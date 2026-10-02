package ai.mengluo.dsh.android;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, payload-free state. Replays and initially idle sessions never create completion notices. */
final class TaskNoticeState {
    enum Notice { NONE, FINISHED, WAITING }
    private static final int LIMIT = 512;
    private static final class Session { boolean running, failed, subagent; }
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Map<String, Boolean> seenRequests = new LinkedHashMap<>();
    private Session session(String id) {
        Session state = sessions.computeIfAbsent(id, ignored -> new Session());
        trim(sessions); return state;
    }
    Notice status(String id, boolean running) {
        Session state = session(id);
        boolean done = state.running && !running && !state.failed && !state.subagent;
        state.running = running;
        if (running) state.failed = false;
        // Official idle means no driver remains active. It may arrive before an HTTP
        // acknowledgement; stale pending bookkeeping must not swallow that transition.
        else pending.values().removeIf(id::equals);
        return done ? Notice.FINISHED : Notice.NONE;
    }
    void summary(String id, boolean subagent) { session(id).subagent = subagent; }
    void error(String id) { session(id).failed = true; }
    void removed(String id) { sessions.remove(id); pending.values().removeIf(id::equals); }
    Notice waiting(String id, String request) {
        pending.put(request, id); trim(pending);
        boolean replay = seenRequests.put(request, true) != null; trim(seenRequests);
        return replay ? Notice.NONE : Notice.WAITING;
    }
    String resolved(String request) { return pending.remove(request); }
    boolean waitingFor(String session) { return pending.containsValue(session); }
    // Dropped streams cannot prove what happened offline; retain request IDs to avoid replay spam.
    void disconnected() { sessions.clear(); pending.clear(); }
    void clear() { disconnected(); seenRequests.clear(); }
    private static void trim(Map<?, ?> map) { while (map.size() > LIMIT) map.remove(map.keySet().iterator().next()); }
}
