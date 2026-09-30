package ai.mengluo.dsh.android;

import android.content.*;
import android.os.Environment;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Run prepare, revoke with adb (Android kills the app), denied, regrant, then verify. */
@RunWith(AndroidJUnit4.class)
public class WorkspacePermissionRestartTest {
    @Test public void selectionSurvivesActualPermissionRevocationAndProcessRestart() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String phase = InstrumentationRegistry.getArguments().getString("workspacePermissionPhase");
        org.junit.Assume.assumeNotNull("Explicit prepare/denied/verify phase required", phase);
        assertTrue("Unknown test phase", List.of("prepare", "denied", "verify").contains(phase));
        SharedPreferences prefs = context.getSharedPreferences("test-workspace-permission-restart", 0);
        WorkspaceStore store = new WorkspaceStore(prefs, Environment.getExternalStorageDirectory(), Environment::isExternalStorageManager);
        if (phase.equals("prepare")) {
            assertTrue(Environment.isExternalStorageManager()); assertNull("Finish/clean up the previous fixture first", store.selected());
            File folder = Files.createTempDirectory(Environment.getExternalStorageDirectory().toPath(), "MengLuo-permission-test-").toFile();
            IO.text(new File(folder, "keep.txt"), "permission-restart-original"); store.select(folder); return;
        }
        File selected = store.selected(); assertNotNull("Run the prepare phase first", selected);
        assertTrue(selected.getName().startsWith("MengLuo-permission-test-"));
        if (phase.equals("denied")) {
            assertFalse("Revoke storage permission before this phase", Environment.isExternalStorageManager());
            assertThrows(IOException.class, store::active); assertEquals(selected, store.selected());
            Engine engine = new Engine(context);
            assertThrows(IOException.class, () -> engine.spawn(List.of("/bin/true"), null, engine.workspace, engine.profile, selected));
            return;
        }
        assertEquals("verify", phase); assertTrue(Environment.isExternalStorageManager());
        assertEquals(selected, store.active()); assertEquals("permission-restart-original", IO.text(new File(selected, "keep.txt")));
        store.select(null); Files.delete(new File(selected, "keep.txt").toPath()); Files.delete(selected.toPath());
    }
}
