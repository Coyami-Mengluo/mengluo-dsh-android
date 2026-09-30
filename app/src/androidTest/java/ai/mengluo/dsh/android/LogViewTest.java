package ai.mengluo.dsh.android;

import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LogViewTest {
    @Test public void opensAtLatestLineAndDoesNotPullReaderBackDown() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.logs(() -> history("latest-on-open")));
            idle();
            scenario.onActivity(activity -> {
                ScrollView scroll = logScroll();
                assertAtBottom(scroll);
                TextView text = scroll.findViewWithTag("runtime-log-text");
                assertTrue(text.isTextSelectable());
                assertTrue(text.getText().toString().endsWith("latest-on-open"));
                scroll.scrollTo(0, 0);
                // A new layout (e.g. selection, keyboard or window change) must not force the tail.
                scroll.requestLayout();
            });
            idle();
            scenario.onActivity(activity -> {
                assertEquals(0, logScroll().getScrollY());
                close();
            });
        }
    }

    @Test public void refreshReadsNewSnapshotAndReturnsToLatestLine() {
        AtomicReference<String> snapshot = new AtomicReference<>(history("before-refresh"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.logs(snapshot::get));
            idle();
            scenario.onActivity(activity -> {
                ScrollView old = logScroll();
                old.scrollTo(0, 0);
                snapshot.set(history("after-refresh"));
                Button refresh = old.getRootView().findViewById(android.R.id.button2);
                assertEquals("刷新", refresh.getText().toString());
                refresh.performClick();
            });
            idle();
            scenario.onActivity(activity -> {
                ScrollView scroll = logScroll();
                assertAtBottom(scroll);
                TextView text = scroll.findViewWithTag("runtime-log-text");
                assertTrue(text.getText().toString().endsWith("after-refresh"));
                assertFalse(text.getText().toString().contains("before-refresh"));
                close();
            });
        }
    }

    @Test public void emptyAndShortLogsRemainReadable() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            for (String value : new String[]{"", "只有一行日志"}) {
                scenario.onActivity(activity -> activity.logs(() -> value));
                idle();
                scenario.onActivity(activity -> {
                    ScrollView scroll = logScroll();
                    assertEquals(0, scroll.getScrollY());
                    assertEquals(value, ((TextView) scroll.findViewWithTag("runtime-log-text")).getText().toString());
                    close();
                });
                idle();
            }
        }
    }

    private static String history(String last) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 240; i++) text.append("测试日志 ").append(i).append("：这是一条足够长、会在窄屏换行的日志记录，验证定位不依赖固定行高。\n");
        return text.append(last).toString();
    }
    private static void assertAtBottom(ScrollView scroll) {
        assertTrue("Fixture must extend beyond the dialog", scroll.getScrollY() > 0);
        assertFalse("The newest line must be visible", scroll.canScrollVertically(1));
        assertTrue(scroll.canScrollVertically(-1));
    }
    private static ScrollView logScroll() {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            ScrollView scroll = root.findViewWithTag("runtime-log-scroll");
            if (scroll != null && scroll.isAttachedToWindow()) return scroll;
        }
        throw new AssertionError("Log window missing");
    }
    private static void close() { logScroll().getRootView().findViewById(android.R.id.button1).performClick(); }
    private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
}
