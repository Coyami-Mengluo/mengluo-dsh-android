package ai.mengluo.dsh.android;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs only in the separate probe UID, using disposable files and a loopback HTTP fixture. */
@RunWith(AndroidJUnit4.class)
public class RuntimeRepairsTest {
    private File fixture;
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void setup() throws Exception { fixture = Files.createTempDirectory(context.getCacheDir().toPath(), "runtime-repairs-").toFile(); }
    @After public void cleanup() throws Exception {
        assertTrue(fixture.getCanonicalFile().toPath().startsWith(context.getCacheDir().getCanonicalFile().toPath()));
        try (var paths = Files.walk(fixture.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.deleteIfExists(path);
        }
    }
    private JSONObject asset(byte[] bytes) throws Exception {
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format(Locale.ROOT, "%02x", value & 255));
        return new JSONObject().put("name", "node-linux.tgz").put("url", "https://nodejs.org/dist/fixture/node-linux.tgz").put("sha256", digest.toString());
    }
    private File archive() { return new File(fixture, BuildConfig.RUNTIME_ABI + "-node-linux.tgz"); }
    private static HttpURLConnection bytes(byte[] body) throws Exception {
        return new HttpURLConnection(new URL("https://nodejs.org/fixture")) {
            @Override public int getResponseCode() { return 200; }
            @Override public long getContentLengthLong() { return body.length; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
    }
    @Test(timeout = 15000) public void stopUnblocksAndroidSocketAndNeverFallsBackOrLeavesPartial() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(3);
        CountDownLatch bodyRead = new CountDownLatch(1), finishServer = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        OperationCancellation cancellation = new OperationCancellation(threads);
        JSONObject asset = asset(new byte[]{1, 2, 3});
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<?> serving = threads.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line; while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    OutputStream output = socket.getOutputStream();
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 10000000\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.write(new byte[64 * 1024]); output.flush();
                    finishServer.await(6, TimeUnit.SECONDS);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            Future<?> download = threads.submit(() -> {
                assertThrows(OperationCancellation.Stopped.class, () -> RuntimeAssets.obtain(fixture, asset, DownloadSource.MIRROR,
                    ignored -> bodyRead.countDown(), cancellation, url -> {
                        requests.incrementAndGet();
                        return (HttpURLConnection) new URL("http://127.0.0.1:" + server.getLocalPort() + "/fixture").openConnection(Proxy.NO_PROXY);
                    }));
            });
            assertTrue("Download did not begin", bodyRead.await(4, TimeUnit.SECONDS));
            cancellation.cancel();
            download.get(3, TimeUnit.SECONDS);
            assertEquals("Stop must not trigger the official fallback", 1, requests.get());
            assertFalse(archive().exists()); assertFalse(new File(archive().getPath() + ".partial").exists());
            // The same serial executor can accept work again without waiting for a network timeout.
            assertEquals("next", threads.submit(() -> "next").get(1, TimeUnit.SECONDS));
            finishServer.countDown(); serving.get(2, TimeUnit.SECONDS);
        } finally { finishServer.countDown(); cancellation.cancel(); threads.shutdownNow(); threads.awaitTermination(3, TimeUnit.SECONDS); }
    }
    @Test public void realDownloadFailureStillFallsBackAndVerifiesExactBytes() throws Exception {
        byte[] payload = "verified archive fixture".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger(); HttpURLConnection success = bytes(payload);
        File result = RuntimeAssets.obtain(fixture, asset(payload), DownloadSource.MIRROR, ignored -> {}, new OperationCancellation(Runnable::run), url -> {
            if (requests.incrementAndGet() == 1) throw new IOException("mirror unavailable");
            assertTrue(url.startsWith("https://nodejs.org/")); return success;
        });
        assertEquals(2, requests.get()); assertArrayEquals(payload, Files.readAllBytes(result.toPath()));
        assertFalse(new File(result.getPath() + ".partial").exists());
    }
    @Test public void stoppedInstallPreservesVerifiedCacheAndFreshInstallCanReuseIt() throws Exception {
        byte[] payload = "existing archive".getBytes(StandardCharsets.UTF_8); Files.write(archive().toPath(), payload);
        OperationCancellation stopped = new OperationCancellation(Runnable::run); stopped.cancel();
        assertThrows(OperationCancellation.Stopped.class, () -> RuntimeAssets.obtain(fixture, asset(payload), DownloadSource.MIRROR, ignored -> {}, stopped));
        File reused = RuntimeAssets.obtain(fixture, asset(payload), DownloadSource.MIRROR, ignored -> {}, new OperationCancellation(Runnable::run),
            url -> { throw new AssertionError("Cache reuse must not use the network"); });
        assertArrayEquals(payload, Files.readAllBytes(reused.toPath()));
    }
    @Test public void invalidHashNeverBecomesACompletedArchive() throws Exception {
        HttpURLConnection corrupt = bytes(new byte[]{9, 9, 9});
        assertThrows(IOException.class, () -> RuntimeAssets.obtain(fixture, asset(new byte[]{1, 2, 3}), DownloadSource.OFFICIAL,
            ignored -> {}, new OperationCancellation(Runnable::run), url -> corrupt));
        assertFalse(archive().exists()); assertFalse(new File(archive().getPath() + ".partial").exists());
    }
    @Test public void privateEditorPreservesExecutableAndRestrictiveModes() throws Exception {
        File workspace = new File(fixture, "workspace"), rootfs = new File(fixture, "rootfs"); workspace.mkdirs(); rootfs.mkdirs();
        ProjectFiles files = new ProjectFiles(workspace, rootfs);
        ProjectFiles.Project project = new ProjectFiles.Project("fixture", "/workspace");
        File script = files.create(project, "", "run.sh");
        for (String mode : List.of("rwxr-xr-x", "rw-r-----", "rwx------")) {
            var expected = PosixFilePermissions.fromString(mode); Files.setPosixFilePermissions(script.toPath(), expected);
            String previous = files.readText(project, "run.sh");
            files.saveText(project, "run.sh", previous, "#!/bin/sh\necho fixture\n");
            assertEquals(expected, Files.getPosixFilePermissions(script.toPath()));
        }
    }
    @Test public void sharedStorageEditorStillSavesOnFuse() throws Exception {
        File storage = context.getExternalFilesDir(null); assertNotNull(storage);
        File directory = Files.createTempDirectory(storage.toPath(), "edit-repairs-").toFile();
        File script = new File(directory, "fixture.txt");
        try {
            ProjectFiles files = new ProjectFiles(directory, fixture);
            ProjectFiles.Project project = new ProjectFiles.Project("fixture", "/workspace");
            assertTrue(script.createNewFile()); var mode = Files.getPosixFilePermissions(script.toPath());
            files.saveText(project, "fixture.txt", "", "updated");
            assertEquals("updated", files.readText(project, "fixture.txt"));
            assertEquals(mode, Files.getPosixFilePermissions(script.toPath()));
        } finally { Files.deleteIfExists(script.toPath()); Files.deleteIfExists(directory.toPath()); }
    }
    @Test public void offlineResolverKeepsPreviousDnsAndSafelyReplacesLinks() throws Exception {
        File resolver = new File(fixture, "resolv.conf"), original = new File(fixture, "original");
        IO.text(resolver, "nameserver 192.0.2.53\n"); RuntimeDns.update(resolver, List.of());
        assertEquals("nameserver 192.0.2.53\n", IO.text(resolver));
        Files.delete(resolver.toPath()); IO.text(original, "keep");
        Files.createSymbolicLink(resolver.toPath(), original.toPath());
        RuntimeDns.update(resolver, List.of());
        assertFalse(Files.isSymbolicLink(resolver.toPath())); assertEquals("keep", IO.text(original));
        assertFalse(IO.text(resolver).contains("nameserver"));
    }
    @Test public void largeLineIsOmittedAndNormalOutputContinues() throws Exception {
        List<String> lines = new ArrayList<>();
        RuntimeOutput.read(new StringReader("x".repeat(1024 * 1024) + "\nnext\n"), lines::add);
        assertEquals(List.of(RuntimeOutput.OMITTED, "next"), lines);
    }
}
