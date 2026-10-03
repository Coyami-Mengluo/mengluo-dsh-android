package ai.mengluo.dsh.android;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Xml;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Private probe preferences and native views only: no runtime, HTTP bridge, or system grants. */
@RunWith(AndroidJUnit4.class)
public class PhoneConfirmationSettingsTest {
    private static final String PROBE = "ai.mengluo.dsh.android.phoneprobe";
    private static final String KEY = "skip-action-confirmation";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context context;
    private PhoneControl control;
    private SharedPreferences preferences, updates;
    private boolean restorePreferences, hadPreference, previousPreference, hadAutomatic, previousAutomatic;

    @Before public void setup() throws Exception {
        context = instrumentation.getTargetContext();
        Assume.assumeTrue("Only the separately packaged phone probe may run these tests", PROBE.equals(context.getPackageName()));
        assertEquals("Refuse production package", PROBE, context.getPackageName());
        onMain(() -> control = PhoneControl.get(context));
        assertFalse("The test requires no overlay grant and never changes it", Settings.canDrawOverlays(context));
        assertFalse("The test requires no probe accessibility grant", control.enabled());
        assertNull("Do not reuse a connected accessibility service", PhoneAccess.connected);
        assertFalse("Do not reuse screen sharing", PhoneCaptureService.available());
        assertFalse("Do not start or replace an existing runtime", (boolean) field("runtime").get(control));
        assertFalse(control.state.live());
        assertNull(field("pending").get(control));
        preferences = context.getSharedPreferences("phone-control", Context.MODE_PRIVATE);
        updates = context.getSharedPreferences("updates", Context.MODE_PRIVATE);
        hadPreference = preferences.contains(KEY); previousPreference = preferences.getBoolean(KEY, false);
        hadAutomatic = updates.contains("automatic"); previousAutomatic = updates.getBoolean("automatic", true);
        restorePreferences = true;
        assertTrue(preferences.edit().remove(KEY).commit());
        // MainActivity normally checks releases; this private probe UI must stay offline.
        assertTrue(updates.edit().putBoolean("automatic", false).commit());
    }

    @After public void cleanup() throws Exception {
        if (!restorePreferences) return;
        try {
            onMain(() -> {
                field("runtime").setBoolean(control, false);
                control.stop("user_cancelled");
            });
            assertFalse("Tests must not acquire overlay permission", Settings.canDrawOverlays(context));
            assertFalse("Tests must not acquire accessibility permission", control.enabled());
            assertNull(PhoneAccess.connected);
        } finally {
            SharedPreferences.Editor phone = preferences.edit();
            if (hadPreference) phone.putBoolean(KEY, previousPreference); else phone.remove(KEY);
            assertTrue(phone.commit());
            SharedPreferences.Editor update = updates.edit();
            if (hadAutomatic) update.putBoolean("automatic", previousAutomatic); else update.remove("automatic");
            assertTrue(update.commit());
        }
    }

    @Test(timeout = 10000) public void defaultIsOffAndBothValuesPersistOutsideController() throws Exception {
        assertFalse(preferences.contains(KEY));
        assertFalse(control.skipActionConfirmation());
        onMain(() -> assertFalse(newController().skipActionConfirmation()));
        for (boolean enabled : new boolean[]{true, false}) {
            onMain(() -> control.skipActionConfirmation(enabled));
            assertEquals(enabled, control.skipActionConfirmation());
            assertTrue(preferences.contains(KEY));
            assertEquals(enabled, preferences.getBoolean(KEY, !enabled));
            // Commit waits for preceding apply writes; inspect the probe's actual preference file.
            assertTrue(preferences.edit().commit());
            assertEquals(enabled, diskPreference());
            onMain(() -> assertEquals("A fresh controller must use persisted state", enabled, newController().skipActionConfirmation()));
        }
    }

    @Test(timeout = 10000) public void changingPreferenceOffMainIsRejectedWithoutSaving() {
        assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
        assertThrows(IllegalStateException.class, () -> control.skipActionConfirmation(true));
        assertFalse(control.skipActionConfirmation());
        assertFalse(preferences.contains(KEY));
        assertFalse(control.state.live());
    }

    @Test(timeout = 10000) public void eitherDirectionRevokesLeaseAndNeverApprovesPendingAction() throws Exception {
        for (boolean enabled : new boolean[]{true, false}) {
            String owner = UUID.randomUUID().toString().replace("-", "").repeat(2);
            CompletableFuture<JSONObject> response = new CompletableFuture<>();
            AtomicInteger executions = new AtomicInteger();
            onMain(() -> {
                assertEquals("preparing", control.state.begin(owner, SystemClock.elapsedRealtime()));
                String lease = control.state.lease();
                assertTrue(control.state.approve(owner, lease));
                assertTrue(control.state.allowed(owner, lease));
                field("pending").set(control, response);
                field("confirmAction").set(control, (Runnable) executions::incrementAndGet);
                field("confirmation").set(control, "Harmless test action awaiting confirmation");
                control.skipActionConfirmation(enabled);
                assertEquals(enabled, control.skipActionConfirmation());
                assertFalse("Changing a setting revokes the existing capability", control.state.allowed(owner, lease));
                assertFalse(control.state.live());
                assertEquals("user_cancelled", control.state.phase(owner));
                assertEquals("A revoked owner cannot resume", "user_cancelled", control.state.begin(owner, SystemClock.elapsedRealtime()));
                assertNull(field("confirmAction").get(control));
                assertNull(field("pending").get(control));
                assertEquals("", field("confirmation").get(control));
                control.approveAction();
                assertEquals("No pending action may execute as a side effect of changing this setting", 0, executions.get());
            });
            assertEquals("user_cancelled", response.get(2, TimeUnit.SECONDS).getString("status"));
        }
    }

    @Test(timeout = 10000) public void statusReportsSettingButCannotChangeIt() throws Exception {
        Method handle = PhoneControl.class.getDeclaredMethod("handle", JSONObject.class, CompletableFuture.class);
        handle.setAccessible(true);
        for (boolean enabled : new boolean[]{false, true}) {
            onMain(() -> control.skipActionConfirmation(enabled));
            CompletableFuture<JSONObject> response = new CompletableFuture<>();
            JSONObject input = new JSONObject().put("op", "status").put("owner", "f".repeat(64))
                .put("skipActionConfirmation", !enabled).put(KEY, !enabled);
            onMain(() -> {
                // Exercise only the local handler's status branch, without opening a bridge or watchdog.
                field("runtime").setBoolean(control, true);
                try { handle.invoke(control, input, response); }
                finally { field("runtime").setBoolean(control, false); }
            });
            JSONObject value = response.get(2, TimeUnit.SECONDS);
            assertTrue(value.get("skipActionConfirmation") instanceof Boolean);
            assertEquals(enabled, value.getBoolean("skipActionConfirmation"));
            assertEquals("Model-supplied fields cannot write native settings", enabled, control.skipActionConfirmation());
            assertFalse(value.getBoolean("accessibilityEnabled"));
            assertFalse(value.getBoolean("overlayPermission"));
        }
    }

    @Test(timeout = 30000) public void switchCancelKeepsOffConfirmEnablesAndOffIsImmediate() throws Exception {
        final AlertDialog[] guide = new AlertDialog[1];
        final MaterialSwitch[] setting = new MaterialSwitch[1];
        // ActivityScenario's invoker unconditionally adds CLEAR_TASK and starts an EmptyActivity
        // during close. Use a plain launch here; instrumentation owns final activity teardown.
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        try {
                onMain(() -> {
                    guide[0] = PhonePermissions.show(activity);
                    setting[0] = guide[0].getWindow().getDecorView().findViewWithTag("phone-skip-confirmation");
                    assertNotNull(setting[0]);
                    assertFalse(setting[0].isChecked());
                    assertTrue(setting[0].getFilterTouchesWhenObscured());
                    // CompoundButton toggles even when performClick returns false (no OnClickListener).
                    setting[0].performClick();
                    assertFalse("The warning must not save the opt-in", control.skipActionConfirmation());
                    assertFalse("The switch stays off while the warning is open", setting[0].isChecked());
                });
                instrumentation.waitForIdleSync();
                onMain(() -> {
                    Button enable = dialogButton("开启");
                    assertNotNull("Enabling requires a native warning", enable);
                    assertTrue(enable.getFilterTouchesWhenObscured());
                    Button cancel = dialogButton("取消"); assertNotNull(cancel); assertTrue(cancel.performClick());
                });
                instrumentation.waitForIdleSync();
                onMain(() -> {
                    assertFalse(control.skipActionConfirmation());
                    assertFalse(setting[0].isChecked());
                    assertFalse("Cancellation must not persist an opt-in", preferences.contains(KEY));
                    assertNull(dialogButton("开启"));
                    setting[0].performClick();
                    assertFalse(control.skipActionConfirmation());
                });
                instrumentation.waitForIdleSync();
                onMain(() -> {
                    Button enable = dialogButton("开启"); assertNotNull(enable); assertTrue(enable.performClick());
                });
                instrumentation.waitForIdleSync();
                onMain(() -> {
                    assertTrue(control.skipActionConfirmation());
                    assertTrue(setting[0].isChecked());
                    assertNull(dialogButton("开启"));
                    setting[0].performClick();
                    assertFalse("Disabling takes effect immediately", control.skipActionConfirmation());
                    assertFalse(setting[0].isChecked());
                    assertNull("Disabling must not require an opt-in warning", dialogButton("开启"));
                    guide[0].dismiss();
                    guide[0] = PhonePermissions.show(activity);
                    MaterialSwitch reopened = guide[0].getWindow().getDecorView().findViewWithTag("phone-skip-confirmation");
                    assertNotNull(reopened); assertFalse("Reopening must use saved state", reopened.isChecked());
                });
        } finally {
                onMain(() -> {
                    if (dialogButton("开启") != null) { Button cancel = dialogButton("取消"); if (cancel != null) cancel.performClick(); }
                    if (guide[0] != null) guide[0].dismiss();
                });
                instrumentation.waitForIdleSync();
        }
    }

    private PhoneControl newController() throws Exception {
        Constructor<PhoneControl> constructor = PhoneControl.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true); return constructor.newInstance(context);
    }

    private static Field field(String name) throws Exception {
        Field field = PhoneControl.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private boolean diskPreference() throws Exception {
        File file = new File(context.getDataDir(), "shared_prefs/phone-control.xml");
        try (FileInputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser(); parser.setInput(input, "UTF-8");
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG && "boolean".equals(parser.getName()) && KEY.equals(parser.getAttributeValue(null, "name")))
                    return Boolean.parseBoolean(parser.getAttributeValue(null, "value"));
            }
        }
        throw new AssertionError("The native setting was not written to the probe preference file");
    }

    private static Button dialogButton(String text) {
        // Public API enumerates only this process's view roots; never reads another application.
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (!root.isAttachedToWindow()) continue;
            Button button = button(root, text); if (button != null) return button;
        }
        return null;
    }

    private static Button button(View view, String text) {
        if (view instanceof Button button && text.contentEquals(button.getText()) && button.getVisibility() == View.VISIBLE) return button;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Button result = button(group.getChildAt(i), text); if (result != null) return result;
        }
        return null;
    }

    private interface CheckedAction { void run() throws Exception; }
    private void onMain(CheckedAction action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        instrumentation.runOnMainSync(task);
        try { task.get(3, TimeUnit.SECONDS); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Error cause) throw cause;
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }
}
