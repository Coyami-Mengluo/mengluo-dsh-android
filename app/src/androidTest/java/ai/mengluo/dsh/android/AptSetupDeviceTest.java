package ai.mengluo.dsh.android;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** Optional real apt install, confined to a freshly extracted cache fixture (not the user's runtime). */
@RunWith(AndroidJUnit4.class)
public class AptSetupDeviceTest {
    @Test public void httpsMirrorRecoversInterruptedDpkgAndInstallsSignedPackagesWithoutCaBundle() throws Exception {
        String archive = InstrumentationRegistry.getArguments().getString("aptFixturePath");
        assumeNotNull(archive);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "apt-004-test-").toFile();
        try {
            String hash = BuildConfig.RUNTIME_ABI.equals("x86_64")
                ? "6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9"
                : "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048";
            ArchiveInstaller.verify(new File(archive), hash);
            ArchiveInstaller.extract(new File(archive), root, 0, ignored -> {});
            assertFalse(new File(root, "etc/ssl/certs/ca-certificates.crt").exists());
            String original = IO.text(new File(root, "etc/apt/sources.list.d/ubuntu.sources"));
            AptSetup.prepare(root, BuildConfig.RUNTIME_ABI, SystemCertificates.pem());
            // Emulate an interrupted dpkg journal in this isolated root only. apt must not switch mirrors.
            File updates = new File(root, "var/lib/dpkg/updates"); updates.mkdirs();
            String record = IO.text(new File(root, "var/lib/dpkg/status")).split("\n\n", 2)[0] + "\n\n";
            IO.text(new File(updates, "0000"), record);
            android.net.ConnectivityManager cm = context.getSystemService(android.net.ConnectivityManager.class);
            var links = cm.getLinkProperties(cm.getActiveNetwork()); assertNotNull(links);
            StringBuilder dns = new StringBuilder();
            for (var server : links.getDnsServers()) dns.append("nameserver ").append(server.getHostAddress()).append('\n');
            File resolv = new File(root, "etc/resolv.conf"); Files.deleteIfExists(resolv.toPath()); IO.text(resolv, dns.toString());
            ArrayList<String> stages = new ArrayList<>();
            ArrayList<List<String>> commands = new ArrayList<>();
            AptSetup.install(DownloadSource.MIRROR, BuildConfig.RUNTIME_ABI, (args, seconds) -> { commands.add(args); run(context, root, args, seconds); },
                message -> { stages.add(message); android.util.Log.i("MengLuoAptTest", message); }, () -> {});
            assertTrue(new File(root, "usr/bin/git").isFile()); assertTrue(new File(root, "usr/bin/python3").exists());
            assertTrue(new File(root, "etc/ssl/certs/ca-certificates.crt").isFile());
            assertEquals(original, IO.text(new File(root, "etc/apt/sources.list.d/ubuntu.sources")));
            assertTrue(stages.stream().anyMatch(s -> s.contains("本地安装")));
            assertTrue(stages.stream().anyMatch(s -> s.contains("正在恢复")));
            assertEquals(1, commands.stream().filter(args -> args.contains("/usr/bin/dpkg")).count());
            assertEquals(1, commands.stream().filter(args -> args.contains("update")).count());
            assertFalse(new File(updates, "0000").exists());
            run(context, root, List.of("/usr/bin/git", "--version"), 30);
            run(context, root, List.of("/usr/bin/python3", "--version"), 30);
            hardLinkDeniedRegression(context, root);
        } finally { removeFixture(root); }
    }

    private static void hardLinkDeniedRegression(Context context, File root) throws Exception {
        File probe = new File(root, "root/hardlink-regression"); assertTrue(probe.mkdirs());
        try (InputStream script = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("deny-hardlinks.py")) {
            Files.copy(script, new File(probe, "deny-hardlinks.py").toPath());
        }
        String guest = "/root/hardlink-regression";
        // A negative control is mandatory: permissive emulators must really reject link(), not just pass.
        List<String> deniedLink = denied(List.of("/usr/bin/python3", "-c",
            "import os; open('" + guest + "/original','w').write('persistent'); os.link('" + guest + "/original','" + guest + "/linked')"));
        CommandFailure linkFailure = assertThrows(CommandFailure.class, () -> run(context, root, deniedLink, 30, false));
        assertTrue(linkFailure.getMessage(), linkFailure.getMessage().contains("Permission denied"));
        assertFalse(new File(probe, "linked").exists());
        run(context, root, deniedLink, 30);
        // A separate PRoot process must read the same backing files after the first has exited.
        run(context, root, denied(List.of("/usr/bin/python3", "-c",
            "import os; assert open('" + guest + "/linked').read()=='persistent'; assert os.path.samefile('" + guest + "/original','" + guest + "/linked'); assert not os.path.islink('" + guest + "/linked')")), 30);

        File database = new File(probe, "db"); assertTrue(new File(database, "updates").mkdirs());
        assertTrue(new File(database, "info").mkdirs());
        String record = IO.text(new File(root, "var/lib/dpkg/status")).split("\n\n", 2)[0] + "\n\n";
        IO.text(new File(database, "status"), record); IO.text(new File(database, "updates/0000"), record);
        List<String> configure = denied(List.of("/usr/bin/dpkg", "--admindir=" + guest + "/db", "--configure", "-a"));
        CommandFailure dpkgFailure = assertThrows(CommandFailure.class, () -> run(context, root, configure, 30, false));
        assertTrue(dpkgFailure.getMessage(), dpkgFailure.getMessage().contains("status-old"));
        assertTrue(dpkgFailure.getMessage(), dpkgFailure.getMessage().contains("Permission denied"));
        // Retry the exact failed database; never delete status/journal files to fake a recovery.
        run(context, root, configure, 30);
        assertTrue(new File(database, "status-old").isFile());
        assertFalse(new File(database, "updates/0000").exists());
        assertEquals(record, IO.text(new File(database, "status")));

        // Exercise real dpkg unpack/configure and file backups too, without network or user packages.
        File payload = new File(probe, "package"); assertTrue(new File(payload, "DEBIAN").mkdirs());
        assertTrue(new File(payload, "opt/mengluo-hardlink-probe").mkdirs());
        // Android cache mkdirs inherits 02700; a Debian package's control directory requires 0755.
        for (String directory : List.of("", "DEBIAN", "opt", "opt/mengluo-hardlink-probe"))
            android.system.Os.chmod(new File(payload, directory).getPath(), 0755);
        for (int version = 1; version <= 2; version++) {
            IO.text(new File(payload, "DEBIAN/control"), "Package: mengluo-hardlink-probe\nVersion: " + version
                + "\nArchitecture: all\nMaintainer: Test <test@example.invalid>\nDescription: isolated hard-link regression\n");
            IO.text(new File(payload, "opt/mengluo-hardlink-probe/value"), "version " + version);
            android.system.Os.chmod(new File(payload, "DEBIAN/control").getPath(), 0644);
            android.system.Os.chmod(new File(payload, "opt/mengluo-hardlink-probe/value").getPath(), 0644);
            run(context, root, List.of("/usr/bin/dpkg-deb", "--build", "--root-owner-group", guest + "/package", guest + "/probe.deb"), 30);
            run(context, root, denied(List.of("/usr/bin/dpkg", "--install", guest + "/probe.deb")), 60);
            assertEquals("version " + version, IO.text(new File(root, "opt/mengluo-hardlink-probe/value")));
        }
        run(context, root, denied(List.of("/usr/bin/dpkg", "--audit")), 30);
        android.util.Log.i("MengLuoAptTest", "HARDLINK_DENIED_REGRESSION_OK: negative controls failed; same database retry and package upgrade passed");
    }
    private static List<String> denied(List<String> command) {
        ArrayList<String> result = new ArrayList<>(List.of("/usr/bin/python3", "/root/hardlink-regression/deny-hardlinks.py"));
        result.addAll(command); return result;
    }

    @Test public void linkBackingStoreRefusesSymlinksWithoutTouchingTheirTarget() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixture = Files.createTempDirectory(context.getCacheDir().toPath(), "l2s-store-test-").toFile();
        try {
            File root = new File(fixture, "rootfs"), outside = new File(fixture, "outside");
            assertTrue(root.mkdir()); assertTrue(outside.mkdir());
            IO.text(new File(outside, "keep"), "unchanged");
            Files.createSymbolicLink(new File(root, ProotCompatibility.LINK_STORE).toPath(), outside.toPath());
            assertThrows(IOException.class, () -> ProotCompatibility.configure(new ProcessBuilder("proot"), root));
            assertEquals("unchanged", IO.text(new File(outside, "keep"))); assertEquals(1, outside.list().length);
        } finally { removeFixture(fixture); }
    }
    private static void run(Context context, File root, List<String> command, int seconds) throws Exception {
        run(context, root, command, seconds, true);
    }
    private static void run(Context context, File root, List<String> command, int seconds, boolean compatibility) throws Exception {
        String libs = context.getApplicationInfo().nativeLibraryDir;
        ArrayList<String> args = new ArrayList<>(List.of(libs + "/libproot.so", "--kill-on-exit", "-0", "-r", root.getPath(),
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root", "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LANG=C.UTF-8", "TMPDIR=/tmp", "DEBIAN_FRONTEND=noninteractive", "PATH=" + RuntimePolicy.GUEST_PATH));
        args.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(args).directory(context.getFilesDir()).redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", libs);
        builder.environment().put("LD_PRELOAD", libs + "/libtalloc.so:" + libs + "/libandroid-shmem.so");
        builder.environment().put("PROOT_LOADER", libs + "/libproot-loader.so");
        builder.environment().put("PROOT_TMP_DIR", context.getCacheDir().getPath()); builder.environment().put("PROOT_NO_SECCOMP", "1");
        if (compatibility) ProotCompatibility.configure(builder, root);
        Process process = RuntimeProcesses.launch(builder, context.getCacheDir());
        ExecutorService reader = Executors.newSingleThreadExecutor(); CommandFailure.Output tail = new CommandFailure.Output();
        Future<?> output = reader.submit(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = input.readLine()) != null) { tail.add(line); android.util.Log.i("MengLuoAptTest", RuntimePolicy.redact(line)); }
            } catch (IOException error) { throw new UncheckedIOException(error); }
        });
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new CommandFailure("apt fixture", "timeout", tail);
            output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new CommandFailure("apt fixture", "exit " + process.exitValue(), tail);
        } finally { assertTrue(RuntimeProcesses.stopAndWait(process, 5)); reader.shutdownNow(); }
    }
    private static void removeFixture(File file) throws IOException {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles(); if (children == null) throw new IOException("Cannot list fixture");
            for (File child : children) removeFixture(child);
        }
        Files.delete(file.toPath());
    }
}
