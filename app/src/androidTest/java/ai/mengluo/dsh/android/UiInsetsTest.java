package ai.mengluo.dsh.android;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synthetic insets exercise gesture/three-button/IME cases even on emulators hiding their navbar. */
@RunWith(AndroidJUnit4.class)
public class UiInsetsTest {
    @Test public void rootAndFloatingMenuStayInSafeArea() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            checkRoot(scenario, Insets.of(0, 32, 0, 24), Insets.of(16, 0, 16, 48), 0);
            checkRoot(scenario, Insets.of(0, 32, 0, 24), Insets.of(16, 0, 16, 48), 300);
            // Landscape three-button bar on the right; avoid assuming navigation is always at bottom.
            checkRoot(scenario, Insets.of(0, 24, 72, 0), Insets.NONE, 0);
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content).findViewWithTag("shell-frame");
                ViewCompat.requestApplyInsets(root);
            });
        }
    }

    private static void checkRoot(ActivityScenario<MainActivity> scenario, Insets bars, Insets gestures, int keyboard) {
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), bars)
            .setInsets(WindowInsetsCompat.Type.systemGestures(), gestures)
            .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, gestures.bottom))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard)).build();
        int bottom = Math.max(bars.bottom, Math.max(gestures.bottom, keyboard));
        scenario.onActivity(activity -> {
            FrameLayout root = activity.findViewById(android.R.id.content).findViewWithTag("shell-frame");
            ViewCompat.dispatchApplyWindowInsets(root, insets);
            ViewCompat.dispatchApplyWindowInsets(root, insets);
            assertEquals("Insets must not accumulate", bottom, root.getPaddingBottom());
            assertEquals(bars.right, root.getPaddingRight());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            FrameLayout root = activity.findViewById(android.R.id.content).findViewWithTag("shell-frame");
            View ball = root.findViewWithTag("menu-ball");
            assertTrue(ball.getY() >= root.getPaddingTop());
            assertTrue(ball.getY() + ball.getHeight() <= root.getHeight() - root.getPaddingBottom());
            float visibleLeft = Math.max(ball.getX(), root.getPaddingLeft());
            float visibleRight = Math.min(ball.getX() + ball.getWidth(), root.getWidth() - root.getPaddingRight());
            assertTrue("Collapsed handle stays usable inside the safe viewport", visibleRight - visibleLeft >= 23 * activity.getResources().getDisplayMetrics().density);
            View home = root.findViewWithTag("home");
            assertEquals(root.getHeight() - root.getPaddingTop() - root.getPaddingBottom(), home.getHeight());
        });
    }

    @Test public void bottomSheetPaddingIsIdempotentAcrossOrientations() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            for (int orientation : new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                scenario.onActivity(activity -> activity.setRequestedOrientation(orientation));
                waitForOrientation(scenario, orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE);
                snapshot(orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? "home-portrait" : "home-landscape");
                scenario.onActivity(activity -> {
                    activity.findViewById(android.R.id.content).findViewWithTag("menu-ball").performClick();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                snapshot(orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? "menu-portrait" : "menu-landscape");
                scenario.onActivity(activity -> {
                    View content = null;
                    for (View window : WindowInspector.getGlobalWindowViews()) {
                        View candidate = window.findViewWithTag("menu-content");
                        if (candidate != null) content = candidate;
                    }
                    assertNotNull("Floating icon opens the modern bottom menu", content);
                    View scroll = (View) content.getParent();
                    int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
                    WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                        .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 48)).build();
                    ViewCompat.dispatchApplyWindowInsets(scroll, insets);
                    ViewCompat.dispatchApplyWindowInsets(scroll, insets);
                    assertEquals(padding + 48, content.getPaddingBottom());
                    assertEquals(padding, content.getPaddingTop());
                    // Use the real back action so the dialog is removed before changing orientation.
                });
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
        }
    }

    @Test public void darkThemeKeepsSystemBarsReadable() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                activity.getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            });
            waitForOrientation(scenario, Configuration.ORIENTATION_PORTRAIT);
            scenario.onActivity(activity -> {
                assertFalse(activity.getResources().getBoolean(R.bool.light_system_bars));
                View root = activity.findViewById(android.R.id.content).findViewWithTag("shell-frame");
                assertFalse(androidx.core.view.WindowCompat.getInsetsController(activity.getWindow(), root).isAppearanceLightNavigationBars());
            });
            snapshot("home-dark");
        }
    }

    @Test public void floatingBallCollapsesAndStillOpensMenu() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Thread.sleep(2600);
            scenario.onActivity(activity -> {
                FrameLayout root = activity.findViewById(android.R.id.content).findViewWithTag("shell-frame");
                View ball = root.findViewWithTag("menu-ball");
                float visible = Math.min(ball.getX() + ball.getWidth(), root.getWidth() - root.getPaddingRight()) - Math.max(ball.getX(), root.getPaddingLeft());
                float expected = 24 * activity.getResources().getDisplayMetrics().density;
                assertEquals(expected, visible, 2f);
                assertEquals(.65f, ball.getAlpha(), .01f);
                assertEquals(1, root.getSystemGestureExclusionRects().size());
            });
            snapshot("menu-ball-collapsed");
            scenario.recreate();
            scenario.onActivity(activity -> {
                View ball = activity.findViewById(android.R.id.content).findViewWithTag("menu-ball");
                assertEquals(.65f, ball.getAlpha(), .01f);
                ball.performClick();
                assertEquals(1f, ball.getAlpha(), .01f);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            boolean found = false;
            for (View window : WindowInspector.getGlobalWindowViews()) if (window.findViewWithTag("menu-content") != null) found = true;
            assertTrue("A single tap still opens the menu", found);
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        }
    }

    private static void waitForOrientation(ActivityScenario<MainActivity> scenario, int orientation) throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> ready.set(activity.getResources().getConfiguration().orientation == orientation));
            if (ready.get()) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("Activity did not rotate");
    }

    private static void snapshot(String name) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.waitForIdleSync();
        // Give only the window transition time to settle; no runtime/network work is waiting here.
        Thread.sleep(350);
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        File directory = new File(instrumentation.getTargetContext().getCacheDir(), "ui-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { image.recycle(); }
    }
}
