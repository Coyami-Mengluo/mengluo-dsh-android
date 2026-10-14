package ai.mengluo.dsh.android;

import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

/** Opt-in preparation for ROMs that block activity starts from a background test process. */
public final class DeviceForegroundListener extends RunListener {
    @Override public void testStarted(Description description) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        String packageName = instrumentation.getTargetContext().getPackageName();
        if (!"ai.mengluo.dsh.android".equals(packageName)) {
            throw new IllegalStateException("Foreground preparation only supports the regular client test package");
        }
        var automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String command = "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "
            + packageName + "/ai.mengluo.dsh.android.MainActivity";
        String result;
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command));
             var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (output.size() + count > 65536) throw new IllegalStateException("Unexpected activity launch output");
                output.write(buffer, 0, count);
            }
            result = output.toString(StandardCharsets.UTF_8.name());
        }
        if (!result.contains("Status: ok") || result.contains("Error:")) {
            throw new IllegalStateException("Unable to foreground the test app: " + result);
        }
        instrumentation.waitForIdleSync();
    }
}
