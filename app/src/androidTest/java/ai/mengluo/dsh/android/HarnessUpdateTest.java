package ai.mengluo.dsh.android;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.UUID;
import static org.junit.Assert.*;

/** Explicit network test. Entire base, projects, profiles and version state are disposable fixtures. */
@RunWith(AndroidJUnit4.class)
public class HarnessUpdateTest {
    @Test public void installCandidateFailureRollbackAndReopen() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixture = InstrumentationRegistry.getArguments().getString("runtimeFixture", "update-integration-" + UUID.randomUUID());
        if (!fixture.matches("update-integration-[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid disposable fixture");
        File area = new File(app.getCacheDir(), fixture); area.mkdirs();
        Context isolated = new ContextWrapper(app) {
            @Override public File getFilesDir() { File f = new File(area, "files"); f.mkdirs(); return f; }
            @Override public File getCacheDir() { File f = new File(area, "cache"); f.mkdirs(); return f; }
        };
        Engine engine = new Engine(isolated);
        try {
            engine.install("0.1.7-rc.2"); await(engine, 2100); assertTrue(engine.logs(), engine.installed());
            String first = engine.versions.active().path;
            File project = new File(engine.rootfs, "root/regression-project/keep.txt"); project.getParentFile().mkdirs(); IO.text(project, "keep");
            File profile = new File(engine.profile, "keep.txt"); IO.text(profile, "fixture-profile");
            engine.install("0.1.7-rc.2"); await(engine, 1900); assertTrue(engine.logs(), engine.installed());
            assertNotEquals(first, engine.versions.active().path); assertEquals(first, engine.versions.previous().path);
            assertEquals("keep", IO.text(project)); assertEquals("fixture-profile", IO.text(profile));
            String next = engine.versions.active().path;
            engine.install("9999.0.0"); await(engine, 90); assertEquals(next, engine.versions.active().path);
            engine.rollback(); await(engine, 180); assertEquals(engine.logs(), first, engine.versions.active().path);
            assertEquals(first, new RuntimeStore(engine.rootfs).active().path);
            assertEquals("keep", IO.text(project)); assertEquals("fixture-profile", IO.text(profile));
            engine.start(); await(engine, 150); assertNotNull(engine.logs(), engine.readyUrl);
        } finally { engine.stop(); }
    }
    private void await(Engine engine, int seconds) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
        while (engine.busy) { if (System.nanoTime() > until) fail(engine.status + "\n" + engine.logs()); Thread.sleep(250); }
    }
}
