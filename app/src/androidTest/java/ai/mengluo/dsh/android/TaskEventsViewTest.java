package ai.mengluo.dsh.android;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.*;
import android.service.notification.StatusBarNotification;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real loopback WebSocket + WebView + notification drawer, without a model, chat data or approval replies. */
@RunWith(AndroidJUnit4.class)
public class TaskEventsViewTest {
    private Context context;
    private SharedPreferences preferences;
    private TaskNotifications notices;
    private NotificationManager manager;
    private final String session = "notification-test-" + UUID.randomUUID();

    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences(session, 0);
        notices = new TaskNotifications(new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        });
        manager = context.getSystemService(NotificationManager.class);
        Assume.assumeTrue("Enable notifications on this test device before running this suite", notices.allowed());
    }
    @After public void cleanup() {
        if (notices != null) { notices.runtime(null); notices.cancel(session); }
        if (preferences != null) preferences.edit().clear().commit();
    }
    @Test public void actualSocketEventsNotifyWhileActivityIsInBackgroundAndNeverAnswerQuestions() throws Exception {
        try (WireFixture wire = new WireFixture(); ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<WebView> view = new AtomicReference<>(); AtomicReference<WebTaskEvents> bridge = new AtomicReference<>();
            try {
                scenario.onActivity(activity -> {
                    WebView web = new WebView(activity); web.getSettings().setJavaScriptEnabled(true);
                    FrameLayout frame = activity.findViewById(android.R.id.content); frame.addView(web, new FrameLayout.LayoutParams(100, 100));
                    WebTaskEvents events = new WebTaskEvents(web, () -> wire.origin(), notices);
                    try { assertTrue(events.install(wire.origin())); } catch (IOException error) { throw new AssertionError(error); }
                    view.set(web); bridge.set(events); web.loadUrl(wire.origin());
                });
                assertTrue("WebView must open its existing official-route socket", wire.connected.await(10, TimeUnit.SECONDS));
                wire.frame("{\"type\":\"ready\",\"clientId\":\"test-client\"}");
                wire.frame("{\"type\":\"emit\",\"event\":\"api-session/status\",\"args\":[\"" + session + "\",true]}");
                scenario.moveToState(Lifecycle.State.CREATED); // onStop, page remains owned by the app.
                wire.frame("{\"type\":\"emit\",\"event\":\"api-session/status\",\"args\":[\"" + session + "\",false]}");
                Notification done = awaitNotice("本轮任务已结束").getNotification();
                assertEquals(Notification.VISIBILITY_PRIVATE, done.visibility);
                assertNotNull(done.contentIntent); assertEquals(0, done.actions == null ? 0 : done.actions.length);
                assertFalse(done.extras.toString().contains("token="));
                notices.cancel(session);
                wire.frame("{\"type\":\"waterfall\",\"event\":\"approval/request\",\"agentId\":\"" + session + "\",\"eventId\":\"permission-1\",\"request\":{\"secret\":\"never-in-notice\"}}");
                StatusBarNotification pending = awaitNotice("需要你确认");
                assertFalse(pending.getNotification().extras.toString().contains("never-in-notice"));
                // The observer has not called send(), opened a second socket, or posted an answer.
                assertEquals(1, wire.sockets.get()); assertEquals(0, wire.clientFrames.get()); assertEquals(0, wire.rpcRequests.get());
                scenario.moveToState(Lifecycle.State.RESUMED);
                // Simulated user response against the isolated fixture: current Harness does
                // not send a cancellation frame back to the client that submitted the answer.
                scenario.onActivity(activity -> view.get().evaluateJavascript("fetch('/api/$events/result',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:'fixture-rpc',method:'$events/result',payload:{args:{clientId:'test-client',eventId:'permission-1',outcome:{kind:'result',value:'test-only'}}}})})", null));
                awaitGone(); assertEquals(1, wire.rpcRequests.get());
                scenario.onActivity(activity -> notices.enabled(false));
                wire.frame("{\"type\":\"waterfall\",\"event\":\"user-questions/request\",\"agentId\":\"" + session + "\",\"eventId\":\"question-2\"}");
                Thread.sleep(300); assertNull(find());
            } finally {
                scenario.moveToState(Lifecycle.State.RESUMED);
                scenario.onActivity(activity -> { if (bridge.get() != null) bridge.get().close(); if (view.get() != null) { ((FrameLayout) view.get().getParent()).removeView(view.get()); view.get().destroy(); } });
            }
        }
    }
    private StatusBarNotification find() {
        for (StatusBarNotification value : manager.getActiveNotifications()) if (("task:" + session).equals(value.getTag())) return value;
        return null;
    }
    private StatusBarNotification awaitNotice(String text) throws Exception {
        for (int i = 0; i < 100; i++) {
            StatusBarNotification notice = find();
            if (notice != null && notice.getNotification().extras.getString(Notification.EXTRA_TITLE, "").contains(text)) return notice;
            Thread.sleep(50);
        }
        throw new AssertionError("Notification missing: " + text);
    }
    private void awaitGone() throws Exception {
        for (int i = 0; i < 100; i++) { if (find() == null) return; Thread.sleep(50); }
        throw new AssertionError("Resolved request notification was not removed");
    }

    private static final class WireFixture implements AutoCloseable {
        final ServerSocket server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicInteger sockets = new AtomicInteger(), clientFrames = new AtomicInteger(), rpcRequests = new AtomicInteger();
        final List<Socket> clients = new CopyOnWriteArrayList<>();
        volatile OutputStream downlink;
        WireFixture() throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            workers.execute(() -> { while (!server.isClosed()) try { Socket socket = server.accept(); clients.add(socket); workers.execute(() -> serve(socket)); } catch (IOException closed) { break; } });
        }
        String origin() { return "http://127.0.0.1:" + server.getLocalPort() + "/"; }
        void serve(Socket socket) {
            try {
                socket.setSoTimeout(15000); InputStream in = socket.getInputStream(); ByteArrayOutputStream headers = new ByteArrayOutputStream();
                while (headers.size() < 16384) {
                    int value = in.read(); if (value < 0) return; headers.write(value);
                    if (new String(headers.toByteArray(), StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) break;
                }
                String request = new String(headers.toByteArray(), StandardCharsets.ISO_8859_1);
                OutputStream out = socket.getOutputStream();
                if (request.startsWith("GET /api/remote.mux ")) {
                    String key = Arrays.stream(request.split("\r\n")).filter(line -> line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":", 2)[1].trim();
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)); out.flush();
                    downlink = out; sockets.incrementAndGet(); connected.countDown();
                    while (in.read() >= 0) clientFrames.incrementAndGet();
                } else {
                    if (request.startsWith("POST ")) rpcRequests.incrementAndGet();
                    boolean reply = request.startsWith("POST /api/$events/result ");
                    if (reply) {
                        String length = Arrays.stream(request.split("\r\n")).filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-length:")).findFirst().orElse("Content-Length: 0").split(":", 2)[1].trim();
                        int remaining = Integer.parseInt(length);
                        if (remaining > 8192) throw new IOException("Test request too large");
                        while (remaining-- > 0 && in.read() >= 0) { }
                    }
                    byte[] body = (reply ? "{\"type\":\"server-response\",\"rpcId\":\"fixture-rpc\",\"result\":{\"ok\":true}}"
                        : "<!doctype html><title>Test</title><script>window.socket=new WebSocket('ws://'+location.host+'/api/remote.mux');</script>").getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + (reply ? "application/json" : "text/html") + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)); out.write(body); out.flush(); socket.close();
                }
            } catch (Exception ignored) { /* Closing this isolated fixture interrupts its handlers. */ }
        }
        synchronized void frame(String value) throws IOException {
            byte[] bytes = ("{\"type\":\"item\",\"streamId\":\"events\",\"value\":" + value + "}").getBytes(StandardCharsets.UTF_8);
            downlink.write(0x81);
            if (bytes.length < 126) downlink.write(bytes.length);
            else { downlink.write(126); downlink.write(bytes.length >> 8); downlink.write(bytes.length & 255); }
            downlink.write(bytes); downlink.flush();
        }
        @Override public void close() throws IOException { server.close(); for (Socket socket : clients) socket.close(); workers.shutdownNow(); }
    }
}
