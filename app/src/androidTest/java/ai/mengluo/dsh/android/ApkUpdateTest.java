package ai.mengluo.dsh.android;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.security.MessageDigest;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ApkUpdateTest {
    private String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[65536]; int n; while((n=in.read(b))!=-1) digest.update(b,0,n); }
        StringBuilder value = new StringBuilder(); for (byte b : digest.digest()) value.append(String.format("%02x", b)); return value.toString();
    }
    @Test public void signedApkIdentityHashAndVersionAreChecked() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = new File(context.getCacheDir(), "apk-update-fixture.apk"); assertTrue("Stage the private 0.0.2/code 3 release fixture first", file.isFile());
        String url = "https://github.com/" + UpdatePolicy.REPOSITORY + "/releases/download/v0.0.2/fixture.apk";
        AndroidUpdates.ApkRelease release = new AndroidUpdates.ApkRelease("0.0.2", 3, 30, url, sha(file), file.length(), "");
        AndroidUpdates.verify(context, file, release);
        try { AndroidUpdates.verify(context, file, new AndroidUpdates.ApkRelease("0.0.2",3,30,url,"0".repeat(64),file.length(),"")); fail(); } catch (IOException expected) { }
        try { AndroidUpdates.verify(context, file, new AndroidUpdates.ApkRelease("0.0.2",2,30,url,sha(file),file.length(),"")); fail(); } catch (IOException expected) { }
        try { AndroidUpdates.verify(context, file, new AndroidUpdates.ApkRelease("0.0.3",3,30,url,sha(file),file.length(),"")); fail(); } catch (IOException expected) { }
    }
    @Test public void invalidFeedsCannotSelectAnotherAppOrUrl() throws Exception {
        JSONObject json = new JSONObject().put("schema",1).put("applicationId", BuildConfig.APPLICATION_ID).put("version","0.0.2")
            .put("versionCode",3).put("minSdk",30).put("files",new JSONObject().put(BuildConfig.RUNTIME_ABI,
                new JSONObject().put("url","https://github.com/"+UpdatePolicy.REPOSITORY+"/releases/download/v0.0.2/fixture.apk")
                    .put("sha256","0".repeat(64)).put("size",1000000)));
        assertEquals(3,AndroidUpdates.ApkRelease.parse(json).code);
        json.put("applicationId","attacker.app");
        try { AndroidUpdates.ApkRelease.parse(json); fail(); } catch(IOException expected) { }
        json.put("applicationId",BuildConfig.APPLICATION_ID).getJSONObject("files").getJSONObject(BuildConfig.RUNTIME_ABI).put("url","https://evil.example/payload.apk");
        try { AndroidUpdates.ApkRelease.parse(json); fail(); } catch(IOException expected) { }
    }
}
