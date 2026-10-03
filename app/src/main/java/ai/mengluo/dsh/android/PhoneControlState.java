package ai.mengluo.dsh.android;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** In-memory capability lease. No grant survives process death, cancellation or a permission change. */
final class PhoneControlState {
    static final long HEARTBEAT_MS = 30_000, TASK_MS = 10 * 60_000;
    private final Map<String, String> closed = new HashMap<>();
    private String owner = "", lease = "", phase = "idle";
    private long started, heartbeat;

    synchronized String begin(String key, long now) {
        if (!key.matches("[a-f0-9]{64}")) return "invalid_owner";
        if (closed.containsKey(key)) return closed.get(key);
        if (!owner.isEmpty()) return owner.equals(key) ? phase : "busy";
        // Never evict a revoked grant and accidentally permit the same conversation turn again.
        if (closed.size() >= 512) return "session_limit";
        owner = key; lease = UUID.randomUUID().toString(); phase = "preparing";
        started = heartbeat = now;
        return phase;
    }
    synchronized boolean approve(String key, String expectedLease) {
        if (!matches(key, expectedLease) || !phase.equals("preparing")) return false;
        phase = "active"; return true;
    }
    synchronized boolean allowed(String key, String expectedLease) { return phase.equals("active") && matches(key, expectedLease); }
    synchronized boolean matches(String key, String expectedLease) { return !owner.isEmpty() && owner.equals(key) && lease.equals(expectedLease); }
    synchronized boolean live() { return !owner.isEmpty(); }
    synchronized String owner() { return owner; }
    synchronized String lease() { return lease; }
    synchronized String phase(String key) { return owner.equals(key) && !key.isEmpty() ? phase : closed.getOrDefault(key, "idle"); }
    synchronized void touch(String key, long now) { if (owner.equals(key)) heartbeat = now; }
    synchronized String expired(long now) {
        if (!live()) return "";
        return now - started >= TASK_MS ? "session_expired" : now - heartbeat >= HEARTBEAT_MS ? "bridge_disconnected" : "";
    }
    synchronized String close(String reason) {
        if (owner.isEmpty()) return "";
        String previous = owner; closed.put(previous, reason); owner = ""; lease = ""; phase = "idle";
        return previous;
    }
}
