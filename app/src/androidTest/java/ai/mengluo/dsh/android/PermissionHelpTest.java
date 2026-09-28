package ai.mengluo.dsh.android;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Local rendered fixtures only. No model calls or writes to Harness/user preferences. */
@RunWith(AndroidJUnit4.class)
public class PermissionHelpTest {
    private static final String ORIGIN = "http://127.0.0.1:49391";

    @Test public void installationHelpCanBeDismissedAndRemainsDismissed() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                String name = "permission-help-test-" + java.util.UUID.randomUUID();
                SharedPreferences prefs = activity.getSharedPreferences(name, 0);
                PermissionHelp help = new PermissionHelp(activity, new Ui(activity), prefs);
                View card = help.installationCard(); help.refresh(false); assertEquals(View.VISIBLE, card.getVisibility());
                assertTrue(hasText(card, "API 密钥")); clickText(card, "知道了");
                assertTrue(prefs.getBoolean(PermissionHelp.INTRO_SEEN, false));
                PermissionHelp reopened = new PermissionHelp(activity, new Ui(activity), prefs);
                View again = reopened.installationCard(); reopened.refresh(false);
                assertEquals(View.GONE, again.getVisibility());
                help.close(); reopened.close(); activity.deleteSharedPreferences(name);
            });
        }
    }

    @Test public void renderedSandboxErrorsTriggerOnceAndDoNotRepeatAfterRecreation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.page("<div data-tool='bash' data-state='error'>sandbox mode workspace-write: no sandbox backend is usable; refusing to run unconfined</div>", ORIGIN);
            assertTrue(fixture.signal.await(5, TimeUnit.SECONDS));
            fixture.scenario.onActivity(activity -> {
                assertNotNull(fixture.signalInfo.get(), fixture.frame.findViewWithTag("permission-runtime-hint"));
                assertTrue(fixture.preferences.getBoolean(PermissionHelp.RUNTIME_SEEN, false));
            });
            Thread.sleep(400);
            snapshot("permission-bubble");
            fixture.scenario.onActivity(activity -> {
                View bubble = fixture.frame.findViewWithTag("permission-runtime-hint");
                assertEquals(View.VISIBLE, bubble.getVisibility());
                assertTrue(bubble.getX() >= fixture.frame.getPaddingLeft());
                assertTrue(bubble.getY() >= fixture.frame.getPaddingTop());
                bubble.findViewWithTag("permission-hint-close").performClick();
                fixture.help.close();
                fixture.help = new PermissionHelp(activity, new Ui(activity), fixture.preferences);
                fixture.help.onConsoleMessage(new ConsoleMessage(PermissionHelp.SIGNAL, ORIGIN + PermissionHelp.SCRIPT_PATH, 1, ConsoleMessage.MessageLevel.LOG), fixture.web, ORIGIN, fixture.frame);
                assertNull(fixture.frame.findViewWithTag("permission-runtime-hint"));
            });
            fixture.js("document.body.insertAdjacentHTML('beforeend', '<pre data-error=\"true\">no sandbox backend is usable</pre>')");
            Thread.sleep(850);
            assertEquals("Observer disconnects after the first diagnosis", 1, fixture.signals.get());
        }
    }

    @Test public void assistantDiagnosticAndStreamedErrorsAreRecognized() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.page("<div data-chat-flow-kind='assistant'><p id='answer'>正在运行</p></div>", ORIGIN);
            fixture.js("document.getElementById('answer').textContent='本机 bash 沙箱不可用（无 bubblewrap/Landlock 后端）'");
            assertTrue(fixture.signal.await(5, TimeUnit.SECONDS));
        }
        try (Fixture fixture = new Fixture()) {
            fixture.page("<div id='result' data-tool='bash' data-state='running'>no sandbox backend is usable</div>", ORIGIN);
            fixture.js("document.getElementById('result').setAttribute('data-state','error')");
            assertTrue(fixture.signal.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void unrelatedErrorsUserMessagesQuotesAndHiddenTextDoNotTrigger() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.page("<div data-tool='bash' data-state='error'>SyntaxError: Unexpected token; npm timeout; permission denied; browser missing</div>"
                + "<div data-chat-flow-kind='user'>本机 bash 沙箱不可用</div>"
                + "<div data-chat-flow-kind='assistant'>如何安装 bubblewrap / Landlock<pre>no sandbox backend is usable</pre><blockquote>本机 bash 沙箱不可用</blockquote></div>"
                + "<div data-error='true' style='display:none'>no sandbox backend is usable</div>"
                + "<div data-error='true' style='visibility:hidden'>no sandbox backend is usable</div>"
                + "<textarea>no sandbox backend is usable</textarea><div contenteditable='true' data-error='true'>no sandbox backend is usable</div>", ORIGIN);
            assertFalse(fixture.signal.await(1200, TimeUnit.MILLISECONDS));
            assertEquals(0, fixture.signals.get());
        }
    }

    @Test public void otherOriginsAndForgedConsoleSourcesDoNotShowNativeHint() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.page("<div data-error='true'>no sandbox backend is usable</div>", "http://127.0.0.1:49392");
            assertFalse(fixture.signal.await(1000, TimeUnit.MILLISECONDS));
            fixture.scenario.onActivity(activity -> {
                fixture.help.onConsoleMessage(new ConsoleMessage(PermissionHelp.SIGNAL, "https://example.invalid/" + PermissionHelp.SCRIPT_PATH, 1, ConsoleMessage.MessageLevel.LOG), fixture.web, ORIGIN, fixture.frame);
                assertFalse(fixture.preferences.getBoolean(PermissionHelp.RUNTIME_SEEN, false));
                assertNull(fixture.frame.findViewWithTag("permission-runtime-hint"));
            });
        }
    }

    private static boolean hasText(View view, String text) {
        if (view instanceof TextView label && label.getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), text)) return true;
        return false;
    }
    private static void snapshot(String name) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.waitForIdleSync();
        android.graphics.Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        java.io.File directory = new java.io.File(instrumentation.getTargetContext().getCacheDir(), "ui-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
    }
    private static boolean clickText(View view, String text) {
        if (view instanceof TextView label && label.getText().toString().equals(text)) { view.performClick(); return true; }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (clickText(group.getChildAt(i), text)) return true;
        return false;
    }

    private static final class Fixture implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        final CountDownLatch signal = new CountDownLatch(1);
        final AtomicInteger signals = new AtomicInteger();
        final AtomicReference<String> signalInfo = new AtomicReference<>();
        final String preferenceName = "permission-help-test-" + java.util.UUID.randomUUID();
        SharedPreferences preferences;
        PermissionHelp help;
        FrameLayout frame;
        WebView web;
        String script;
        Fixture() throws Exception {
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets().open("permission-hint.js")) {
                script = new String(IO.bytes(input), StandardCharsets.UTF_8);
            }
            scenario.onActivity(activity -> {
                preferences = activity.getSharedPreferences(preferenceName, 0);
                help = new PermissionHelp(activity, new Ui(activity), preferences);
                frame = activity.findViewById(android.R.id.content).findViewWithTag("shell-frame");
                web = new WebView(activity); web.getSettings().setJavaScriptEnabled(true);
                web.setWebChromeClient(new WebChromeClient() {
                    @Override public boolean onConsoleMessage(ConsoleMessage message) {
                        if (PermissionHelp.SIGNAL.equals(message.message())) {
                            signalInfo.set("source=" + message.sourceId() + " level=" + message.messageLevel() + " page=" + web.getUrl() + " visibility=" + web.getVisibility());
                            signals.incrementAndGet(); help.onConsoleMessage(message, web, ORIGIN, frame); signal.countDown();
                        }
                        return true;
                    }
                });
                frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
                frame.findViewWithTag("menu-ball").bringToFront();
            });
        }
        void page(String body, String base) throws Exception {
            CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                web.setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        String html = request.isForMainFrame() ? "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><body>" + body + "</body>" : "";
                        return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                    }
                    @Override public void onPageFinished(WebView view, String url) {
                        // Load exactly the packaged observer; console source verifies its native signal path.
                        view.evaluateJavascript(script + "(" + JSONObject.quote(ORIGIN) + ");\n//# sourceURL=" + ORIGIN + PermissionHelp.SCRIPT_PATH,
                            value -> loaded.countDown());
                    }
                });
                web.loadUrl(base + "/fixture");
            });
            assertTrue("Fixture page loaded", loaded.await(8, TimeUnit.SECONDS));
        }
        void js(String code) throws Exception {
            CountDownLatch done = new CountDownLatch(1);
            scenario.onActivity(activity -> web.evaluateJavascript(code, value -> done.countDown()));
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }
        @Override public void close() {
            scenario.onActivity(activity -> { help.close(); frame.removeView(web); web.destroy(); activity.deleteSharedPreferences(preferenceName); });
            scenario.close();
        }
    }
}
