package ai.mengluo.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneControlStateTest {
    private final String a = "a".repeat(64), b = "b".repeat(64);
    @Test public void preparingTaskCannotActUntilNativeReadinessCheckCompletes() {
        PhoneControlState state = new PhoneControlState();
        assertEquals("preparing", state.begin(a, 100)); String lease = state.lease();
        assertFalse(state.allowed(a, lease)); assertFalse(state.approve(b, lease));
        assertTrue(state.approve(a, lease)); assertTrue(state.allowed(a, lease)); assertFalse(state.allowed(a, "wrong"));
    }
    @Test public void stopRevokesPendingAndActiveAndCannotBeRegrantedOnSameTurn() {
        PhoneControlState state = new PhoneControlState();
        state.begin(a, 0); String old = state.lease(); state.close("user_cancelled");
        assertFalse(state.approve(a, old)); assertEquals("user_cancelled", state.begin(a, 2));
        state.begin(b, 3); String next = state.lease(); assertNotEquals(old, next); state.approve(b, next);
        assertFalse(state.allowed(a, old)); assertTrue(state.allowed(b, next));
        state.close("permission_lost"); assertFalse(state.allowed(b, next)); assertEquals("permission_lost", state.begin(b, 4));
    }
    @Test public void heartbeatCannotExtendAbsoluteTaskDeadline() {
        PhoneControlState state = new PhoneControlState(); state.begin(a, 100);
        assertEquals("bridge_disconnected", state.expired(100 + PhoneControlState.HEARTBEAT_MS));
        state.touch(a, 100 + PhoneControlState.HEARTBEAT_MS); assertEquals("", state.expired(101 + PhoneControlState.HEARTBEAT_MS));
        state.touch(a, 100 + PhoneControlState.TASK_MS); assertEquals("session_expired", state.expired(100 + PhoneControlState.TASK_MS));
    }
    @Test public void concurrentSessionsAndSpoofedHeartbeatsAreDenied() {
        PhoneControlState state = new PhoneControlState(); assertEquals("invalid_owner", state.begin("model-choice", 0));
        state.begin(a, 100); assertEquals("busy", state.begin(b, 101));
        state.touch(b, 20000); assertEquals("bridge_disconnected", state.expired(30100));
    }
    @Test public void completedTaskCannotReopenAndNewProcessHasNoGrant() {
        PhoneControlState state = new PhoneControlState(); state.begin(a, 0); String lease = state.lease(); state.approve(a, lease);
        state.close("completed"); assertEquals("completed", state.begin(a, 1)); assertFalse(new PhoneControlState().allowed(a, lease));
    }
    @Test public void unsupportedRuntimeSkipsOptionalPluginAndBridgeTokenIsRedacted() {
        assertTrue(PhoneControl.supported("0.2.0-rc.2")); assertFalse(PhoneControl.supported("0.1.5-rc.2"));
        assertFalse(PhoneControl.supported("1.0.0")); assertFalse(PhoneControl.supported("unexpected"));
        assertFalse(RuntimePolicy.redact("ML_PHONE_TOKEN=private-bridge-secret").contains("private-bridge-secret"));
    }
    @Test public void launcherPatchPrecedesWebFlagsAndOldRuntimeRemainsUnchanged() {
        java.util.List<String> enabled = PhoneControl.webCommand("/fixture/bin.js", 47821, true);
        assertEquals(java.util.List.of("/opt/node/bin/node", "--expose-internals", "/fixture/bin.js", "web", "--patch", "/opt/mengluo-phone/patch.yml", "--host", "127.0.0.1", "--port", "47821", "--no-open"), enabled);
        assertEquals(java.util.List.of("/opt/node/bin/node", "--expose-internals", "/fixture/bin.js", "web", "--host", "127.0.0.1", "--port", "47821", "--no-open"), PhoneControl.webCommand("/fixture/bin.js", 47821, false));
    }
}
