package ai.mengluo.dsh.android;

import android.app.Instrumentation;
import android.content.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PhoneProbeTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context context;
    private PhoneControl control;
    private String endpoint, token;
    private final String owner = "a".repeat(64);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @Before public void setup() throws Exception {
        context = instrumentation.getTargetContext(); Assume.assumeTrue(context.getPackageName().endsWith(".phoneprobe")); assertEquals("Refuse production package", "ai.mengluo.dsh.android.phoneprobe", context.getPackageName());
        control = PhoneControl.get(context);
        List<String> environment = control.prepare(new File(context.getCacheDir(), "phone-probe-rootfs"));
        for (String value : environment) { if (value.startsWith("ML_PHONE_URL=")) endpoint = value.substring(13); if (value.startsWith("ML_PHONE_TOKEN=")) token = value.substring(15); }
        assertNotNull(endpoint); assertNotNull(token); instrumentation.waitForIdleSync();
    }
    @After public void cleanup() { control.runtimeStopped(); worker.shutdownNow(); instrumentation.waitForIdleSync(); }
    JSONObject rpc(JSONObject input) throws Exception {
        input.put("owner", owner).put("requestId", UUID.randomUUID().toString());
        HttpURLConnection connection = (HttpURLConnection)new URL(endpoint).openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(2000); connection.setReadTimeout(100000);
        connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Authorization", "Bearer " + token);
        try { try (OutputStream output = connection.getOutputStream()) { output.write(input.toString().getBytes(StandardCharsets.UTF_8)); } return new JSONObject(new String(IO.bytes(connection.getInputStream()), StandardCharsets.UTF_8)); }
        finally { connection.disconnect(); }
    }
    @Test public void withoutPermissionsNoActionOrGrantIsPossible() throws Exception {
        Assume.assumeFalse(Settings.canDrawOverlays(context));
        assertEquals("permission_required", rpc(new JSONObject().put("op", "begin").put("purpose", "Isolated permission denial test")).getString("status"));
        assertEquals("permission_required", rpc(new JSONObject().put("op", "action").put("action", "launch").put("lease", "fake")).getString("status"));
        assertEquals("permission_required", rpc(new JSONObject().put("op", "begin").put("purpose", "must not retry")).getString("status"));
        assertFalse(control.state.live());
    }
    @Test(timeout = 20000) public void activeOverlayAutoHidesAndKeepsStopReachable() throws Exception {
        Assume.assumeTrue("Requires explicit isolated overlay fixture opt-in",
            "true".equals(InstrumentationRegistry.getArguments().getString("allowOverlayFixture")));
        assertEquals("Refuse production package", "ai.mengluo.dsh.android.phoneprobe", context.getPackageName());
        assertTrue("The test does not grant overlay permission", Settings.canDrawOverlays(context));
        // A separate overlay cannot be rewritten by PhoneControl's permission watchdog.
        PhoneOverlay[] fixture = new PhoneOverlay[1];
        int[] expandedWidth = new int[1];
        long[] shownAt = new long[1];
        android.graphics.Bitmap[] compactArtwork = new android.graphics.Bitmap[1];
        Instrumentation.ActivityMonitor mainActivity = instrumentation.addMonitor(MainActivity.class.getName(), null, true);
        try {
            instrumentation.runOnMainSync(() -> {
                fixture[0] = new PhoneOverlay(context, control);
                assertTrue(fixture[0].show(true, ""));
                shownAt[0] = android.os.SystemClock.uptimeMillis();
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertTrue("Initial stop pill must attach", fixture[0].attached());
                assertFalse((boolean)overlayFixtureValue(fixture[0], "collapsed"));
                View view = (View)overlayFixtureValue(fixture[0], "view");
                expandedWidth[0] = view.getWidth();
                assertTrue(expandedWidth[0] > 0);
                assertNotNull(findText(view, "停止"));
            });
            waitUntilOverlayUptime(shownAt[0] + 1200);
            instrumentation.runOnMainSync(() -> {
                assertFalse("Must remain expanded before the two-second deadline", (boolean)overlayFixtureValue(fixture[0], "collapsed"));
                View original = (View)overlayFixtureValue(fixture[0], "view");
                assertTrue(fixture[0].show(true, ""));
                assertSame("Ordinary state refresh must not rebuild the pill", original, overlayFixtureValue(fixture[0], "view"));
            });
            waitUntilOverlayUptime(shownAt[0] + 2400);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertTrue("Refresh must not postpone the original collapse deadline", (boolean)overlayFixtureValue(fixture[0], "collapsed"));
                assertTrue("Stop affordance must stay attached at the edge", fixture[0].attached());
                View view = (View)overlayFixtureValue(fixture[0], "view");
                assertTrue("Collapsed handle must occupy less width", view.getWidth() > 0 && view.getWidth() < expandedWidth[0]);
                compactArtwork[0] = assertCompactIconAndDrawFixture(fixture[0]);
                View stop = findText(view, "停止"); assertNotNull(stop); assertEquals(View.GONE, stop.getVisibility());
                assertTrue(((ImageButton)overlayFixtureValue(fixture[0], "icon")).performClick());
                assertFalse("Tapping the edge must restore Stop", (boolean)overlayFixtureValue(fixture[0], "collapsed"));
                assertEquals(View.VISIBLE, stop.getVisibility());
                shownAt[0] = android.os.SystemClock.uptimeMillis();
            });
            instrumentation.waitForIdleSync();
            File external = context.getExternalFilesDir(null); assertNotNull(external);
            try (OutputStream output = new FileOutputStream(new File(external, "phone-overlay-compact.png"))) {
                assertTrue(compactArtwork[0].compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
            } finally { compactArtwork[0].recycle(); compactArtwork[0] = null; }
            assertEquals("Opening Stop must not bring MainActivity over the task", 0, mainActivity.getHits());
            waitUntilOverlayUptime(shownAt[0] + 2400);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertTrue("Manually expanded pill must hide again", (boolean)overlayFixtureValue(fixture[0], "collapsed"));
                assertTrue(fixture[0].show(true, "测试确认"));
                assertFalse((boolean)overlayFixtureValue(fixture[0], "collapsed"));
                shownAt[0] = android.os.SystemClock.uptimeMillis();
            });
            waitUntilOverlayUptime(shownAt[0] + 2400);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertFalse("Confirmation must remain visible until answered", (boolean)overlayFixtureValue(fixture[0], "collapsed"));
                assertNotNull(findText((View)overlayFixtureValue(fixture[0], "view"), "测试确认"));
                assertTrue(fixture[0].show(true, ""));
                assertTrue("Dismissing confirmation must immediately restore the edge handle", (boolean)overlayFixtureValue(fixture[0], "collapsed"));
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertTrue(fixture[0].attached());
                assertTrue(((View)overlayFixtureValue(fixture[0], "view")).getWidth() < expandedWidth[0]);
                assertTrue(((ImageButton)overlayFixtureValue(fixture[0], "icon")).performClick());
                assertFalse((boolean)overlayFixtureValue(fixture[0], "collapsed"));
                // State activation and clicking Stop are one main-thread block, so the
                // watchdog cannot require or enable AccessibilityService between them.
                String fixtureOwner = UUID.randomUUID().toString().replace("-", "").repeat(2);
                assertEquals("preparing", control.state.begin(fixtureOwner, android.os.SystemClock.elapsedRealtime()));
                String lease = control.state.lease();
                assertTrue(control.state.approve(fixtureOwner, lease));
                View stop = findText((View)overlayFixtureValue(fixture[0], "view"), "停止");
                assertNotNull(stop); assertEquals(View.VISIBLE, stop.getVisibility());
                assertTrue(stop.performClick());
                assertEquals("user_cancelled", control.state.phase(fixtureOwner));
                assertFalse(control.state.allowed(fixtureOwner, lease));
                assertFalse(control.state.live());
            });
            assertEquals("Neither edge expansion may launch MainActivity", 0, mainActivity.getHits());
        } finally {
            if (compactArtwork[0] != null) compactArtwork[0].recycle();
            instrumentation.runOnMainSync(() -> { if (fixture[0] != null) fixture[0].hide(); });
            instrumentation.removeMonitor(mainActivity);
        }
    }
    private android.graphics.Bitmap assertCompactIconAndDrawFixture(PhoneOverlay fixture) {
        ViewGroup view = (ViewGroup)overlayFixtureValue(fixture, "view");
        ImageButton icon = (ImageButton)overlayFixtureValue(fixture, "icon");
        WindowManager.LayoutParams position = (WindowManager.LayoutParams)overlayFixtureValue(fixture, "position");
        PhoneOverlayGeometry.IconBox expected = PhoneOverlayGeometry.icon(true, context.getResources().getDisplayMetrics().density);
        assertEquals(expected.width(), position.width);
        assertEquals(expected.width(), view.getWidth()); assertEquals(expected.height(), view.getHeight());
        assertEquals(expected.width(), icon.getLayoutParams().width); assertEquals(expected.height(), icon.getLayoutParams().height);
        assertEquals(expected.width(), icon.getWidth()); assertEquals(expected.height(), icon.getHeight());
        assertEquals(expected.horizontalPadding(), icon.getPaddingLeft()); assertEquals(expected.horizontalPadding(), icon.getPaddingRight());
        assertEquals(expected.verticalPadding(), icon.getPaddingTop()); assertEquals(expected.verticalPadding(), icon.getPaddingBottom());
        android.graphics.Rect iconBounds = new android.graphics.Rect(0, 0, icon.getWidth(), icon.getHeight());
        view.offsetDescendantRectToMyCoords(icon, iconBounds);
        assertTrue("The complete icon must stay inside the actual window", iconBounds.left >= 0 && iconBounds.top >= 0
            && iconBounds.right <= view.getWidth() && iconBounds.bottom <= view.getHeight());
        assertEquals(ImageView.ScaleType.FIT_CENTER, icon.getScaleType());
        assertNotNull(icon.getDrawable());
        android.graphics.RectF imageBounds = new android.graphics.RectF(icon.getDrawable().getBounds());
        assertTrue(imageBounds.width() > 0 && imageBounds.height() > 0);
        icon.getImageMatrix().mapRect(imageBounds); imageBounds.offset(icon.getPaddingLeft(), icon.getPaddingTop());
        assertTrue("FIT_CENTER must retain the full artwork inside its content box", imageBounds.left >= icon.getPaddingLeft() - .5f
            && imageBounds.top >= icon.getPaddingTop() - .5f && imageBounds.right <= icon.getWidth() - icon.getPaddingRight() + .5f
            && imageBounds.bottom <= icon.getHeight() - icon.getPaddingBottom() + .5f);
        // This draws only the self-created fixture view, never the display or another app.
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        return bitmap;
    }
    @Test(timeout = 20000) public void activeOverlayWaitsForCompactLayoutAndRestoresOrdinaryPosition() throws Exception {
        Assume.assumeTrue("Requires explicit isolated overlay fixture opt-in",
            "true".equals(InstrumentationRegistry.getArguments().getString("allowOverlayFixture")));
        assertEquals("Refuse production package", "ai.mengluo.dsh.android.phoneprobe", context.getPackageName());
        assertTrue("The test does not grant overlay permission", Settings.canDrawOverlays(context));
        PhoneOverlay[] fixture = new PhoneOverlay[1];
        CompletableFuture<OverlayFixtureLayout> initiallyCompact = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger cancelledCallbacks = new java.util.concurrent.atomic.AtomicInteger();
        try {
            instrumentation.runOnMainSync(() -> {
                fixture[0] = new PhoneOverlay(context, control);
                assertTrue(fixture[0].show(true, ""));
                fixture[0].whenCompact(() -> initiallyCompact.complete(overlayFixtureLayout(fixture[0])));
            });
            Thread.sleep(500);
            assertFalse("Compact readiness must not run while the pill is expanded", initiallyCompact.isDone());
            assertCompactFixtureLayout(initiallyCompact.get(4, TimeUnit.SECONDS));

            CompletableFuture<OverlayFixtureLayout> replacementCompact = new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> {
                assertTrue(((ImageButton)overlayFixtureValue(fixture[0], "icon")).performClick());
                assertFalse((boolean)overlayFixtureValue(fixture[0], "collapsed"));
                fixture[0].whenCompact(cancelledCallbacks::incrementAndGet);
                android.os.Handler handler = (android.os.Handler)overlayFixtureValue(fixture[0], "main");
                Runnable collapse = (Runnable)overlayFixtureValue(fixture[0], "collapse");
                assertTrue("Expansion must schedule auto-hide", handler.hasCallbacks(collapse));
                fixture[0].hide();
                assertFalse("Hide must cancel the scheduled auto-hide", handler.hasCallbacks(collapse));
                assertFalse(fixture[0].attached());
                assertNull(overlayFixtureValue(fixture[0], "view"));
                assertTrue(fixture[0].show(true, ""));
                assertFalse("A replacement must not inherit hidden state", (boolean)overlayFixtureValue(fixture[0], "collapsed"));
                fixture[0].whenCompact(() -> replacementCompact.complete(overlayFixtureLayout(fixture[0])));
            });
            assertCompactFixtureLayout(replacementCompact.get(4, TimeUnit.SECONDS));
            assertEquals("Readiness callbacks from a hidden view must never run on its replacement", 0, cancelledCallbacks.get());

            CompletableFuture<OverlayFixtureLayout> atBottom = new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> {
                android.graphics.Rect safe = (android.graphics.Rect)overlayFixtureInvoke(fixture[0], "safeBounds");
                Ui ui = (Ui)overlayFixtureValue(fixture[0], "ui");
                View view = (View)overlayFixtureValue(fixture[0], "view");
                WindowManager.LayoutParams position = (WindowManager.LayoutParams)overlayFixtureValue(fixture[0], "position");
                position.y = safe.bottom - view.getHeight() - ui.dp(8);
                overlayFixtureInvoke(fixture[0], "update");
                fixture[0].whenCompact(() -> atBottom.complete(overlayFixtureLayout(fixture[0])));
            });
            OverlayFixtureLayout ordinary = atBottom.get(3, TimeUnit.SECONDS);
            assertCompactFixtureLayout(ordinary);

            CompletableFuture<OverlayFixtureLayout> confirming = new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> {
                assertTrue(fixture[0].show(true, "测试确认：底部位置恢复"));
                fixture[0].whenAttached(() -> confirming.complete(overlayFixtureLayout(fixture[0])));
            });
            OverlayFixtureLayout confirmation = confirming.get(3, TimeUnit.SECONDS);
            assertTrue(confirmation.attached()); assertFalse(confirmation.collapsed());
            assertEquals(confirmation.desiredWidth(), confirmation.width()); assertFalse(confirmation.layoutRequested());
            assertTrue("A taller confirmation must be clamped upward near the bottom", confirmation.y() < ordinary.y());
            assertTrue("The actual window must also move upward", confirmation.screenY() < ordinary.screenY());

            CompletableFuture<OverlayFixtureLayout> restored = new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> {
                assertTrue(fixture[0].show(true, ""));
                fixture[0].whenCompact(() -> restored.complete(overlayFixtureLayout(fixture[0])));
                assertFalse("Compact readiness must wait for the newly attached window's layout", restored.isDone());
            });
            OverlayFixtureLayout restoredLayout = restored.get(3, TimeUnit.SECONDS);
            assertCompactFixtureLayout(restoredLayout);
            assertEquals("Confirmation clamping must not overwrite the ordinary edge position", ordinary.y(), restoredLayout.y());
            assertEquals("The actual compact window must return to its original height", ordinary.screenY(), restoredLayout.screenY());
            assertEquals(0, cancelledCallbacks.get());
        } finally {
            instrumentation.runOnMainSync(() -> { if (fixture[0] != null) fixture[0].hide(); });
        }
    }
    private record OverlayFixtureLayout(boolean attached, boolean collapsed, boolean layoutRequested,
                                        int width, int desiredWidth, int y, int screenY) { }
    private static OverlayFixtureLayout overlayFixtureLayout(PhoneOverlay fixture) {
        View view = (View)overlayFixtureValue(fixture, "view");
        WindowManager.LayoutParams position = (WindowManager.LayoutParams)overlayFixtureValue(fixture, "position");
        int[] location = new int[2]; view.getLocationOnScreen(location);
        return new OverlayFixtureLayout(fixture.attached(), (boolean)overlayFixtureValue(fixture, "collapsed"),
            view.isLayoutRequested(), view.getWidth(), position.width, position.y, location[1]);
    }
    private static void assertCompactFixtureLayout(OverlayFixtureLayout value) {
        assertTrue("Compact callback requires an attached view", value.attached());
        assertTrue("Compact callback requires collapsed state", value.collapsed());
        assertFalse("Compact callback must wait for layout", value.layoutRequested());
        assertTrue(value.width() > 0);
        assertEquals("Compact callback must observe the actual narrow width", value.desiredWidth(), value.width());
    }
    private static Object overlayFixtureInvoke(PhoneOverlay fixture, String name) {
        try { java.lang.reflect.Method method = PhoneOverlay.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(fixture); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static Object overlayFixtureValue(PhoneOverlay fixture, String name) {
        try { java.lang.reflect.Field field = PhoneOverlay.class.getDeclaredField(name); field.setAccessible(true); return field.get(fixture); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void waitUntilOverlayUptime(long deadline) throws InterruptedException {
        long remaining = deadline - android.os.SystemClock.uptimeMillis();
        if (remaining > 0) Thread.sleep(remaining);
    }
    @Test public void ordinaryPrivatePathWritesOnlyUseProbeCache() throws Exception {
        File directory = new File(context.getCacheDir(), "filename-probe-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        JSONObject report = new JSONObject().put("package", context.getPackageName()).put("uid", android.os.Process.myUid());
        JSONArray cases = new JSONArray();
        for (String name : new String[] { "normal", "su", "su-l", "sudo", "SU", "test-su", "su.txt",
                "etc/pam.d/normal", "etc/pam.d/su", "etc/pam.d/su-l", "usr/bin/su" }) {
            File target = new File(directory, name), parent = target.getParentFile();
            assertTrue(parent.isDirectory() || parent.mkdirs());
            JSONObject item = new JSONObject().put("name", name).put("parentExists", parent.isDirectory())
                .put("parentWritable", parent.canWrite()).put("symlink", java.nio.file.Files.isSymbolicLink(target.toPath()));
            try (OutputStream output = new FileOutputStream(target)) {
                output.write("harmless filename probe".getBytes(StandardCharsets.UTF_8));
                item.put("result", "written");
            } catch (Exception error) { item.put("result", "error").put("error", error.toString()); }
            item.put("existsAfter", target.isFile()); cases.put(item);
        }
        report.put("cases", cases);
        try (OutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "phone-path-report.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        android.os.Bundle result = new android.os.Bundle(); result.putString("path_report", report.toString()); instrumentation.sendStatus(0, result);
    }
    @Test public void nativeRuntimeLaunchDiagnosticsOnlyUseProbeCache() throws Exception {
        assertEquals("Refuse production package", "ai.mengluo.dsh.android.phoneprobe", context.getPackageName());
        int uid = android.os.Process.myUid();
        assertTrue("Diagnostic must run as an ordinary app UID", uid % 100000 >= 10000 && uid % 100000 < 20000);
        File directory = new File(context.getCacheDir(), "native-launch-probe-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        File nativeLibraries = new File(context.getApplicationInfo().nativeLibraryDir);
        File aliases = new File(directory, "aliases");
        List<List<String>> commands = List.of(
            List.of(new File(nativeLibraries, "libproot.so").getPath(), "--version"),
            List.of("/system/bin/sh", "-c", "exit 0"),
            List.of("/system/bin/sh", "-c", "echo harmless-native-launch-probe >&2; exit 17"),
            List.of("/system/bin/sh", "-c", "sleep 0.3; exit 0"));
        String[] names = { "proot-version", "shell-exit-zero", "shell-stderr-exit-seventeen", "shell-delayed-exit-zero" };
        long started = System.nanoTime(), deadline = started + TimeUnit.SECONDS.toNanos(20);
        JSONObject report = new JSONObject().put("package", context.getPackageName()).put("uid", uid)
            .put("pid", android.os.Process.myPid()).put("fixture", directory.getPath())
            .put("runtimeAbi", BuildConfig.RUNTIME_ABI).put("deadlineSeconds", 20).put("maxOutputBytes", 8192);
        JSONArray cases = new JSONArray();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            for (int commandIndex = 0; commandIndex < commands.size(); commandIndex++) {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    for (boolean wrapped : new boolean[] { false, true }) {
                        JSONObject item = new JSONObject().put("name", names[commandIndex]).put("attempt", attempt)
                            .put("mode", wrapped ? "wrapped" : "raw").put("argv", new JSONArray(commands.get(commandIndex)))
                            .put("uid", uid).put("launchThrew", false).put("exitCode", JSONObject.NULL)
                            .put("output", JSONObject.NULL).put("timedOut", false);
                        cases.put(item);
                        if (System.nanoTime() >= deadline) { item.put("notRun", "overall deadline reached"); continue; }
                        try {
                            ProcessBuilder builder = diagnosticNativeBuilder(commands.get(commandIndex), directory, nativeLibraries, aliases);
                            nativeLaunchCase(item, builder, wrapped, directory, deadline, reader);
                        } catch (Exception error) {
                            item.put("diagnosticException", error.toString());
                        }
                    }
                }
            }
        } finally {
            reader.shutdownNow();
            report.put("cases", cases).put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            File external = context.getExternalFilesDir(null);
            assertNotNull("Probe external files directory is unavailable", external);
            try (OutputStream output = new FileOutputStream(new File(external, "phone-native-launch-report.json"))) {
                output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            android.os.Bundle result = new android.os.Bundle();
            result.putString("native_launch_report", report.toString()); instrumentation.sendStatus(0, result);
        }
    }
    @Test public void pinnedGuestStartupDiagnosticsOnlyUseProbeCache() throws Exception {
        Assume.assumeTrue("Requires explicit isolated local guest diagnostic opt-in",
            "true".equals(InstrumentationRegistry.getArguments().getString("allowPinnedGuestProbe")));
        assertEquals("Refuse production package", "ai.mengluo.dsh.android.phoneprobe", context.getPackageName());
        int uid = android.os.Process.myUid();
        assertTrue("Diagnostic must run as an ordinary app UID", uid % 100000 >= 10000 && uid % 100000 < 20000);
        assertEquals("Pinned fixtures are x86_64 only", "x86_64", BuildConfig.RUNTIME_ABI);
        File external = context.getExternalFilesDir(null);
        assertNotNull("Probe external files directory is unavailable", external);
        File fixtures = new File(external, "native-fixtures");
        File ubuntu = new File(fixtures, "ubuntu-base.tgz"), node = new File(fixtures, "node-linux.tgz");
        assertTrue("Only an existing local Ubuntu fixture is allowed", ubuntu.isFile());
        assertTrue("Only an existing local Node fixture is allowed", node.isFile());
        File directory = new File(context.getCacheDir(), "guest-launch-probe-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        File rootfs = new File(directory, "rootfs"), nodeRoot = new File(rootfs, "opt/node");
        File working = new File(directory, "working"), profile = new File(directory, "profile");
        JSONObject report = new JSONObject().put("package", context.getPackageName()).put("uid", uid)
            .put("pid", android.os.Process.myPid()).put("fixture", directory.getPath()).put("runtimeAbi", BuildConfig.RUNTIME_ABI)
            .put("launchDeadlineSeconds", 20).put("maxOutputBytes", 8192).put("usesNetwork", false);
        JSONArray cases = new JSONArray();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        long started = System.nanoTime();
        try {
            report.put("stage", "verify-local-archives");
            ArchiveInstaller.verify(ubuntu, "6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9");
            ArchiveInstaller.verify(node, "f625d97cd707df4ff96254916fbc5ff014f09c09effe5a1e0ca8f6d41a8789d4");
            report.put("pinnedHashesVerified", true);
            assertTrue(rootfs.mkdirs());
            report.put("stage", "extract-ubuntu");
            ArchiveInstaller.extract(ubuntu, rootfs, 0, ignored -> {});
            assertTrue(nodeRoot.isDirectory() || nodeRoot.mkdirs());
            report.put("stage", "extract-node");
            ArchiveInstaller.extract(node, nodeRoot, 1, ignored -> {});
            report.put("stage", "prepare-isolated-directories");
            RuntimePolicy.requireElf64(new File(rootfs, "opt/node/bin/node"), BuildConfig.RUNTIME_ABI);
            for (String name : List.of("tmp", "workspace", "proc", "sys", "dev", "root/.dsh")) {
                File child = new File(rootfs, name); assertTrue(child.isDirectory() || child.mkdirs());
            }
            assertTrue(working.mkdirs()); assertTrue(profile.mkdirs());
            report.put("extractionElapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            File nativeLibraries = new File(context.getApplicationInfo().nativeLibraryDir);
            File aliases = new File(directory, "aliases");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            report.put("stage", "launch-guest-node");
            for (boolean wrapped : new boolean[] { false, true }) {
                JSONObject item = new JSONObject().put("name", "pinned-guest-node-version").put("mode", wrapped ? "wrapped" : "raw");
                cases.put(item);
                ArrayList<String> args = new ArrayList<>(List.of(new File(nativeLibraries, "libproot.so").getPath(),
                    "--kill-on-exit", "-0", "-r", rootfs.getPath(), "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-b", working.getPath() + ":/workspace", "-b", profile.getPath() + ":/root/.dsh", "-w", "/workspace",
                    "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp",
                    "DEBIAN_FRONTEND=noninteractive", "PATH=" + RuntimePolicy.GUEST_PATH, "DSH_HOME=/root/.dsh", "DSH_TELEMETRY_DISABLED=1"));
                args.addAll(DownloadSource.MIRROR.packageEnvironment());
                args.addAll(List.of("/opt/node/bin/node", "-e", "console.log('node='+process.version+' platform='+process.platform+' arch='+process.arch)"));
                ProcessBuilder builder = diagnosticNativeBuilder(args, directory, nativeLibraries, aliases);
                ProotCompatibility.configure(builder, rootfs);
                nativeLaunchCase(item, builder, wrapped, directory, deadline, reader);
            }
            report.put("stage", "diagnostics-complete");
        } catch (Exception | AssertionError error) {
            report.put("diagnosticException", error.toString());
        } finally {
            reader.shutdownNow();
            report.put("cases", cases).put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            try (OutputStream output = new FileOutputStream(new File(external, "phone-guest-launch-report.json"))) {
                output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            android.os.Bundle result = new android.os.Bundle();
            result.putString("guest_launch_report", report.toString()); instrumentation.sendStatus(0, result);
        }
    }
    private static ProcessBuilder diagnosticNativeBuilder(List<String> command, File directory, File nativeLibraries, File aliases) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory).redirectErrorStream(true);
        ProotLibraries.configure(builder, nativeLibraries, aliases);
        builder.environment().put("PROOT_LOADER", new File(nativeLibraries, "libproot-loader.so").getPath());
        builder.environment().put("PROOT_TMP_DIR", directory.getPath());
        builder.environment().put("PROOT_NO_SECCOMP", "1");
        return builder;
    }
    private static void nativeLaunchCase(JSONObject item, ProcessBuilder builder, boolean wrapped, File directory,
                                         long deadline, ExecutorService reader) throws Exception {
        item.put("uid", android.os.Process.myUid()).put("argv", new JSONArray(builder.command())).put("cwd", builder.directory().getPath())
            .put("launchThrew", false).put("exitCode", JSONObject.NULL).put("output", JSONObject.NULL).put("timedOut", false);
        JSONObject safeEnvironment = new JSONObject();
        for (String key : new String[] { "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_TMP_DIR", "PROOT_NO_SECCOMP", "PROOT_L2S_DIR" })
            if (builder.environment().containsKey(key)) safeEnvironment.put(key, builder.environment().get(key));
        safeEnvironment.put("LD_PRELOAD_PRESENT", builder.environment().containsKey("LD_PRELOAD"));
        item.put("environment", safeEnvironment);
        if (System.nanoTime() >= deadline) { item.put("notRun", "overall launch deadline reached"); return; }
        Process process = null;
        Future<JSONObject> output = null;
        long started = System.nanoTime();
        try {
            try { process = wrapped ? RuntimeProcesses.launch(builder, directory) : builder.start(); }
            catch (Exception error) {
                item.put("launchThrew", true).put("launchException", error.toString())
                    .put("outputUnavailable", "launch did not return a Process; no output or exit code was observed");
                return;
            }
            Process launched = process;
            output = reader.submit(() -> nativeLaunchOutput(launched.getInputStream()));
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                item.put("timedOut", true);
            } else {
                item.put("exitCode", process.exitValue());
                long remaining = Math.max(1, deadline - System.nanoTime());
                JSONObject captured = output.get(Math.min(remaining, TimeUnit.SECONDS.toNanos(2)), TimeUnit.NANOSECONDS);
                item.put("output", captured.getString("text")).put("outputBytes", captured.getLong("bytes"))
                    .put("outputTruncated", captured.getBoolean("truncated"));
            }
        } catch (Exception error) { item.put("diagnosticException", error.toString()); }
        finally {
            if (process != null) {
                if (process.isAlive()) { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS); }
                item.put("aliveAfterCleanup", process.isAlive());
                closeNativeLaunchStream(process.getInputStream());
                closeNativeLaunchStream(process.getErrorStream());
                closeNativeLaunchStream(process.getOutputStream());
            }
            if (output != null) output.cancel(true);
            item.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }
    private static JSONObject nativeLaunchOutput(InputStream input) throws Exception {
        ByteArrayOutputStream kept = new ByteArrayOutputStream(8192);
        long total = 0;
        try (input) {
            byte[] bytes = new byte[2048]; int length;
            while ((length = input.read(bytes)) != -1) {
                total += length;
                int retain = Math.min(length, 8192 - kept.size());
                if (retain > 0) kept.write(bytes, 0, retain);
            }
        }
        return new JSONObject().put("text", new String(kept.toByteArray(), StandardCharsets.UTF_8))
            .put("bytes", total).put("truncated", total > 8192);
    }
    private static void closeNativeLaunchStream(Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { /* Only streams from this diagnostic's own process. */ }
    }
    @Test public void permissionGuideRendersWithoutGrantingAnything() throws Exception {
        Assume.assumeFalse(Settings.canDrawOverlays(context));
        boolean enabled = control.enabled();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                androidx.appcompat.app.AlertDialog guide = PhonePermissions.show(activity);
                assertTrue(guide.isShowing()); assertNotNull(guide.getWindow());
                assertTrue(hasText(guide.getWindow().getDecorView(), "授权无障碍服务"));
                assertTrue(hasText(guide.getWindow().getDecorView(), "授权跨应用悬浮窗"));
                assertTrue(hasText(guide.getWindow().getDecorView(), "重新检查状态"));
                assertEquals(enabled, control.enabled()); assertFalse(Settings.canDrawOverlays(context));
            });
            Thread.sleep(500);
            android.graphics.Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
            try (OutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "phone-permission-guide.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); }
            image.recycle();
        }
    }
    private boolean hasText(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().equals(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) if (hasText(((ViewGroup)view).getChildAt(i), text)) return true;
        return false;
    }
    @Test public void unmaskedFullDisplayOnlyTouchesFixture() throws Exception {
        Assume.assumeTrue("Requires explicit user's isolated-test permission approval", InstrumentationRegistry.getArguments().getString("allowPhoneFixture", "false").equals("true"));
        long deadline = System.currentTimeMillis() + 10000;
        while ((!control.enabled() || PhoneAccess.connected == null) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue(Settings.canDrawOverlays(context)); assertTrue(control.enabled()); assertNotNull(PhoneAccess.connected);
        assertFalse("This diagnostic must not request screen sharing", PhoneCaptureService.available());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            JSONObject begun = rpc(new JSONObject().put("op", "begin").put("purpose", "Unmasked full-display screenshot of the isolated fixture only"));
            assertEquals(begun.toString(), "active", begun.getString("status")); String lease = begun.getString("lease");
            String fixture = context.getPackageName() + ".test";
            assertEquals("action_dispatched", rpc(new JSONObject().put("op", "action").put("lease", lease).put("action", "launch").put("package", fixture)).getString("status"));
            Thread.sleep(800);
            JSONObject tree = rpc(new JSONObject().put("op", "observe").put("lease", lease));
            assertEquals(tree.toString(), "ok", tree.getString("status"));
            int hide = nodeWithText(tree, "Hide sensitive fixture"); assertTrue(hide > 0);
            assertEquals("action_dispatched", rpc(new JSONObject().put("op", "action").put("lease", lease).put("action", "click").put("snapshot", tree.getString("snapshot")).put("node", hide)).getString("status"));
            Thread.sleep(700);
            instrumentation.runOnMainSync(() -> assertTrue("Keep the actual stop pill visible during capture", overlay().attached()));

            // Bypass the production screenshot pipeline completely: no masking, clipping, scaling,
            // empty-frame rejection, or MediaProjection fallback. Keep every on-screen window visible.
            JSONObject report = new JSONObject().put("mode", "raw_default_display_no_mask_no_crop").put("overlayVisible", true);
            PhoneAccess initialService = PhoneAccess.connected;
            for (int i = 1; i <= 2; i++) {
                JSONObject before = screenshotServiceHealth(initialService);
                android.graphics.Bitmap raw = unmaskedAccessibilityScreenshot();
                try { report.put("accessibility" + i, saveCapture(raw, "phone-unmasked-" + i + ".png")); }
                finally { raw.recycle(); }
                JSONObject after = screenshotServiceHealth(initialService);
                after.put("observationStatus", rpc(new JSONObject().put("op", "observe").put("lease", lease)).optString("status"));
                report.put("serviceBefore" + i, before).put("serviceAfter" + i, after);
                Thread.sleep(400);
            }
            // Privileged instrumentation capture is only a visual reference, never an app fallback.
            android.graphics.Bitmap reference = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
            assertNotNull(reference);
            try {
                JSONObject stats = saveCapture(reference, "phone-unmasked-reference.png"); report.put("testReference", stats);
                assertTrue("The reference fixture must be visibly nonempty", stats.getInt("brightPixels") > stats.getInt("pixels") * 0.6);
                for (int i = 1; i <= 2; i++) {
                    assertEquals(reference.getWidth(), report.getJSONObject("accessibility" + i).getInt("width"));
                    assertEquals(reference.getHeight(), report.getJSONObject("accessibility" + i).getInt("height"));
                }
            } finally { reference.recycle(); }
            assertFalse(PhoneCaptureService.available());
            try (OutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "phone-unmasked-report.json"))) { output.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
            android.os.Bundle result = new android.os.Bundle(); result.putString("unmasked_screen_report", report.toString()); instrumentation.sendStatus(0, result);
        }
    }
    private JSONObject screenshotServiceHealth(PhoneAccess expected) throws Exception {
        boolean[] state = new boolean[2];
        instrumentation.runOnMainSync(() -> {
            PhoneAccess current = PhoneAccess.connected;
            state[0] = current != null && current == expected;
            state[1] = current != null && current.getServiceInfo() != null;
        });
        return new JSONObject().put("processId", android.os.Process.myPid())
            .put("sameConnectedService", state[0]).put("serviceInfoAvailable", state[1]);
    }
    @Test public void restartedDisplayScreenshotOnlyTouchesFixture() throws Exception {
        Assume.assumeTrue("Requires explicit user's isolated-test permission approval", InstrumentationRegistry.getArguments().getString("allowPhoneFixture", "false").equals("true"));
        long deadline = System.currentTimeMillis() + 10000;
        while ((!control.enabled() || PhoneAccess.connected == null) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue(Settings.canDrawOverlays(context)); assertTrue(control.enabled()); assertNotNull(PhoneAccess.connected);
        assertFalse(PhoneCaptureService.available());
        JSONObject begun = rpc(new JSONObject().put("op", "begin").put("purpose", "Read only the isolated fixture display after the emulator restart"));
        assertEquals(begun.toString(), "active", begun.getString("status"));
        String fixture = context.getPackageName() + ".test";
        // Do not create/close a second ActivityScenario: some emulator multi-display modes kill
        // instrumentation when its separate task is removed. The runner cleans up both test APKs.
        context.startActivity(new Intent().setComponent(new ComponentName(fixture, PhoneFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        int fixtureDisplay = -1;
        deadline = System.currentTimeMillis() + 6000;
        while (fixtureDisplay < 0 && System.currentTimeMillis() < deadline) { Thread.sleep(200); fixtureDisplay = isolatedFixtureDisplay(fixture, true); }
        assertTrue("The known fixture window must be present", fixtureDisplay >= 0); Thread.sleep(700);
        PhoneAccess initialService = PhoneAccess.connected;
        int screenshotGapMs = Integer.parseInt(InstrumentationRegistry.getArguments().getString("screenshotGapMs", "600"));
        assertTrue("Bounded diagnostic capture rate", screenshotGapMs >= 400 && screenshotGapMs <= 10000);
        String[] delaySequence = InstrumentationRegistry.getArguments().getString("screenshotDelaySequence", "0,0,0").split(",");
        assertTrue("Bounded diagnostic capture count", delaySequence.length > 0 && delaySequence.length <= 12);
        int[] preDelays = new int[delaySequence.length];
        for (int i = 0; i < preDelays.length; i++) {
            preDelays[i] = Integer.parseInt(delaySequence[i]);
            assertTrue("Only compare no added delay and 200ms", preDelays[i] == 0 || preDelays[i] == 200);
        }
        JSONObject report = new JSONObject().put("mode", "actual_fixture_display_no_mask_no_crop")
            .put("requestedDisplay", fixtureDisplay).put("defaultDisplay", Display.DEFAULT_DISPLAY).put("configuredGapMs", screenshotGapMs)
            .put("preDelaySequenceMs", new JSONArray(delaySequence)).put("scheduledCaptures", preDelays.length);
        long previousRequest = 0;
        Exception captureFailure = null;
        for (int i = 1; i <= preDelays.length; i++) {
            assertEquals("Never capture a different app/display", fixtureDisplay, isolatedFixtureDisplay(fixture, false));
            report.put("serviceBefore" + i, screenshotServiceHealth(initialService));
            long[] timing = new long[3];
            android.graphics.Bitmap raw = null;
            try {
                raw = unmaskedAccessibilityScreenshot(fixtureDisplay, timing, preDelays[i - 1], fixture);
                JSONObject pixels = saveCapture(raw, "phone-restarted-display-" + i + ".png").put("callback", "onSuccess")
                    .put("requestUptimeMs", timing[0]).put("callbackAfterMs", timing[1] - timing[0])
                    .put("configuredPreDelayMs", preDelays[i - 1]).put("actualPreDelayMs", timing[0] - timing[2]).put("firstCapture", i == 1)
                    .put("requestGapMs", previousRequest == 0 ? JSONObject.NULL : timing[0] - previousRequest);
                report.put("accessibility" + i, pixels); previousRequest = timing[0];
            } catch (Exception error) {
                // Preserve failed callbacks/timeouts too; never continue with an unresolved capture.
                captureFailure = error;
                Throwable cause = error.getCause() == null ? error : error.getCause();
                report.put("accessibility" + i, new JSONObject().put("callback", "error")
                    .put("configuredPreDelayMs", preDelays[i - 1]).put("requestUptimeMs", timing[0])
                    .put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage()));
            } finally {
                if (raw != null) raw.recycle();
            }
            report.put("serviceAfter" + i, screenshotServiceHealth(initialService));
            if (captureFailure != null) break;
            if (i < preDelays.length) Thread.sleep(screenshotGapMs);
        }
        assertFalse(PhoneCaptureService.available());
        try (OutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "phone-restarted-display-report.json"))) { output.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
        android.os.Bundle result = new android.os.Bundle(); result.putString("restart_display_report", report.toString()); instrumentation.sendStatus(0, result);
        if (captureFailure != null) throw captureFailure;
    }
    private int isolatedFixtureDisplay(String fixture, boolean hidePassword) {
        assertEquals("Only inspect the independent fixture", "ai.mengluo.dsh.android.phoneprobe.test", fixture);
        android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>> displays = PhoneAccess.connected.getWindowsOnAllDisplays();
        int found = -1;
        for (int i = 0; i < displays.size(); i++) for (android.view.accessibility.AccessibilityWindowInfo window : displays.valueAt(i)) {
            try {
                if (window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                android.view.accessibility.AccessibilityNodeInfo root = window.getRoot(); if (root == null) continue;
                try {
                    if (!fixture.contentEquals(Objects.toString(root.getPackageName(), ""))) continue;
                    assertTrue("Ambiguous fixture display", found < 0 || found == window.getDisplayId()); found = window.getDisplayId();
                    if (hidePassword) {
                        List<android.view.accessibility.AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByText("Hide sensitive fixture");
                        for (android.view.accessibility.AccessibilityNodeInfo button : buttons) {
                            try { if (button.isClickable()) assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)); }
                            finally { button.recycle(); }
                        }
                    }
                } finally { root.recycle(); }
            } finally { window.recycle(); }
        }
        return found;
    }
    private android.graphics.Bitmap unmaskedAccessibilityScreenshot() throws Exception {
        return unmaskedAccessibilityScreenshot(Display.DEFAULT_DISPLAY);
    }
    private android.graphics.Bitmap unmaskedAccessibilityScreenshot(int displayId) throws Exception {
        return unmaskedAccessibilityScreenshot(displayId, new long[3], 0, null);
    }
    private android.graphics.Bitmap unmaskedAccessibilityScreenshot(int displayId, long[] timing, int preDelayMs, String fixture) throws Exception {
        CompletableFuture<android.graphics.Bitmap> result = new CompletableFuture<>();
        instrumentation.runOnMainSync(() -> {
            timing[2] = android.os.SystemClock.uptimeMillis();
            // Both arms use the same queue. Never sleep on the main thread or block drawing.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (result.isDone()) return;
            try {
            if (fixture != null) assertEquals("Fixture display must still be the capture target", displayId, isolatedFixtureDisplay(fixture, false));
            timing[0] = android.os.SystemClock.uptimeMillis();
            PhoneAccess.connected.takeScreenshot(displayId, context.getMainExecutor(), new android.accessibilityservice.AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult screenshot) {
                timing[1] = android.os.SystemClock.uptimeMillis();
                android.hardware.HardwareBuffer buffer = screenshot.getHardwareBuffer(); android.graphics.Bitmap hardware = null;
                try {
                    hardware = android.graphics.Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("No hardware bitmap");
                    android.graphics.Bitmap copy = hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                    if (copy == null) throw new IllegalStateException("No CPU bitmap");
                    if (!result.complete(copy)) copy.recycle();
                } catch (Throwable error) { result.completeExceptionally(error); }
                finally { if (hardware != null) hardware.recycle(); buffer.close(); }
            }
            @Override public void onFailure(int code) { result.completeExceptionally(new IllegalStateException("Accessibility screenshot code " + code)); }
            });
            } catch (Throwable error) { result.completeExceptionally(error); }
            }, preDelayMs);
        });
        try { return result.get(5, TimeUnit.SECONDS); }
        catch (Exception error) {
            // Completion can win the race with a timed-out/interrupted waiter. In that case
            // the callback transferred ownership, but there is no caller left to recycle it.
            if (!result.cancel(false) && !result.isCompletedExceptionally()) {
                android.graphics.Bitmap abandoned = result.getNow(null);
                if (abandoned != null) abandoned.recycle();
            }
            throw error;
        }
    }
    private JSONObject saveCapture(android.graphics.Bitmap bitmap, String name) throws Exception {
        try (OutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)); }
        int[] row = new int[bitmap.getWidth()]; int bright = 0, rgbNonzero = 0, alphaNonzero = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int color : row) {
                if ((color & 0x00ffffff) != 0) rgbNonzero++;
                if (android.graphics.Color.alpha(color) != 0) alphaNonzero++;
                if ((android.graphics.Color.red(color) + android.graphics.Color.green(color) + android.graphics.Color.blue(color)) / 3 > 160) bright++;
            }
        }
        return new JSONObject().put("width", bitmap.getWidth()).put("height", bitmap.getHeight()).put("pixels", bitmap.getWidth() * bitmap.getHeight())
            .put("brightPixels", bright).put("nonzeroRgbPixels", rgbNonzero).put("nonzeroAlphaPixels", alphaNonzero);
    }
    @Test(timeout = 10000) public void dynamicContentDoesNotCreateCoordinateRevisionGates() throws Exception {
        // Structural contract only: no connected service, real screen access, or permission grant.
        Class<?> planType = Class.forName(PhoneAccess.class.getName() + "$ScreenPlan");
        Set<String> fields = new HashSet<>();
        for (java.lang.reflect.Field field : planType.getDeclaredFields()) if (!field.isSynthetic() && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) fields.add(field.getName());
        assertEquals(Set.of("pkg", "window", "width", "height", "rotation", "overlay"), fields);
        Class<?> screenType = Class.forName(PhoneAccess.class.getName() + "$CapturedScreen"); fields.clear();
        for (java.lang.reflect.Field field : screenType.getDeclaredFields()) if (!field.isSynthetic() && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) fields.add(field.getName());
        assertEquals(Set.of("id", "plan", "imageWidth", "imageHeight", "capturedAt"), fields);
        assertEquals(planType, PhoneAccess.class.getDeclaredMethod("captureStart", Set.class, java.util.function.Supplier.class).getReturnType());

        PhoneAccess service = new PhoneAccess();
        java.lang.reflect.Field epoch = PhoneAccess.class.getDeclaredField("captureEpoch"); epoch.setAccessible(true); epoch.setLong(service, 41L);
        for (int type : new int[]{android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED,
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED}) {
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(type);
            try { event.setPackageName("ai.mengluo.dsh.android.phoneprobe.test"); service.onAccessibilityEvent(event); }
            finally { event.recycle(); }
            assertEquals("An accessibility event alone must not cancel an existing image", 41L, epoch.getLong(service));
        }
        java.lang.reflect.Constructor<?> constructor = planType.getDeclaredConstructor(String.class, int.class, int.class, int.class, int.class, PhoneScreenPolicy.Box.class);
        constructor.setAccessible(true); PhoneScreenPolicy.Box overlay = new PhoneScreenPolicy.Box(800, 20, 900, 100);
        Object plan = constructor.newInstance("fixture", 12, 1080, 1920, 0, overlay);
        assertEquals(plan, constructor.newInstance("fixture", 12, 1080, 1920, 0, overlay));
        assertNotEquals("Window changes remain protected", plan, constructor.newInstance("fixture", 13, 1080, 1920, 0, overlay));
        assertNotEquals("Display size changes remain protected", plan, constructor.newInstance("fixture", 12, 1920, 1080, 0, overlay));
        assertNotEquals("Rotation changes remain protected", plan, constructor.newInstance("fixture", 12, 1080, 1920, 1, overlay));
        assertNotEquals("Stop overlay movement remains protected", plan, constructor.newInstance("fixture", 12, 1080, 1920, 0, new PhoneScreenPolicy.Box(700, 20, 800, 100)));
    }

    @Test(timeout = 45000) public void visualTouchesAllowDynamicContentAndRecheckRisk() throws Exception {
        Assume.assumeTrue("Requires explicit isolated fixture permissions",
            "true".equals(InstrumentationRegistry.getArguments().getString("allowPhoneFixture")));
        long serviceDeadline = android.os.SystemClock.elapsedRealtime() + 10000;
        while ((!control.enabled() || PhoneAccess.connected == null) && android.os.SystemClock.elapsedRealtime() < serviceDeadline) Thread.sleep(100);
        assertTrue("The test never grants overlay permission", Settings.canDrawOverlays(context));
        assertTrue("The test never enables accessibility", control.enabled());
        assertNotNull("Isolated accessibility service must already be connected", PhoneAccess.connected);
        String fixture = context.getPackageName() + ".test";
        assertEquals("ai.mengluo.dsh.android.phoneprobe.test", fixture);
        // MuMu may retain each app on a private display. This suite exercises the
        // production-supported display 0; move only our two isolated test activities.
        android.os.Bundle primaryDisplay = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle();
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK), primaryDisplay);
        Thread.sleep(700);
        assertEquals("Probe app must run on the supported primary display", Display.DEFAULT_DISPLAY,
            context.getSystemService(WindowManager.class).getDefaultDisplay().getDisplayId());
        instrumentation.runOnMainSync(() -> assertEquals("The native service must use the supported primary display", Display.DEFAULT_DISPLAY,
            PhoneAccess.connected.getSystemService(WindowManager.class).getDefaultDisplay().getDisplayId()));
        JSONObject begun = rpc(new JSONObject().put("op", "begin").put("purpose", "Validate coordinates only in the isolated revision fixture"));
        assertEquals(begun.toString(), "active", begun.getString("status"));
        String lease = begun.getString("lease");
        context.startActivity(new Intent().setComponent(new ComponentName(fixture, PhoneFixtureActivity.class.getName()))
            .putExtra("revisionFixture", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK), primaryDisplay);
        try {
            Thread.sleep(700);
            instrumentation.runOnMainSync(() -> assertEquals("Fixture must actually be on display 0; do not fake window geometry",
                Display.DEFAULT_DISPLAY, isolatedFixtureDisplay(fixture, false)));
            CompletableFuture<Void> compact = new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> overlay().whenCompact(() -> compact.complete(null)));
            compact.get(5, TimeUnit.SECONDS);
            assertEquals("Count: 0", revisionFixtureCommand("count").getString("count"));

            RevisionFrame first = revisionFixtureFrame(fixture);
            revisionFixtureCommand("startTicker"); Thread.sleep(700);
            assertTrue("The independent hint must actually have changed", revisionFixtureCommand("count").getInt("ticks") >= 3);
            JSONObject tapped = revisionTap(first, lease);
            assertEquals(tapped.toString(), "action_dispatched", tapped.getString("status")); Thread.sleep(150);
            assertEquals("Count: 1", revisionFixtureCommand("count").getString("count"));

            revisionFixtureCommand("reset"); Thread.sleep(350);
            RevisionFrame unrelatedScroll = revisionFixtureFrame(fixture);
            revisionFixtureCommand("scrollUnrelated"); Thread.sleep(400);
            tapped = revisionTap(unrelatedScroll, lease);
            assertEquals("Disjoint scrolling must not invalidate a stable target: " + tapped, "action_dispatched", tapped.getString("status"));
            Thread.sleep(150); assertEquals("Count: 2", revisionFixtureCommand("count").getString("count"));

            int expectedCount = 2;
            for (String mutation : List.of("ordinaryLabel", "moveTarget", "scroll")) {
                revisionFixtureCommand("reset"); Thread.sleep(350);
                RevisionFrame before = revisionFixtureFrame(fixture);
                revisionFixtureCommand(mutation); Thread.sleep(400);
                // Read current coordinates from our own test View, never guess where a moved
                // target went. Keep the original screenshot ID to exercise dynamic tolerance.
                JSONObject accepted = revisionTap(revisionFixtureCurrentTarget(before), lease);
                assertEquals(mutation + ": " + accepted, "action_dispatched", accepted.getString("status"));
                Thread.sleep(150); assertEquals("Count: " + (++expectedCount), revisionFixtureCommand("count").getString("count"));
            }

            revisionFixtureCommand("reset"); Thread.sleep(350);
            RevisionFrame priorWindow = revisionFixtureFrame(fixture);
            revisionFixtureCommand("newWindow"); Thread.sleep(400);
            JSONObject rejected = revisionTap(priorWindow, lease);
            assertEquals(rejected.toString(), "screen_changed", rejected.getString("status"));
            assertTrue("Must distinguish a window rejection from dispatch", rejected.has("actionDispatched"));
            assertFalse(rejected.getBoolean("actionDispatched"));
            assertEquals("The rejected window action must not increment the counter", "Count: " + expectedCount, revisionFixtureCommand("count").getString("count"));

            // Use the existing empty input only after all original geometry cases have completed.
            revisionFixtureCommand("reset");
            android.os.Bundle beforeHint = revisionFixtureCommand("prepareHint");
            assertFalse("The focus assertion needs a genuinely unfocused input", beforeHint.getBoolean("inputFocused"));
            assertEquals(0, beforeHint.getInt("inputLength")); Thread.sleep(350);
            RevisionFrame editable = revisionFixtureFrame(fixture, true);
            revisionFixtureCommand("startHintTicker"); Thread.sleep(700);
            assertTrue("Placeholder text must really rotate after capture", revisionFixtureCommand("count").getInt("hintTicks") >= 3);
            JSONObject focused = revisionTap(editable, lease, "Focus the empty test search input");
            assertEquals(focused.toString(), "action_dispatched", focused.getString("status")); Thread.sleep(150);
            android.os.Bundle afterHint = revisionFixtureCommand("count");
            assertTrue("A dispatched coordinate tap must actually focus the same input", afterHint.getBoolean("inputFocused"));
            assertEquals("No actual text entry is part of this test", 0, afterHint.getInt("inputLength"));
            assertEquals("Count: " + expectedCount, afterHint.getString("count"));

            revisionFixtureCommand("reset"); Thread.sleep(350);
            RevisionFrame normal = revisionFixtureFrame(fixture);
            JSONObject normalInput = at("tap", normal.screenshot, lease, normal.targetX, normal.targetY, normal.display).put("target", "Increment test counter");
            PhoneAccess.Touch[] planned = new PhoneAccess.Touch[1]; CompletableFuture<Void> planning = new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> {
                try {
                    planned[0] = PhoneAccess.connected.planTouch(normalInput, false, Set.of(fixture), () -> overlay().screenBounds());
                    assertFalse("Plan an ordinary action before the fixture changes its meaning", planned[0].needsConfirmation()); planning.complete(null);
                } catch (Throwable error) { planning.completeExceptionally(error); }
            });
            planning.get(3, TimeUnit.SECONDS);
            revisionFixtureCommand("dangerousLabel"); Thread.sleep(400);
            CompletableFuture<JSONObject> riskResult = new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> PhoneAccess.connected.touch(planned[0], Set.of(fixture), () -> overlay().screenBounds(), false, riskResult::complete));
            JSONObject risk = riskResult.get(3, TimeUnit.SECONDS);
            assertEquals("Changed risk still needs the human: " + risk, "confirmation_required", risk.getString("status"));
            assertTrue(risk.has("actionDispatched")); assertFalse(risk.getBoolean("actionDispatched"));
            assertEquals("No unconfirmed risky action may increment the counter", "Count: " + expectedCount, revisionFixtureCommand("count").getString("count"));
        } finally {
            control.stop("user_cancelled");
            revisionFixtureCommand("finish");
        }
    }
    private android.os.Bundle revisionFixtureCommand(String operation) throws Exception {
        CompletableFuture<android.os.Bundle> result = new CompletableFuture<>();
        android.os.ResultReceiver reply = new android.os.ResultReceiver(new android.os.Handler(android.os.Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int code, android.os.Bundle data) {
                if (code == 0) result.complete(data);
                else result.completeExceptionally(new AssertionError("Isolated fixture: " + data.getString("error")));
            }
        };
        String fixture = "ai.mengluo.dsh.android.phoneprobe.test";
        context.sendBroadcast(new Intent(fixture + ".REVISION_FIXTURE").setPackage(fixture).putExtra("operation", operation).putExtra("reply", reply));
        return result.get(4, TimeUnit.SECONDS);
    }
    private record RevisionFrame(JSONObject screenshot, int targetX, int targetY, android.graphics.Point display) { }
    private RevisionFrame revisionFixtureFrame(String fixture) throws Exception {
        return revisionFixtureFrame(fixture, false);
    }
    private RevisionFrame revisionFixtureFrame(String fixture, boolean editable) throws Exception {
        android.os.Bundle drawn = revisionFixtureCommand("paint"); byte[] bytes = drawn.getByteArray("image"); assertNotNull(bytes);
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inMutable = true;
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        assertNotNull(bitmap);
        // Software-render only the test activity. Never use UiAutomation or a screen-capture permission here.
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        instrumentation.runOnMainSync(() -> {
            boolean transferred = false;
            try {
                PhoneAccess access = PhoneAccess.connected; assertNotNull(access);
                assertEquals("Never observe another app", fixture, access.foreground()); access.clear();
                java.util.function.Supplier<PhoneScreenPolicy.Box> mask = () -> overlay().screenBounds();
                java.lang.reflect.Method start = PhoneAccess.class.getDeclaredMethod("captureStart", Set.class, java.util.function.Supplier.class);
                start.setAccessible(true); Object expected = start.invoke(access, Set.of(fixture), mask);
                java.lang.reflect.Field epoch = PhoneAccess.class.getDeclaredField("captureEpoch"); epoch.setAccessible(true);
                java.lang.reflect.Method deliver = PhoneAccess.class.getDeclaredMethod("deliverScreenshot", expected.getClass(), long.class, long.class,
                    Set.class, java.util.function.Supplier.class, android.graphics.Bitmap.class, String.class, java.util.function.Consumer.class);
                deliver.setAccessible(true); transferred = true;
                deliver.invoke(access, expected, epoch.getLong(access), android.os.SystemClock.elapsedRealtime(), Set.of(fixture), mask,
                    bitmap, "isolated_view_draw", (java.util.function.Consumer<JSONObject>)result::complete);
            } catch (Throwable error) { result.completeExceptionally(error); }
            finally { if (!transferred && !bitmap.isRecycled()) bitmap.recycle(); }
        });
        JSONObject screenshot = result.get(4, TimeUnit.SECONDS);
        assertEquals(screenshot.toString(), "ok", screenshot.getString("status"));
        return new RevisionFrame(screenshot, drawn.getInt(editable ? "inputX" : "targetX"), drawn.getInt(editable ? "inputY" : "targetY"), new android.graphics.Point(drawn.getInt("width"), drawn.getInt("height")));
    }
    private RevisionFrame revisionFixtureCurrentTarget(RevisionFrame prior) throws Exception {
        android.os.Bundle current = revisionFixtureCommand("paint");
        assertEquals(prior.display.x, current.getInt("width")); assertEquals(prior.display.y, current.getInt("height"));
        return new RevisionFrame(prior.screenshot, current.getInt("targetX"), current.getInt("targetY"), prior.display);
    }
    private JSONObject revisionTap(RevisionFrame frame, String lease) throws Exception {
        return revisionTap(frame, lease, "Increment test counter");
    }
    private JSONObject revisionTap(RevisionFrame frame, String lease, String target) throws Exception {
        JSONObject input = at("tap", frame.screenshot, lease, frame.targetX, frame.targetY, frame.display).put("target", target);
        JSONObject result = worker.submit(() -> rpc(input)).get(5, TimeUnit.SECONDS);
        if (result.optString("status").equals("outside_foreground_app")) reportRevisionTapGeometry(frame, input, result);
        return result;
    }
    private void reportRevisionTapGeometry(RevisionFrame frame, JSONObject input, JSONObject response) throws Exception {
        JSONObject report = new JSONObject().put("status", response.optString("status"))
            .put("imageX", input.getInt("x")).put("imageY", input.getInt("y"))
            .put("imageWidth", frame.screenshot.getInt("width")).put("imageHeight", frame.screenshot.getInt("height"))
            .put("fixtureTargetDisplayX", frame.targetX).put("fixtureTargetDisplayY", frame.targetY)
            .put("fixtureDisplayWidth", frame.display.x).put("fixtureDisplayHeight", frame.display.y);
        PhoneScreenPolicy.Pixel mapped = PhoneScreenPolicy.toDisplay(input.getInt("x"), input.getInt("y"),
            frame.screenshot.getInt("width"), frame.screenshot.getInt("height"), frame.display.x, frame.display.y);
        report.put("mappedDisplayX", mapped.x()).put("mappedDisplayY", mapped.y());
        instrumentation.runOnMainSync(() -> {
            try {
                PhoneAccess access = PhoneAccess.connected;
                report.put("serviceConnected", access != null);
                if (access == null) return;
                android.graphics.Point current = new android.graphics.Point();
                Display display = access.getSystemService(WindowManager.class).getDefaultDisplay(); display.getRealSize(current);
                report.put("nativeDisplayId", display.getDisplayId()).put("nativeDisplayWidth", current.x).put("nativeDisplayHeight", current.y).put("rotation", display.getRotation());
                android.view.accessibility.AccessibilityNodeInfo root = access.getRootInActiveWindow();
                if (root != null) {
                    try {
                        boolean fixtureRoot = "ai.mengluo.dsh.android.phoneprobe.test".contentEquals(Objects.toString(root.getPackageName(), ""));
                        report.put("foregroundIsFixture", fixtureRoot);
                        // Read neither text nor identities belonging to other applications.
                        if (fixtureRoot) {
                            android.graphics.Rect bounds = new android.graphics.Rect(); root.getBoundsInScreen(bounds);
                            report.put("fixtureWindowId", root.getWindowId()).put("fixtureRootBounds", new JSONArray(List.of(bounds.left, bounds.top, bounds.right, bounds.bottom)));
                        }
                    } finally { root.recycle(); }
                }
                JSONArray windows = new JSONArray();
                for (android.view.accessibility.AccessibilityWindowInfo window : access.getWindows()) {
                    try {
                        android.graphics.Rect bounds = new android.graphics.Rect(); window.getBoundsInScreen(bounds);
                        windows.put(new JSONObject().put("id", window.getId()).put("type", window.getType()).put("layer", window.getLayer())
                            .put("display", window.getDisplayId()).put("bounds", new JSONArray(List.of(bounds.left, bounds.top, bounds.right, bounds.bottom))));
                    } finally { window.recycle(); }
                }
                report.put("windowGeometry", windows);
                PhoneScreenPolicy.Box own = overlay().screenBounds();
                report.put("ownOverlayBounds", own == null ? JSONObject.NULL : new JSONArray(List.of(own.left(), own.top(), own.right(), own.bottom())));
            } catch (Exception error) {
                try { report.put("diagnosticFailure", error.getClass().getSimpleName()); }
                catch (JSONException ignored) { }
            }
        });
        android.os.Bundle status = new android.os.Bundle(); status.putString("revision_tap_geometry", report.toString()); instrumentation.sendStatus(0, status);
    }

    @Test public void automaticActionsScreenshotAndStopOnlyTouchFixture() throws Exception {
        Assume.assumeTrue("Requires explicit user's isolated-test permission approval", InstrumentationRegistry.getArguments().getString("allowPhoneFixture", "false").equals("true"));
        long deadline = System.currentTimeMillis() + 10000;
        while ((!control.enabled() || PhoneAccess.connected == null) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue("Test overlay permission was not granted", Settings.canDrawOverlays(context));
        assertTrue("Test accessibility service was not enabled", control.enabled());
        assertNotNull("Test accessibility service did not connect", PhoneAccess.connected);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Future<JSONObject> begin = worker.submit(() -> rpc(new JSONObject().put("op", "begin").put("purpose", "Only increment the separate fixture counter")));
            JSONObject started = begin.get(5, TimeUnit.SECONDS);
            assertEquals(started.toString(), "active", started.getString("status")); String lease = started.getString("lease");
            assertFalse(started.getJSONArray("allowedApps").toString().contains("com.android.settings"));
            String fixture = context.getPackageName() + ".test";
            Future<JSONObject> launch = worker.submit(() -> rpc(new JSONObject().put("op", "action").put("lease", lease).put("action", "launch").put("package", fixture)));
            assertEquals("action_dispatched", launch.get(5, TimeUnit.SECONDS).getString("status")); Thread.sleep(800);
            JSONObject observed = rpc(new JSONObject().put("op", "observe").put("lease", lease));
            long observeDeadline = System.currentTimeMillis() + 5000;
            while (observed.optString("status").equals("screen_unavailable") && System.currentTimeMillis() < observeDeadline) {
                Thread.sleep(200); observed = rpc(new JSONObject().put("op", "observe").put("lease", lease));
            }
            assertEquals(observed.toString(), "ok", observed.getString("status"));
            assertFalse(observed.toString().contains("private-fixture-value")); assertFalse(observed.toString().contains("private-fixture-description"));
            int node = -1; JSONArray nodes = observed.getJSONArray("nodes");
            for (int i = 0; i < nodes.length(); i++) if (nodes.getJSONObject(i).optString("text").equalsIgnoreCase("Increment test counter")) node = nodes.getJSONObject(i).getInt("id");
            assertTrue("Missing fixture counter: " + observed, node > 0); final int target = node; final String observedSnapshot = observed.getString("snapshot");
            Future<JSONObject> click = worker.submit(() -> rpc(new JSONObject().put("op", "action").put("lease", lease).put("action", "click").put("snapshot", observedSnapshot).put("node", target)));
            assertEquals("action_dispatched", click.get(5, TimeUnit.SECONDS).getString("status")); Thread.sleep(500);
            JSONObject afterClick = rpc(new JSONObject().put("op", "observe").put("lease", lease));
            assertTrue(afterClick.toString().contains("Count: 1"));
            assertEquals("sensitive_screen", rpc(new JSONObject().put("op", "screenshot").put("lease", lease)).getString("status"));
            JSONObject passwordTree = rpc(new JSONObject().put("op", "observe").put("lease", lease));
            int hideNode = nodeWithText(passwordTree, "Hide sensitive fixture"); assertTrue(hideNode > 0);
            assertEquals("action_dispatched", rpc(new JSONObject().put("op", "action").put("lease", lease).put("action", "click").put("snapshot", passwordTree.getString("snapshot")).put("node", hideNode)).getString("status"));
            Thread.sleep(600);
            android.graphics.Bitmap reference = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
            assertNotNull(reference);
            try (OutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "phone-fixture-reference.png"))) { reference.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); }
            reference.recycle();
            JSONObject screenshot = screenshot(lease);
            assertEquals("full_display", screenshot.getString("captureMode")); assertEquals("image_pixels", screenshot.getString("coordinateSpace"));
            assertTrue(screenshot.optBoolean("maskedOwnOverlay")); assertFalse(screenshot.has("maskedOtherWindows"));
            android.graphics.Point display = new android.graphics.Point(); context.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(display);
            float scale = Math.min(1f, 1280f / Math.max(display.x, display.y));
            assertEquals((int)(display.x * scale), screenshot.getInt("width")); assertEquals((int)(display.y * scale), screenshot.getInt("height"));
            byte[] pixels = android.util.Base64.decode(screenshot.getString("image"), android.util.Base64.NO_WRAP);
            try (OutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "phone-model-screenshot.jpg"))) { output.write(pixels); }
            android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(pixels, 0, pixels.length); assertNotNull(decoded);
            int light = 0, samples = 0, darkest = 255, brightest = 0;
            for (int y = 0; y < decoded.getHeight(); y += 8) for (int x = 0; x < decoded.getWidth(); x += 8) {
                int color = decoded.getPixel(x, y), luminance = (android.graphics.Color.red(color) + android.graphics.Color.green(color) + android.graphics.Color.blue(color)) / 3;
                if (luminance > 160) light++; samples++; darkest = Math.min(darkest, luminance); brightest = Math.max(brightest, luminance);
            }
            assertTrue("Fixture screenshot was painted black: bright=" + light + "/" + samples + " range=" + darkest + ".." + brightest, light > samples * 0.6);
            assertTrue("Fixture screenshot lost all detail", brightest - darkest > 150);
            PhoneScreenPolicy.Box[] ownMask = new PhoneScreenPolicy.Box[1];
            instrumentation.runOnMainSync(() -> ownMask[0] = overlay().screenBounds()); assertNotNull(ownMask[0]);
            int maskX = Math.min(display.x - 1, (ownMask[0].left() + ownMask[0].right()) / 2), maskY = (ownMask[0].top() + ownMask[0].bottom()) / 2;
            JSONObject blocked = at("tap", screenshot, lease, maskX, maskY, display).put("target", "must not touch our overlay");
            int maskColor = decoded.getPixel(blocked.getInt("x"), blocked.getInt("y"));
            assertTrue("Own overlay is not masked", android.graphics.Color.red(maskColor) < 55 && android.graphics.Color.green(maskColor) < 55 && android.graphics.Color.blue(maskColor) < 55);
            decoded.recycle();
            assertEquals("own_overlay_blocked", rpc(blocked).getString("status"));
            assertEquals("invalid_coordinates", rpc(at("tap", screenshot, lease, 0, 0, display).put("x", screenshot.getInt("width")).put("target", "invalid")).getString("status"));
            assertEquals("stale_screenshot", rpc(at("tap", screenshot, lease, 0, 0, display).put("screenshot", "forged").put("target", "invalid")).getString("status"));
            try (OutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "phone-model-screenshot.jpg"))) { output.write(android.util.Base64.decode(screenshot.getString("image"), android.util.Base64.NO_WRAP)); }
            JSONObject imageTree = rpc(new JSONObject().put("op", "observe").put("lease", lease));
            JSONArray counterBounds = boundsWithText(imageTree, "Increment test counter");
            int counterX = counterBounds.getInt(0) + (counterBounds.getInt(2) - counterBounds.getInt(0)) / 5;
            int counterY = (counterBounds.getInt(1) + counterBounds.getInt(3)) / 2;
            JSONObject imageTap = at("tap", screenshot, lease, counterX, counterY, display).put("target", "Increment test counter");
            JSONObject tapped = rpc(imageTap); assertEquals(tapped.toString(), "action_dispatched", tapped.getString("status")); Thread.sleep(300);
            assertTrue(rpc(new JSONObject().put("op", "observe").put("lease", lease)).toString().contains("Count: 2"));
            assertEquals("stale_screenshot", rpc(imageTap).getString("status"));

            JSONObject hold = at("gesture", screenshot(lease), lease, counterX, counterY, display).put("gesture", "long_press").put("durationMs", 800).put("target", "Long press test counter");
            JSONObject held = rpc(hold); assertEquals(held.toString(), "action_dispatched", held.getString("status")); Thread.sleep(300);
            JSONObject gestureTree = rpc(new JSONObject().put("op", "observe").put("lease", lease)); assertTrue(gestureTree.toString().contains("Long presses: 1"));
            JSONArray padBounds = boundsWithText(gestureTree, "Gesture pad");
            int padX = padBounds.getInt(0) + (padBounds.getInt(2) - padBounds.getInt(0)) / 5;
            int padTop = padBounds.getInt(1) + 20, padBottom = padBounds.getInt(3) - 20;
            JSONObject swipeImage = screenshot(lease);
            JSONObject swipe = at("gesture", swipeImage, lease, padX, padBottom, display).put("gesture", "swipe").put("durationMs", 400).put("target", "Move along the test pad")
                .put("endX", padX * swipeImage.getInt("width") / display.x).put("endY", padTop * swipeImage.getInt("height") / display.y);
            JSONObject swiped = rpc(swipe); assertEquals(swiped.toString(), "action_dispatched", swiped.getString("status")); Thread.sleep(300);
            assertTrue(rpc(new JSONObject().put("op", "observe").put("lease", lease)).toString().contains("Swipes: 1"));

            // Explicit confirmation still restores the compact pill before dispatching a coordinate touch.
            JSONObject confirm = at("tap", screenshot(lease), lease, counterX, counterY, display).put("target", "Increment test counter").put("ask", true);
            Future<JSONObject> confirmed = worker.submit(() -> rpc(confirm)); Thread.sleep(400); assertFalse(confirmed.isDone());
            clickOverlayButton("确认这次操作");
            JSONObject confirmedResult = confirmed.get(5, TimeUnit.SECONDS); assertEquals(confirmedResult.toString(), "action_dispatched", confirmedResult.getString("status")); Thread.sleep(300);
            assertTrue(rpc(new JSONObject().put("op", "observe").put("lease", lease)).toString().contains("Count: 3"));
            afterClick = rpc(new JSONObject().put("op", "observe").put("lease", lease));
            int nextNode = -1; JSONArray afterNodes = afterClick.getJSONArray("nodes");
            for (int i = 0; i < afterNodes.length(); i++) if (afterNodes.getJSONObject(i).optString("text").equalsIgnoreCase("Send test message")) nextNode = afterNodes.getJSONObject(i).getInt("id");
            assertTrue(nextNode > 0); JSONArray sendBounds = boundsWithText(afterClick, "Send test message");
            JSONObject risky = at("gesture", screenshot(lease), lease, sendBounds.getInt(0) + (sendBounds.getInt(2) - sendBounds.getInt(0)) / 5,
                (sendBounds.getInt(1) + sendBounds.getInt(3)) / 2, display).put("gesture", "long_press").put("target", "Send test message").put("ask", false);
            Future<JSONObject> pendingClick = worker.submit(() -> rpc(risky));
            Thread.sleep(400); assertFalse("Consequential click must still wait even when ask=false", pendingClick.isDone());
            // Hit the actual native pill Stop button, without suppressing our AccessibilityService.
            android.app.UiAutomation automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.graphics.Point stopAt = new android.graphics.Point();
            instrumentation.runOnMainSync(() -> {
                try {
                    java.lang.reflect.Field overlayField = PhoneControl.class.getDeclaredField("overlay"); overlayField.setAccessible(true);
                    Object overlay = overlayField.get(control);
                    java.lang.reflect.Field viewField = PhoneOverlay.class.getDeclaredField("view"); viewField.setAccessible(true);
                    View stop = findText((View)viewField.get(overlay), "停止"); assertNotNull("Stop pill must be present", stop);
                    int[] location = new int[2]; stop.getLocationOnScreen(location);
                    stopAt.set(location[0] + stop.getWidth() / 2, location[1] + stop.getHeight() / 2);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            android.graphics.Bitmap image = automation.takeScreenshot(); assertNotNull(image);
            try (OutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "phone-action-confirmation.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); }
            image.recycle();
            long touchTime = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(touchTime, touchTime, MotionEvent.ACTION_DOWN, stopAt.x, stopAt.y, 0);
            MotionEvent up = MotionEvent.obtain(touchTime, touchTime + 60, MotionEvent.ACTION_UP, stopAt.x, stopAt.y, 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { assertTrue(automation.injectInputEvent(down, true)); assertTrue(automation.injectInputEvent(up, true)); }
            finally { down.recycle(); up.recycle(); }
            instrumentation.waitForIdleSync();
            assertEquals("user_cancelled", pendingClick.get(5, TimeUnit.SECONDS).getString("status"));
            assertEquals("user_cancelled", rpc(new JSONObject().put("op", "observe").put("lease", lease)).getString("status"));
            assertEquals("user_cancelled", rpc(new JSONObject().put("op", "action").put("lease", lease).put("action", "back")).getString("status"));
            assertEquals("user_cancelled", rpc(new JSONObject().put("op", "begin").put("purpose", "restart forbidden")).getString("status"));
            assertEquals("user_cancelled", rpc(new JSONObject().put("op", "screenshot").put("lease", lease)).getString("status"));
            assertEquals("user_cancelled", rpc(imageTap).getString("status")); assertEquals("user_cancelled", rpc(swipe).getString("status"));
            assertFalse("Stop must release the projection", PhoneCaptureService.available());
        }
    }
    private JSONObject screenshot(String lease) throws Exception {
        Thread.sleep(350);
        Future<JSONObject> capture = worker.submit(() -> rpc(new JSONObject().put("op", "screenshot").put("lease", lease)));
        long deadline = System.currentTimeMillis() + 6000;
        boolean approved = false;
        while (!capture.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            if (capture.isDone()) break;
            android.app.UiAutomation automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) continue;
            try {
                if (!"com.android.systemui".contentEquals(Objects.toString(root.getPackageName(), ""))) continue;
                List<android.view.accessibility.AccessibilityNodeInfo> labels = root.findAccessibilityNodeInfosByText("MengLuo Phone Probe");
                if (labels.isEmpty()) continue;
                for (android.view.accessibility.AccessibilityNodeInfo label : labels) label.recycle();
                assertTrue("Screen-sharing test requires separate explicit human approval", InstrumentationRegistry.getArguments().getString("allowScreenCapture", "false").equals("true"));
                assertFalse("A task must never prompt twice", approved);
                List<android.view.accessibility.AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId("android:id/button1");
                assertEquals(1, buttons.size()); android.view.accessibility.AccessibilityNodeInfo button = buttons.get(0);
                try {
                    String label = Objects.toString(button.getText(), "");
                    assertTrue("Unexpected system permission button: " + label, Set.of("立即开始", "Start now", "START NOW").contains(label));
                    assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)); approved = true;
                } finally { button.recycle(); }
            } finally { root.recycle(); }
        }
        JSONObject value = capture.get(6, TimeUnit.SECONDS);
        if (value.optString("status").equals("screen_capture_ready")) {
            assertTrue(PhoneCaptureService.available()); Thread.sleep(700);
            value = rpc(new JSONObject().put("op", "screenshot").put("lease", lease));
        }
        assertEquals(value.optString("status") + " code=" + value.optInt("androidCode"), "ok", value.getString("status"));
        return value;
    }
    private JSONObject at(String op, JSONObject screenshot, String lease, int x, int y, android.graphics.Point display) throws Exception {
        return new JSONObject().put("op", op).put("lease", lease).put("screenshot", screenshot.getString("screenshot"))
            .put("x", x * screenshot.getInt("width") / display.x).put("y", y * screenshot.getInt("height") / display.y);
    }
    private JSONArray boundsWithText(JSONObject tree, String text) throws Exception {
        JSONArray nodes = tree.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) if (nodes.getJSONObject(i).optString("text").equalsIgnoreCase(text)) return nodes.getJSONObject(i).getJSONArray("bounds");
        throw new AssertionError("Missing fixture element: " + text);
    }
    private PhoneOverlay overlay() {
        try { java.lang.reflect.Field field = PhoneControl.class.getDeclaredField("overlay"); field.setAccessible(true); return (PhoneOverlay)field.get(control); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void clickOverlayButton(String text) {
        android.graphics.Point at = new android.graphics.Point();
        instrumentation.runOnMainSync(() -> {
            try {
                java.lang.reflect.Field field = PhoneOverlay.class.getDeclaredField("view"); field.setAccessible(true);
                View button = findText((View)field.get(overlay()), text); assertNotNull(text, button);
                int[] location = new int[2]; button.getLocationOnScreen(location); at.set(location[0] + button.getWidth() / 2, location[1] + button.getHeight() / 2);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        android.app.UiAutomation automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        long time = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, at.x, at.y, 0), up = MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, at.x, at.y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(automation.injectInputEvent(down, true)); assertTrue(automation.injectInputEvent(up, true)); }
        finally { down.recycle(); up.recycle(); }
        instrumentation.waitForIdleSync();
    }
    private int nodeWithText(JSONObject tree, String text) throws Exception {
        JSONArray nodes = tree.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) if (nodes.getJSONObject(i).optString("text").equalsIgnoreCase(text)) return nodes.getJSONObject(i).getInt("id");
        return -1;
    }
    private View findText(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().equals(text)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) { View found = findText(((ViewGroup)view).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
}
