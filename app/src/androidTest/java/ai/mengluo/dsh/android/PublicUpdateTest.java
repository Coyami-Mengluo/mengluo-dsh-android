package ai.mengluo.dsh.android;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.content.pm.PackageManager;

/** Run explicitly against a private lower-version build after publishing a real release. */
@RunWith(AndroidJUnit4.class)
public class PublicUpdateTest {
    @Test public void publicFeedDownloadAndSignatureVerification() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long installed = context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode();
        assertEquals("Use the private code-1 updater probe, never downgrade a public release",1,installed);
        context.getSharedPreferences("updates",0).edit().remove("apk-check").commit();
        AndroidUpdates updates = AndroidUpdates.get(context); updates.checkApk(true);
        long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        while(updates.checkingApk && System.nanoTime()<deadline) Thread.sleep(100);
        assertNotNull(updates.apkStatus,updates.apk); assertEquals(2,updates.apk.code); assertEquals("0.0.1",updates.apk.version);
        updates.download(); deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        while(updates.downloading && System.nanoTime()<deadline) Thread.sleep(100);
        assertFalse(updates.apkStatus,updates.downloading); assertTrue(updates.apkStatus,updates.downloaded());
        assertEquals(100,updates.progress); assertTrue(updates.apkStatus,updates.apkStatus.contains("校验"));
        // This test deliberately does not grant unknown-source permission or accept the OS installer.
    }
}
