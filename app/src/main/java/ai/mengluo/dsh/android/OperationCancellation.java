package ai.mengluo.dsh.android;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.Executor;

/** One serial install owns one token; stopping it cannot disconnect a later install. */
final class OperationCancellation {
    static final class Stopped extends IOException {
        Stopped() { super("操作已停止"); }
    }
    private final Executor closer;
    private volatile boolean stopped;
    private HttpURLConnection connection;

    OperationCancellation(Executor closer) { this.closer = closer; }
    void check() throws Stopped { if (stopped) throw new Stopped(); }
    synchronized void watch(HttpURLConnection value) throws Stopped {
        check();
        connection = value;
    }
    synchronized void release(HttpURLConnection value) {
        if (connection == value) connection = null;
    }
    void cancel() {
        HttpURLConnection current;
        synchronized (this) {
            stopped = true;
            current = connection;
            connection = null;
        }
        // Android disconnect can wait on an in-flight socket; never do that on the UI thread.
        if (current != null) closer.execute(current::disconnect);
    }
}
