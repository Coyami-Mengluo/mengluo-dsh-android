package ai.mengluo.dsh.android;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;
import android.content.pm.PackageManager;

/** Opt-in: run against an existing lower build, without downgrading or clearing user data. */
@RunWith(AndroidJUnit4.class)
public class PublicUpdateTest {
    @Test public void publicFeedDownloadAndSignatureVerification() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var args = InstrumentationRegistry.getArguments();
        String expectedVersion = args.getString("expectedVersion"), expectedCode = args.getString("expectedCode");
        assumeNotNull(expectedVersion, expectedCode);
        long installed = context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode();
        assertTrue("Run on an existing older installation; never downgrade it for a test", installed < Integer.parseInt(expectedCode));
        context.getSharedPreferences("updates",0).edit().remove("apk-check").commit();
        AndroidUpdates updates = AndroidUpdates.get(context); updates.checkApk(true);
        long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        while(updates.checkingApk && System.nanoTime()<deadline) Thread.sleep(100);
        assertNotNull(updates.apkStatus,updates.apk); assertEquals(Integer.parseInt(expectedCode),updates.apk.code); assertEquals(expectedVersion,updates.apk.version);
        updates.download(); deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        while(updates.downloading && System.nanoTime()<deadline) Thread.sleep(100);
        assertFalse(updates.apkStatus,updates.downloading); assertTrue(updates.apkStatus,updates.downloaded());
        assertEquals(100,updates.progress); assertTrue(updates.apkStatus,updates.apkStatus.contains("校验"));
        // This test deliberately does not grant unknown-source permission or accept the OS installer.
    }
}
