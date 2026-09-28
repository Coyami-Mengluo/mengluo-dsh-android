package ai.mengluo.dsh.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RuntimeStoreTest {
    private RuntimeStore fixture() {
        File dir = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "store-test-" + UUID.randomUUID() + "/rootfs");
        dir.mkdirs(); return new RuntimeStore(dir);
    }
    private void packageFor(RuntimeStore store, RuntimeStore.Slot slot) throws Exception {
        File pkg = new File(store.rootfs, slot.path.substring(1) + "/node_modules/@deepseek-ai/dsh"); new File(pkg, "lib").mkdirs();
        IO.text(new File(pkg, "package.json"), new JSONObject().put("name", RegistryClient.NAME).put("version", slot.version).toString());
        IO.text(new File(pkg, "lib/bin.js"), "// test fixture");
    }
    @Test public void migratesLegacyWithoutChangingAnyUserFileAndSurvivesReopen() throws Exception {
        RuntimeStore store = fixture(); RuntimeStore.Slot old = new RuntimeStore.Slot("0.1.7-rc.2", "/opt/harness", "11.7.0"); packageFor(store, old);
        File project = new File(store.rootfs, "root/user-project/keep.txt"); project.getParentFile().mkdirs(); IO.text(project, "keep");
        IO.atomicText(new File(store.rootfs.getParentFile(), "state.json"), new JSONObject().put("schema", 2).put("version", old.version).put("abi", BuildConfig.RUNTIME_ABI).toString());
        assertEquals(old.version, store.active().version);
        RuntimeStore.Slot next = new RuntimeStore.Slot("0.1.8", "/opt/harness-slots/v0.1.8-test", "11.7.0"); packageFor(store, next); store.activate(next);
        RuntimeStore reopened = new RuntimeStore(store.rootfs);
        assertEquals(next.version, reopened.active().version); assertEquals(old.version, reopened.previous().version); assertEquals("keep", IO.text(project));
        reopened.activate(reopened.previous()); assertEquals(old.version, new RuntimeStore(store.rootfs).active().version);
    }
    @Test public void rejectsUnfinishedCandidateAndRecoversInterruptedAtomicWrite() throws Exception {
        RuntimeStore store = fixture(); RuntimeStore.Slot old = new RuntimeStore.Slot("0.1.7-rc.2", "/opt/harness", "11.7.0"); packageFor(store, old); store.activate(old);
        try { store.activate(new RuntimeStore.Slot("0.1.8", "/opt/harness-slots/missing", "11.7.0")); fail(); } catch (IOException expected) { }
        android.util.AtomicFile atomic = new android.util.AtomicFile(new File(store.rootfs.getParentFile(), "state.json"));
        FileOutputStream out = atomic.startWrite(); out.write("broken".getBytes()); out.close(); // Simulate process death, not finishWrite.
        assertEquals(old.version, new RuntimeStore(store.rootfs).active().version);
    }
    @Test public void dynamicPortAndInvalidPorts() {
        assertEquals("http://127.0.0.1:51321/?token=abc_-123", Engine.readyAddress("dsh web: http://127.0.0.1:51321/?token=abc_-123"));
        assertEquals("http://127.0.0.1:41555?token=abc", Engine.readyAddress("dsh web: http://127.0.0.1:41555?token=abc"));
        assertNull(Engine.readyAddress("dsh web: http://127.0.0.1:99999/?token=abc"));
        assertNull(Engine.readyAddress("dsh web: http://evil.test:80/?token=abc"));
    }
}
