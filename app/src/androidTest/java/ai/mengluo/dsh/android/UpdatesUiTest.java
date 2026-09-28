package ai.mengluo.dsh.android;
import android.content.Intent;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UpdatesUiTest {
    @Test public void nativeUpdatesPageOpensAndKeepsSystemInstallerSeparate() throws Exception {
        var i = InstrumentationRegistry.getInstrumentation();
        MainActivity a = (MainActivity)i.startActivitySync(new Intent(i.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        UpdatesDialog page = new UpdatesDialog(a);
        try {
            i.runOnMainSync(page::show); i.waitForIdleSync(); Thread.sleep(350);
            Bitmap screenshot = i.getUiAutomation().takeScreenshot(); assertNotNull(screenshot);
            File directory = new File(a.getCacheDir(), "verification"); directory.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(directory, "updates.png"))) { screenshot.compress(Bitmap.CompressFormat.PNG,100,out); }
            screenshot.recycle();
            // Merely opening the page must not download an APK or start a Harness install.
            assertFalse(AndroidUpdates.get(a).downloading); assertFalse(Engine.get(a).busy);
        } finally { i.runOnMainSync(() -> { page.close(); a.finish(); }); }
    }
}
