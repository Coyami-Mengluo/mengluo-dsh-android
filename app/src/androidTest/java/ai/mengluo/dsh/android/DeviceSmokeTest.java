package ai.mengluo.dsh.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.content.Intent;
import android.webkit.WebView;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Executes through the app UID, not adb-shell or a host-side server. No model/API credentials. */
@RunWith(AndroidJUnit4.class)
public class DeviceSmokeTest {
    @Test public void testLocalRuntime() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Engine engine = Engine.get(activity);
        assertTrue("App must not run as root", android.os.Process.myUid() >= 10000);
        engine.downloadSource(DownloadSource.MIRROR);
        assertEquals("mirror", instrumentation.getTargetContext().getSharedPreferences("downloads", 0).getString("source", null));
        RegistryClient.check(DownloadSource.MIRROR);
        if (!engine.installed()) {
            engine.install();
            await(() -> !engine.busy, 1900);
            assertTrue("Install failed: " + engine.status + "\n" + engine.logs(), engine.installed());
        }
        CountDownLatch completed = new CountDownLatch(1);
        engine.command("/opt/node/bin/node -e \"const fs=require('fs');fs.mkdirSync('/workspace/smoke',{recursive:true});fs.writeFileSync('/workspace/smoke/hello.js','console.log(6*7)');if(require('child_process').execFileSync('/opt/node/bin/node',['/workspace/smoke/hello.js']).toString().trim()!=='42')process.exit(1);console.log('LOCAL_CODE_EXECUTION_OK');\"", result -> completed.countDown());
        assertTrue("Local code execution timed out", completed.await(90, TimeUnit.SECONDS));
        assertTrue(engine.logs(), engine.logs().contains("LOCAL_CODE_EXECUTION_OK"));
        assertTrue(new File(engine.workspace, "smoke/hello.js").isFile());
        CountDownLatch toolsChecked = new CountDownLatch(1);
        engine.command("set -e; git --version; python3 -c 'print(6*7)'; pnpm --version; npm config get registry; pnpm config get registry; test \"$(npm config get registry)\" = 'https://registry.npmmirror.com/'; test \"$(pnpm config get registry)\" = 'https://registry.npmmirror.com/'; echo MIRROR_AND_TOOLS_OK", result -> toolsChecked.countDown());
        assertTrue("Tool checks timed out", toolsChecked.await(90, TimeUnit.SECONDS));
        assertTrue(engine.logs(), engine.logs().contains("MIRROR_AND_TOOLS_OK"));
        engine.start();
        await(() -> engine.readyUrl != null || !engine.busy, 150);
        assertNotNull("Harness failed to start: " + engine.logs(), engine.readyUrl);
        assertTrue(engine.readyUrl.startsWith("http://127.0.0.1:"));
        try {
            await(() -> "true".equals(evaluate(activity,
                "document.title.includes('Harness') && typeof Promise.withResolvers==='function' && typeof AbortSignal.any==='function' && document.body.innerText.includes('新会话') && document.body.innerText.includes('设置')")), 45);
        } catch (AssertionError failure) {
            fail("WebView startup: " + evaluate(activity, "JSON.stringify({title:document.title,ready:document.readyState,promise:typeof Promise.withResolvers,abort:typeof AbortSignal.any,rendered:document.body.innerText.includes('新会话')})") + "\n" + engine.logs());
        }
        Thread.sleep(4000);
        assertEquals("Frontend is still reconnecting", "false", evaluate(activity, "document.body.innerText.includes('重新连接中')"));
        assertFalse("Frontend JavaScript error: " + engine.logs(), engine.logs().contains("[web] Uncaught"));
        engine.stop();
        assertTrue("Stopping must preserve the installed version", engine.installed());
        assertTrue("Stopping must preserve code", new File(engine.workspace, "smoke/hello.js").isFile());
    }
    private static String evaluate(MainActivity activity, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView web = activity.findViewById(android.R.id.content).findViewWithTag("harness-web");
            web.evaluateJavascript(script, response -> { value.set(response); done.countDown(); });
        });
        assertTrue("WebView did not answer", done.await(5, TimeUnit.SECONDS));
        return value.get();
    }
    private interface Condition { boolean check() throws Exception; }
    private static void await(Condition condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.check()) {
            if (System.nanoTime() > deadline) fail("Timed out after " + seconds + " seconds");
            Thread.sleep(250);
        }
    }
}
