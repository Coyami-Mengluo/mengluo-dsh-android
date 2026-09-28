package ai.mengluo.dsh.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.Test;
import static org.junit.Assert.*;

public class HarnessPageTest {
    @Test public void followsTokenCookieExchangeWithinLoopbackOnly() throws Exception {
        try (Server server = new Server(request -> request.startsWith("GET /?token=test ")
            ? "303 See Other\r\nSet-Cookie: session=test; HttpOnly; Path=/\r\nLocation: /\r\nContent-Length: 0\r\n\r\n"
            : (request.contains("Cookie: session=test") ? "200 OK" : "401 Unauthorized")
                + "\r\nContent-Length: 31\r\n\r\n<title>DeepSeek Harness</title>")) {
            HarnessPage.check(server.base() + "/?token=test");
        }
    }
    @Test public void rejectsRedirectsToOtherOriginsAndRedirectLoops() throws Exception {
        try (Server server = new Server(request -> "303 See Other\r\nLocation: "
            + (request.startsWith("GET /outside ") ? "http://127.0.0.1:1/" : "/loop") + "\r\nContent-Length: 0\r\n\r\n")) {
            String base = server.base();
            assertTrue(assertThrows(IOException.class, () -> HarnessPage.check(base + "/outside")).getMessage().contains("范围"));
            assertTrue(assertThrows(IOException.class, () -> HarnessPage.check(base + "/loop")).getMessage().contains("次数"));
        }
    }
    private static final class Server implements AutoCloseable {
        private final ServerSocket socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        private final ExecutorService thread = Executors.newSingleThreadExecutor();
        Server(Function<String, String> handler) throws IOException {
            thread.execute(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        client.setSoTimeout(5_000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder headers = new StringBuilder(); String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) headers.append(line).append('\n');
                        client.getOutputStream().write(("HTTP/1.1 " + handler.apply(headers.toString())).getBytes(StandardCharsets.UTF_8));
                    } catch (IOException error) { if (!socket.isClosed()) throw new RuntimeException(error); }
                }
            });
        }
        String base() { return "http://127.0.0.1:" + socket.getLocalPort(); }
        @Override public void close() throws IOException { socket.close(); thread.shutdownNow(); }
    }
}
