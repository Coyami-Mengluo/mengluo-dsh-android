package ai.mengluo.dsh.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class AptSetupTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void architectureAndSecurityUseCorrectRepositories() {
        assertEquals("https://mirrors.ustc.edu.cn/ubuntu-ports/", AptSetup.repository(DownloadSource.MIRROR, "arm64-v8a", false));
        assertEquals("https://mirrors.ustc.edu.cn/ubuntu/", AptSetup.repository(DownloadSource.MIRROR, "x86_64", true));
        assertEquals("https://ports.ubuntu.com/ubuntu-ports/", AptSetup.repository(DownloadSource.OFFICIAL, "arm64-v8a", true));
        assertEquals("https://security.ubuntu.com/ubuntu/", AptSetup.repository(DownloadSource.OFFICIAL, "x86_64", true));
        assertThrows(IllegalArgumentException.class, () -> AptSetup.sourceFile(DownloadSource.MIRROR, "../bad"));
    }
    @Test public void sourcesKeepOfficialSignaturesAndOnlyRequiredComponents() {
        for (DownloadSource source : DownloadSource.values()) for (String abi : List.of("arm64-v8a", "x86_64")) {
            String text = AptSetup.sources(source, abi);
            assertTrue(text.contains("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"));
            assertTrue(text.contains("noble-security")); assertTrue(text.contains("Components: main universe"));
            assertTrue(text.contains("Architectures: " + (abi.equals("arm64-v8a") ? "arm64" : "amd64")));
            assertFalse(text.contains("http://")); assertFalse(text.contains("trusted=yes")); assertFalse(text.contains("backports"));
        }
    }
    @Test public void commandsEnforceTlsAndSeparateDownloadFromInstall() {
        for (AptSetup.Stage stage : AptSetup.Stage.values()) {
            List<String> args = AptSetup.command(DownloadSource.MIRROR, "arm64-v8a", stage);
            for (String required : List.of("Acquire::Languages=none", "Dir::Etc::sourceparts=-", "Acquire::https::Verify-Peer=true",
                "Acquire::https::Verify-Host=true", "APT::Get::AllowUnauthenticated=false", "Acquire::AllowInsecureRepositories=false"))
                assertTrue(args.contains(required));
            assertEquals(stage == AptSetup.Stage.DOWNLOAD, args.contains("--download-only"));
            assertEquals(stage == AptSetup.Stage.INSTALL, args.contains("--no-download"));
        }
    }
    private File root(String release) throws Exception {
        File root = temporary.newFolder();
        new File(root, "usr/lib").mkdirs(); IO.text(new File(root, "usr/lib/os-release"), "ID=ubuntu\nVERSION_CODENAME=" + release + "\n");
        new File(root, "usr/share/keyrings").mkdirs(); IO.text(new File(root, "usr/share/keyrings/ubuntu-archive-keyring.gpg"), "fixture-keyring");
        new File(root, "etc/apt/sources.list.d").mkdirs(); IO.text(new File(root, "etc/apt/sources.list.d/ubuntu.sources"), "original-user-config");
        return root;
    }
    @Test public void preparationDoesNotOverwriteUserSources() throws Exception {
        File root = root("noble"); String ca = "-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----\n";
        AptSetup.prepare(root, "arm64-v8a", ca);
        assertEquals("original-user-config", IO.text(new File(root, "etc/apt/sources.list.d/ubuntu.sources")));
        assertEquals(ca, IO.text(new File(root, AptSetup.CA_FILE.substring(1))));
        assertEquals(AptSetup.sources(DownloadSource.MIRROR, "arm64-v8a"), IO.text(new File(root, AptSetup.sourceFile(DownloadSource.MIRROR, "arm64-v8a").substring(1))));
    }
    @Test public void wrongDistroOrMissingCaFailsClosed() throws Exception {
        assertThrows(IOException.class, () -> AptSetup.prepare(root("jammy"), "arm64-v8a", "-----BEGIN CERTIFICATE-----"));
        assertThrows(IOException.class, () -> AptSetup.prepare(root("noble"), "arm64-v8a", ""));
    }
    private CommandFailure failure() { return new CommandFailure("apt-get", "退出码 100", new CommandFailure.Output()); }
    @Test public void downloadFailureFallsBackExactlyOnce() throws Exception {
        ArrayList<List<String>> calls = new ArrayList<>();
        AptSetup.install(DownloadSource.MIRROR, "arm64-v8a", (args, seconds) -> {
            calls.add(args); assertTrue(seconds > 0 && seconds <= 600);
            if (calls.size() == 2) throw failure();
        }, ignored -> {}, () -> {});
        assertEquals(5, calls.size());
        assertTrue(calls.get(0).contains("Dir::Etc::sourcelist=" + AptSetup.sourceFile(DownloadSource.MIRROR, "arm64-v8a")));
        assertTrue(calls.get(2).contains("Dir::Etc::sourcelist=" + AptSetup.sourceFile(DownloadSource.OFFICIAL, "arm64-v8a")));
        assertTrue(calls.get(4).contains("--no-download"));
    }
    @Test public void localInstallFailureDoesNotRetryOnAnotherMirror() {
        ArrayList<List<String>> calls = new ArrayList<>();
        Exception error = assertThrows(IOException.class, () -> AptSetup.install(DownloadSource.MIRROR, "x86_64", (args, seconds) -> {
            calls.add(args); if (args.contains("--no-download")) throw failure();
        }, ignored -> {}, () -> {}));
        assertEquals(3, calls.size()); assertTrue(error.getMessage().contains("不是下载阶段"));
    }
    @Test public void cancellationDoesNotTriggerFallback() {
        ArrayList<List<String>> calls = new ArrayList<>();
        assertThrows(IOException.class, () -> AptSetup.install(DownloadSource.MIRROR, "x86_64", (args, seconds) -> calls.add(args),
            ignored -> {}, () -> { if (!calls.isEmpty()) throw new IOException("stopped"); }));
        assertEquals(1, calls.size());
    }
    @Test public void officialFailureNeverSwitchesToMirror() {
        ArrayList<List<String>> calls = new ArrayList<>();
        assertThrows(IOException.class, () -> AptSetup.install(DownloadSource.OFFICIAL, "x86_64", (args, seconds) -> {
            calls.add(args); throw failure();
        }, ignored -> {}, () -> {}));
        assertEquals(1, calls.size());
    }
    private CommandFailure interrupted() {
        var output = new CommandFailure.Output();
        output.add("ERROR: ld.so: object 'libtalloc.so' cannot be preloaded: ignored.");
        output.add("E: dpkg was interrupted, you must manually run 'dpkg --configure -a' to correct the problem.");
        return new CommandFailure("apt-get", "退出码 100", output);
    }
    @Test public void interruptedDpkgIsRecoveredOnceWithoutRedownloadingIndexesOrSwitchingSource() throws Exception {
        ArrayList<List<String>> calls = new ArrayList<>();
        AptSetup.install(DownloadSource.MIRROR, "arm64-v8a", (args, seconds) -> {
            calls.add(args); if (calls.size() == 2) throw interrupted();
        }, ignored -> {}, () -> {});
        assertEquals(5, calls.size()); assertEquals(List.of("/usr/bin/dpkg", "--configure", "-a"), calls.get(2));
        assertTrue(calls.get(3).contains("--download-only")); assertTrue(calls.get(4).contains("--no-download"));
        assertEquals(1, calls.stream().filter(args -> args.contains("update")).count());
        assertFalse(calls.stream().anyMatch(args -> args.stream().anyMatch(arg -> arg.contains("official-arm64"))));
    }
    @Test public void failedRecoveryStopsInsteadOfSwitchingSourceOrLooping() {
        ArrayList<List<String>> calls = new ArrayList<>();
        Exception error = assertThrows(IOException.class, () -> AptSetup.install(DownloadSource.MIRROR, "arm64-v8a", (args, seconds) -> {
            calls.add(args); if (calls.size() == 2) throw interrupted(); if (calls.size() == 3) throw failure();
        }, ignored -> {}, () -> {}));
        assertEquals(3, calls.size()); assertTrue(error.getMessage().contains("恢复上次"));
    }
    @Test public void repeatedInterruptionAndLocalDiskErrorsDoNotRetryOnOfficialSource() {
        ArrayList<List<String>> calls = new ArrayList<>();
        assertThrows(IOException.class, () -> AptSetup.install(DownloadSource.MIRROR, "arm64-v8a", (args, seconds) -> {
            calls.add(args); if (args.contains("--download-only")) throw interrupted();
        }, ignored -> {}, () -> {}));
        assertEquals(4, calls.size());
        calls.clear(); var output = new CommandFailure.Output(); output.add("E: Write error - write (28: No space left on device)");
        assertThrows(IOException.class, () -> AptSetup.install(DownloadSource.MIRROR, "arm64-v8a", (args, seconds) -> {
            calls.add(args); throw new CommandFailure("apt-get", "exit 100", output);
        }, ignored -> {}, () -> {}));
        assertEquals(1, calls.size());
    }
}
