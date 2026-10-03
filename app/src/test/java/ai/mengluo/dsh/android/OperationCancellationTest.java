package ai.mengluo.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class OperationCancellationTest {
    private static class Connection extends HttpURLConnection {
        volatile boolean disconnected;
        final int status;
        Connection(int status) throws Exception { super(new URL("https://nodejs.org/test")); this.status = status; }
        @Override public int getResponseCode() throws IOException { return status; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? "/next" : null; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
    @Test public void preCancelledRequestNeverOpensNetwork() throws Exception {
        OperationCancellation cancellation = new OperationCancellation(Runnable::run); cancellation.cancel();
        assertThrows(OperationCancellation.Stopped.class, () -> UpdateHttp.open("https://nodejs.org/test", url -> true, cancellation,
            url -> { fail("Must not connect after cancellation"); return null; }));
    }
    @Test(timeout = 5000) public void cancelDisconnectsPendingHeadersOffCallerThread() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(), closer = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), closed = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        Connection connection = new Connection(200) {
            @Override public int getResponseCode() throws IOException {
                entered.countDown();
                try { if (!closed.await(2, TimeUnit.SECONDS)) throw new IOException("Fixture timeout"); }
                catch (InterruptedException error) { throw new IOException(error); }
                throw new IOException("Socket closed");
            }
            @Override public void disconnect() { assertNotSame(caller, Thread.currentThread()); super.disconnect(); closed.countDown(); }
        };
        OperationCancellation cancellation = new OperationCancellation(closer);
        try {
            Future<?> result = worker.submit(() -> {
                assertThrows(IOException.class, () -> UpdateHttp.open("https://nodejs.org/test", url -> true, cancellation, url -> connection));
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS)); cancellation.cancel();
            result.get(2, TimeUnit.SECONDS); assertTrue(connection.disconnected);
            assertThrows(OperationCancellation.Stopped.class, cancellation::check);
        } finally { closer.shutdownNow(); worker.shutdownNow(); }
    }
    @Test public void redirectIsDisconnectedAndOnlyCurrentConnectionIsCancelled() throws Exception {
        Connection first = new Connection(302), second = new Connection(200);
        AtomicInteger opened = new AtomicInteger();
        OperationCancellation cancellation = new OperationCancellation(Runnable::run);
        assertSame(second, UpdateHttp.open("https://nodejs.org/test", url -> true, cancellation,
            url -> opened.getAndIncrement() == 0 ? first : second));
        assertTrue(first.disconnected); assertFalse(second.disconnected);
        cancellation.release(first); cancellation.cancel();
        assertTrue(second.disconnected); assertEquals(2, opened.get());
    }
    @Test public void stoppingOldInstallDoesNotTouchNewInstall() throws Exception {
        Connection first = new Connection(200), second = new Connection(200);
        OperationCancellation old = new OperationCancellation(Runnable::run), current = new OperationCancellation(Runnable::run);
        old.watch(first); old.release(first); current.watch(second); old.cancel();
        current.check(); assertFalse(second.disconnected); assertFalse(first.disconnected);
    }
    @Test public void cancellationDuringConnectionCreationClosesWithoutSending() throws Exception {
        Connection connection = new Connection(200) {
            @Override public int getResponseCode() { fail("Must not send request"); return 200; }
        };
        OperationCancellation cancellation = new OperationCancellation(Runnable::run);
        assertThrows(OperationCancellation.Stopped.class, () -> UpdateHttp.open("https://nodejs.org/test", url -> true, cancellation,
            url -> { cancellation.cancel(); return connection; }));
        assertTrue(connection.disconnected);
    }
}
