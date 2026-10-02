package ai.mengluo.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;
import static ai.mengluo.dsh.android.TaskNoticeState.Notice.*;

public class TaskNoticeStateTest {
    @Test public void onlyObservedRunningToIdleTransitionProducesCompletion() {
        TaskNoticeState state = new TaskNoticeState();
        assertEquals(NONE, state.status("s", false));
        assertEquals(NONE, state.status("s", true));
        assertEquals(FINISHED, state.status("s", false));
        assertEquals(NONE, state.status("s", false));
        state.status("s", true); state.disconnected();
        assertEquals(NONE, state.status("s", false));
    }
    @Test public void pendingRequestsDeduplicateAndLateReplyAcknowledgementCannotHideCompletion() {
        TaskNoticeState state = new TaskNoticeState(); state.status("s", true);
        assertEquals(WAITING, state.waiting("s", "approval1"));
        assertEquals(NONE, state.waiting("s", "approval1"));
        assertEquals(FINISHED, state.status("s", false));
        assertFalse(state.waitingFor("s")); assertNull(state.resolved("approval1"));
        state.disconnected();
        assertEquals(NONE, state.waiting("s", "approval1"));
        assertEquals("s", state.resolved("approval1")); assertFalse(state.waitingFor("s"));
        assertEquals(WAITING, state.waiting("s", "question2"));
    }
    @Test public void errorsSubagentsAndRemovedSessionsAreNotReportedAsCompleted() {
        TaskNoticeState state = new TaskNoticeState(); state.status("s", true); state.error("s");
        assertEquals(NONE, state.status("s", false));
        state.status("s", true); assertEquals(FINISHED, state.status("s", false));
        state.summary("child", true); state.status("child", true);
        assertEquals(NONE, state.status("child", false));
        // A subagent requesting permission still needs the human's attention.
        assertEquals(WAITING, state.waiting("child", "permission"));
        state.removed("child"); assertFalse(state.waitingFor("child"));
        assertEquals(NONE, state.status("child", false));
    }
    @Test public void runtimeChangesResetReplayHistoryAndStateRemainsBounded() {
        TaskNoticeState state = new TaskNoticeState(); state.waiting("old", "r"); state.clear();
        assertEquals(WAITING, state.waiting("new", "r"));
        state.status("oldest", true);
        for (int i = 0; i < 1000; i++) { state.status("s" + i, true); state.waiting("s" + i, "r" + i); }
        assertEquals(NONE, state.status("oldest", false));
        assertNull(state.resolved("r0"));
    }
}
