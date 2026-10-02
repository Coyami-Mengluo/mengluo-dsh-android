package ai.mengluo.dsh.android;

import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Isolated state and log files only; no user backend, model credentials, or projects. */
@RunWith(AndroidJUnit4.class)
public class EngineLifecycleLogTest {
    private Path fixture;
    private Engine engine;
    @Before public void setup() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fixture = Files.createTempDirectory(context.getCacheDir().toPath(), "engine-log-test-");
        engine = new Engine(new ContextWrapper(context) {
            @Override public File getFilesDir() { return fixture.toFile(); }
        });
    }
    @After public void cleanup() throws Exception {
        if (engine != null) {
            for (String name : new String[]{"work", "readers"}) {
                var field = Engine.class.getDeclaredField(name); field.setAccessible(true);
                ExecutorService executor = (ExecutorService) field.get(engine); executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
            var field = Engine.class.getDeclaredField("log"); field.setAccessible(true); ((RuntimeLog) field.get(engine)).close();
        }
        if (fixture != null) try (var paths = Files.walk(fixture)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
        }
    }
    private void backend(Process process, long epoch) throws Exception {
        var field = Engine.class.getDeclaredField("backend"); field.setAccessible(true); field.set(engine, process);
        field = Engine.class.getDeclaredField("generation"); field.setAccessible(true); field.setLong(engine, epoch);
        engine.busy = true;
    }
    private String persisted() throws Exception {
        try (RuntimeLog reopened = new RuntimeLog(fixture.resolve("logs").toFile())) {
            return new String(reopened.snapshot("test"), StandardCharsets.UTF_8);
        }
    }
    @Test public void unexpectedExitRecordsCodeAndClearsCurrentState() throws Exception {
        Process process = new IdleProcess(); backend(process, 4); engine.readyUrl = "http://127.0.0.1:1234/?token=secret";
        engine.backendExited(process, 4, 17);
        assertFalse(engine.busy); assertNull(engine.readyUrl);
        assertTrue(persisted().contains("退出码 17（进程自行结束）"));
        assertFalse(persisted().contains("secret"));
    }
    @Test public void timeoutAndStopArePersistedAndOldTimerDoesNotRepeat() throws Exception {
        IdleProcess process = new IdleProcess(); backend(process, 8);
        engine.startupTimedOut(process, 8);
        assertFalse(engine.busy); assertFalse(process.isAlive());
        assertTrue(engine.status.contains("120 秒"));
        String before = persisted(); assertTrue(before.contains("启动超时")); assertTrue(before.contains("已请求停止运行进程"));
        engine.startupTimedOut(process, 8); assertEquals(before, persisted());
        engine.backendExited(process, 8, 143);
        assertTrue(persisted().contains("退出码 143（已请求停止）"));
    }
    @Test public void staleExitAndTimeoutDoNotClearNewBackend() throws Exception {
        IdleProcess current = new IdleProcess(); backend(current, 11); engine.readyUrl = "http://127.0.0.1:1234/";
        engine.startupTimedOut(current, 10);
        engine.startupTimedOut(current, 11); // Already ready, so this timer must do nothing.
        engine.backendExited(new IdleProcess(), 10, 0);
        assertTrue(engine.busy); assertNotNull(engine.readyUrl); assertTrue(current.isAlive());
        assertFalse(persisted().contains("启动超时"));
    }
    private static final class IdleProcess extends Process {
        private volatile boolean alive = true;
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return getInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { alive = false; }
        @Override public boolean isAlive() { return alive; }
    }
}
