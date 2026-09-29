package ai.mengluo.dsh.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** Read-only verification of a staged next release, before making its public update feed visible. */
@RunWith(AndroidJUnit4.class)
public class ReleaseCandidateTest {
    @Test public void nextReleasePassesRealPackageManagerAndSignatureChecks() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        String apkPath = args.getString("candidateApk"), manifestPath = args.getString("candidateManifest");
        assumeNotNull(apkPath, manifestPath);
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var release = AndroidUpdates.ApkRelease.parse(new JSONObject(IO.text(new File(manifestPath))));
        long installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        assertTrue("A release must update the installed local test build as well", release.code > installed);
        File apk = new File(apkPath); AndroidUpdates.verify(context, apk, release);
        var tampered = new AndroidUpdates.ApkRelease(release.version, release.code, release.minSdk, release.url,
            "0".repeat(64), release.size, release.notes);
        assertThrows(IOException.class, () -> AndroidUpdates.verify(context, apk, tampered));
        var wrongCode = new AndroidUpdates.ApkRelease(release.version, (int) installed, release.minSdk, release.url,
            release.sha256, release.size, release.notes);
        assertThrows(IOException.class, () -> AndroidUpdates.verify(context, apk, wrongCode));
    }
}
