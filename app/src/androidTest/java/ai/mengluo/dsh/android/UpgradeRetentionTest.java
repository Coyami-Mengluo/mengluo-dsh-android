package ai.mengluo.dsh.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** Opt-in, two invocations around adb install -r; no profile/chat reads or runtime reinstall. */
@RunWith(AndroidJUnit4.class)
public class UpgradeRetentionTest {
    @Test public void coveredUpgradeKeepsRuntimeWorkspaceAndPreferences() throws Exception {
        String phase = InstrumentationRegistry.getArguments().getString("upgradeRetentionPhase");
        assumeNotNull(phase);
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Engine engine = Engine.get(context);
        assertTrue("An existing runtime is required; this test must not install one", engine.installed());
        File baseline = new File(context.getCacheDir(), "upgrade-retention-test.json");
        File marker = new File(engine.workspace, ".mengluo-upgrade-retention-test");
        File state = new File(engine.rootfs.getParentFile(), "state.json");
        String stateHash = Arrays.toString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(state.toPath())));
        long code = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        if (phase.equals("prepare")) {
            assertFalse("Do not replace an earlier test's baseline", baseline.exists());
            assertTrue("Do not overwrite an existing workspace file", marker.createNewFile());
            String value = UUID.randomUUID().toString(); IO.text(marker, value);
            IO.text(baseline, new JSONObject().put("value", value).put("code", code).put("state", stateHash)
                .put("version", engine.currentVersion()).put("source", engine.downloadSource().id).toString());
        } else {
            assertEquals("verify", phase);
            JSONObject saved = new JSONObject(IO.text(baseline));
            assertTrue(code > saved.getLong("code"));
            assertEquals(saved.getString("state"), stateHash);
            assertEquals(saved.getString("version"), engine.currentVersion());
            assertEquals(saved.getString("source"), engine.downloadSource().id);
            assertEquals(saved.getString("value"), IO.text(marker));
            Files.delete(marker.toPath()); Files.delete(baseline.toPath());
        }
    }
}
