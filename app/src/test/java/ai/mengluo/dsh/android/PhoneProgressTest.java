package ai.mengluo.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneProgressTest {
    @Test public void summariesAreBoundedAndClearedBetweenTasks() {
        PhoneProgress progress = new PhoneProgress(); progress.begin("打开测试页面");
        for (int i = 0; i < 40; i++) {
            progress.message("m" + i, "assistant", "reply-" + i);
            progress.step("step-" + i);
        }
        assertFalse(progress.conversation().contains("reply-19"));
        assertTrue(progress.conversation().contains("reply-39"));
        assertFalse(progress.steps().contains("step-19"));
        assertTrue(progress.caption().contains("reply-39"));
        progress.begin("新任务");
        assertFalse(progress.conversation().contains("reply-"));
        assertFalse(progress.caption().contains("reply-"));
        assertFalse(progress.steps().contains("step-"));
    }
    @Test public void onlyPublicRolesAndPlainBoundedTextAreAccepted() {
        PhoneProgress progress = new PhoneProgress();
        progress.message("secret", "reasoning", "hidden");
        progress.message("system", "system", "prompt");
        assertFalse(progress.conversation().contains("hidden"));
        assertFalse(progress.conversation().contains("prompt"));
        progress.message("reply", "assistant", "a".repeat(900));
        assertTrue(progress.conversation().length() <= 803);
        progress.message("reply", "assistant", "changed\u0000\nline");
        assertEquals("AI：changed\nline", progress.conversation());
        assertEquals("a…", PhoneProgress.limit("a😀bc", 3));
        progress.clear(); assertEquals("等待下一步\n", progress.caption());
    }
}
