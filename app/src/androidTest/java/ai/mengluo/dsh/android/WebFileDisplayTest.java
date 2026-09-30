package ai.mengluo.dsh.android;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.webkit.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real WebView and Android sharing APIs; only cache fixtures, no real sessions or files. */
@RunWith(AndroidJUnit4.class)
public class WebFileDisplayTest {
    private static final String ORIGIN = "http://127.0.0.1:49431";

    @Test public void floatingMenuShowsZoomWithoutCoveringNavigationArea() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            Thread.sleep(450);
            scenario.onActivity(a -> a.findViewById(android.R.id.content).findViewWithTag("menu-ball").performClick());
            awaitText("页面缩放"); snapshot("zoom-menu-portrait"); back();
        }
    }

    @Test public void wholePageZoomChangesViewportAndPersistsWithoutChangingNativeControls() throws Exception {
        try (Fixture f = new Fixture()) {
            f.page("<style>body{margin:0}#fill{width:100vw;height:100vh}</style><div id='fill'>缩放测试</div>", ORIGIN);
            f.scenario.onActivity(a -> f.zoom.change(100));
            double initial = f.width();
            f.scenario.onActivity(a -> f.zoom.change(50)); f.awaitWidth(initial * 2);
            assertEquals(0, new JSONObject(f.object("({overflow:Math.abs(document.getElementById('fill').getBoundingClientRect().height-innerHeight)})")).getInt("overflow"));
            f.scenario.onActivity(a -> f.zoom.change(200)); f.awaitWidth(initial / 2);
            f.scenario.onActivity(a -> {
                assertEquals(200, new PageZoom(f.web, () -> ORIGIN, f.preferences).percent());
                LinearLayout controls = f.zoom.controls(new Ui(a));
                assertFalse(controls.findViewWithTag("page-zoom-plus").isEnabled());
                controls.findViewWithTag("page-zoom-reset").performClick(); assertEquals(100, f.zoom.percent());
            });
            f.awaitWidth(initial);
        }
    }
    @Test public void fileTreeAndCardsUseNativeConfirmationWhileDirectoriesAndLinksStayOfficial() throws Exception {
        try (Fixture f = new Fixture()) {
            String body = "<div data-files-root='/workspace'><li data-files-entry='file' data-files-path='图 #1.png'><button id='file' onclick='window.officialClicked=true'>图片</button></li>"
                + "<li data-files-entry='directory' data-files-path='/workspace/sub'><button id='folder' onclick='window.folderClicked=true'>文件夹</button></li></div>"
                + "<div data-presented-file><button id='card' title='/workspace/hello.html' onclick='window.officialClicked=true'>打开</button></div>"
                + "<a id='outside' href='https://example.invalid/' onclick='event.preventDefault();window.linkClicked=true'>外部链接</a>";
            f.page(body, ORIGIN);
            f.js("document.getElementById('file').click()"); assertFalse(visible("使用系统应用打开？"));
            f.js("window.officialClicked=false"); f.tap("file"); awaitText("使用系统应用打开？");
            assertTrue(visible("/workspace/图 #1.png")); assertEquals("false", f.js("Boolean(window.officialClicked)"));
            snapshot("system-file-confirmation"); back();
            f.tap("folder"); assertEquals("true", f.js("Boolean(window.folderClicked)"));
            f.tap("outside"); assertEquals("true", f.js("Boolean(window.linkClicked)"));
            f.tap("card"); awaitText("/workspace/hello.html"); back();
            assertFalse("No file copies before user consent", f.shares.exists());
        }
    }
    @Test public void serviceUnavailableGetsOneFallbackWithoutGuessingFilePath() throws Exception {
        try (Fixture f = new Fixture()) {
            f.page("<div data-textpreview-state='loading'>文件资源服务不可用</div>", ORIGIN);
            assertEquals("1", f.js("document.querySelectorAll('[data-mengluo-system-open]').length"));
            f.js("document.querySelector('[data-mengluo-system-open]').id='fallback';document.body.append(document.createElement('i'))");
            f.tap("fallback"); await(() -> f.browsed.get() == 1);
            assertEquals("1", f.js("document.querySelectorAll('[data-mengluo-system-open]').length"));
            assertFalse(visible("使用系统应用打开？"));
        }
    }
    @Test public void bridgeRejectsOtherPortsSubframesAndRuntimePaths() throws Exception {
        try (Fixture f = new Fixture()) {
            f.page("<iframe src='/child'></iframe>", ORIGIN);
            f.js("document.querySelector('iframe').contentWindow.MengLuoFiles.postMessage(JSON.stringify({action:'open',path:'/workspace/child.png'}))");
            Thread.sleep(200); assertFalse(visible("使用系统应用打开？"));
            f.js("MengLuoFiles.postMessage(JSON.stringify({action:'open',path:'/root/.dsh/credentials'}))");
            Thread.sleep(200); assertFalse(visible("使用系统应用打开？"));
            f.page("<button>other page</button>", "http://127.0.0.1:49432");
            assertEquals("\"undefined\"", f.js("typeof MengLuoFiles"));
            f.page("<button>valid page</button>", ORIGIN);
            f.js("MengLuoFiles.postMessage(JSON.stringify({action:'open',path:'/workspace/allowed.png'}))");
            awaitText("使用系统应用打开？"); back();
            f.scenario.onActivity(a -> { assertFalse(f.web.getSettings().getAllowFileAccess()); assertFalse(f.web.getSettings().getAllowContentAccess()); });
        }
    }
    @Test public void resourceLinksDecodeRealFilenamesButLeaveRelativeAndRemoteLinksAlone() throws Exception {
        try (Fixture f = new Fixture()) {
            f.page("<a id='absolute' href='dsh-resource://file/absolute/workspace/a%20%23%3F.png'>absolute</a>"
                + "<a id='session' href='dsh-resource://file/session/s1//workspace/file.txt'>session</a>", ORIGIN);
            f.tap("absolute"); awaitText("/workspace/a #?.png"); back();
            f.tap("session"); awaitText("/workspace/file.txt"); back();
        }
    }
    @Test public void readOnlySnapshotKeepsOriginalAndProviderCannotExposeProfile() throws Exception {
        try (Fixture f = new Fixture()) {
            File original = new File(f.workspace, "image.png"); byte[] bytes = {1, 2, 3, 4, 5}; Files.write(original.toPath(), bytes);
            File copy = f.system.snapshot("/workspace/image.png");
            try {
                Intent view = SystemFiles.viewIntent(f.activity, copy);
                assertEquals(Intent.ACTION_VIEW, view.getAction()); assertEquals("image/png", view.getType());
                assertEquals("content", view.getData().getScheme());
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, view.getFlags());
                try (InputStream input = f.activity.getContentResolver().openInputStream(view.getData())) { assertArrayEquals(bytes, IO.bytes(input)); }
                assertThrows(FileNotFoundException.class, () -> f.activity.getContentResolver().openOutputStream(view.getData(), "w"));
                Files.write(copy.toPath(), new byte[]{8}); assertArrayEquals(bytes, Files.readAllBytes(original.toPath()));
                File secret = new File(f.rootfs, "root/.dsh/secret"); assertTrue(secret.getParentFile().mkdirs()); Files.write(secret.toPath(), new byte[]{7});
                assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(f.activity, f.activity.getPackageName() + ".files", secret));
                assertThrows(IOException.class, () -> f.system.snapshot("/root/.dsh/secret"));
                Files.createSymbolicLink(new File(f.workspace, "link").toPath(), secret.toPath());
                assertThrows(IOException.class, () -> f.system.snapshot("/workspace/link"));
            } finally { Files.delete(copy.toPath()); Files.delete(copy.getParentFile().toPath()); }
        }
    }
    @Test public void nativeOpenLaunchesChooserWithOnlySelectedFile() throws Exception {
        try (Fixture f = new Fixture()) {
            IO.text(new File(f.workspace, "hello.txt"), "fixture");
            AtomicReference<Intent> launched = new AtomicReference<>();
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
                @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                    if (!Intent.ACTION_CHOOSER.equals(intent.getAction())) return null;
                    launched.set(intent); return new Instrumentation.ActivityResult(0, null);
                }
            };
            instrumentation.addMonitor(monitor);
            try {
                f.scenario.onActivity(a -> f.system.open("/workspace/hello.txt", false));
                await(() -> launched.get() != null);
                Intent selected = launched.get().getParcelableExtra(Intent.EXTRA_INTENT);
                assertEquals("text/plain", selected.getType());
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, selected.getFlags());
                assertEquals(selected.getData(), selected.getClipData().getItemAt(0).getUri());
                try (InputStream in = f.activity.getContentResolver().openInputStream(selected.getData())) { assertEquals("fixture", new String(IO.bytes(in), StandardCharsets.UTF_8)); }
            } finally { instrumentation.removeMonitor(monitor); }
        }
    }

    private static void back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private static void await(java.util.function.BooleanSupplier ready) throws Exception {
        long until = SystemClock.uptimeMillis() + 7000;
        while (!ready.getAsBoolean() && SystemClock.uptimeMillis() < until) Thread.sleep(60);
        assertTrue("Timed out waiting for UI", ready.getAsBoolean());
    }
    private static void awaitText(String text) throws Exception { await(() -> visible(text)); }
    private static boolean visible(String text) {
        AtomicBoolean found = new AtomicBoolean(); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) if (contains(root, text)) found.set(true);
        }); return found.get();
    }
    private static boolean contains(View v, String text) {
        if (v instanceof TextView tv && tv.getText().toString().contains(text)) return true;
        if (v instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (contains(group.getChildAt(i), text)) return true;
        return false;
    }
    private static void snapshot(String name) throws Exception {
        Thread.sleep(400); File target = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "verification"); target.mkdirs();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (OutputStream output = new FileOutputStream(new File(target, name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        finally { bitmap.recycle(); }
    }
    private static final class Fixture implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        final AtomicInteger browsed = new AtomicInteger();
        final String prefsName = "display-test-" + UUID.randomUUID();
        MainActivity activity; WebView web; FrameLayout frame; SharedPreferences preferences;
        PageZoom zoom; WebFiles bridge; SystemFiles system; File base, workspace, rootfs, shares;
        Fixture() throws Exception {
            base = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(), "display-fixture-").toFile();
            workspace = new File(base, "workspace"); rootfs = new File(base, "rootfs"); workspace.mkdirs(); rootfs.mkdirs();
            scenario.onActivity(a -> {
                activity = a; preferences = a.getSharedPreferences(prefsName, 0);
                frame = a.findViewById(android.R.id.content).findViewWithTag("shell-frame");
                web = new WebView(a); web.getSettings().setJavaScriptEnabled(true); web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
                zoom = new PageZoom(web, () -> ORIGIN, preferences);
                shares = new File(a.getCacheDir(), "open-files/test-" + UUID.randomUUID());
                system = new SystemFiles(a, new ProjectFiles(workspace, rootfs), shares);
                bridge = new WebFiles(web, () -> ORIGIN, system, browsed::incrementAndGet);
                try { bridge.install(ORIGIN); } catch (IOException e) { throw new AssertionError(e); }
                frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
            });
        }
        void page(String body, String origin) throws Exception {
            CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(a -> {
                web.setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        String html = "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><style>button,a{display:block;margin:16px;padding:12px;font-size:18px}</style><body>"
                            + (request.isForMainFrame() ? body : "child") + "</body>";
                        return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                    }
                    @Override public void onPageFinished(WebView view, String url) { bridge.finished(url); zoom.apply(); loaded.countDown(); }
                });
                web.loadUrl(origin + "/fixture?" + System.nanoTime());
            });
            assertTrue(loaded.await(10, TimeUnit.SECONDS)); Thread.sleep(250);
        }
        String js(String code) throws Exception {
            CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
            scenario.onActivity(a -> web.evaluateJavascript(code, v -> { value.set(v); done.countDown(); }));
            assertTrue(done.await(5, TimeUnit.SECONDS)); return value.get();
        }
        String object(String expression) throws Exception { return new JSONArray("[" + js("JSON.stringify(" + expression + ")") + "]").getString(0); }
        double width() throws Exception { return Double.parseDouble(js("innerWidth")); }
        void awaitWidth(double expected) throws Exception {
            double width = 0; long until = SystemClock.uptimeMillis() + 5000;
            do { Thread.sleep(75); width = width(); } while (Math.abs(width - expected) > 3 && SystemClock.uptimeMillis() < until);
            assertEquals(expected, width, 3);
        }
        void tap(String id) throws Exception {
            JSONObject rect = new JSONObject(object("(()=>{const r=document.getElementById(" + JSONObject.quote(id) + ").getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2,scale:visualViewport.scale}})()"));
            float[] xy = new float[2]; scenario.onActivity(a -> {
                int[] offset = new int[2]; web.getLocationOnScreen(offset); float density = a.getResources().getDisplayMetrics().density;
                xy[0] = offset[0] + (float) rect.optDouble("x") * density * (float) rect.optDouble("scale", 1);
                xy[1] = offset[1] + (float) rect.optDouble("y") * density * (float) rect.optDouble("scale", 1);
            });
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, xy[0], xy[1], 0);
                InstrumentationRegistry.getInstrumentation().sendPointerSync(event); event.recycle();
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(150);
        }
        @Override public void close() throws Exception {
            scenario.onActivity(a -> { bridge.close(); system.close(); frame.removeView(web); web.destroy(); a.deleteSharedPreferences(prefsName); }); scenario.close();
            try (var paths = Files.walk(base.toPath())) { for (var file : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file); }
            if (shares.exists()) try (var paths = Files.walk(shares.toPath())) { for (var file : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file); }
        }
    }
}
