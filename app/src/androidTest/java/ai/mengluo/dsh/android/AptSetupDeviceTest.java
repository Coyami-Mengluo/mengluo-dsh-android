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
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "apt-003-test-").toFile();
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
        } finally { removeFixture(root); }
    }
    private static void run(Context context, File root, List<String> command, int seconds) throws Exception {
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
