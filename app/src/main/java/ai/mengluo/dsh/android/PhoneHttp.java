package ai.mengluo.dsh.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Tiny private loopback RPC transport. No CORS, redirects, URLs, GET requests or unbounded reads. */
final class PhoneHttp implements AutoCloseable {
    private final ServerSocket server;
    private final String token;
    private final Function<String, String> dispatch;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), r -> { Thread t = new Thread(r, "phone-rpc"); t.setDaemon(true); return t; });
    PhoneHttp(String token, Function<String, String> dispatch) throws IOException {
        this.token = token; this.dispatch = dispatch;
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread listener = new Thread(this::accept, "phone-rpc-listen"); listener.setDaemon(true); listener.start();
    }
    int port() { return server.getLocalPort(); }
    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept(); clients.add(socket);
                try { workers.execute(() -> serve(socket)); }
                catch (RejectedExecutionException error) { clients.remove(socket); socket.close(); }
            } catch (IOException error) { if (server.isClosed()) return; }
        }
    }
    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(4_000);
            String request = read(socket.getInputStream(), token);
            String response = dispatch.apply(request);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body); output.flush();
        } catch (Exception ignored) { /* Never log capabilities, request bodies or screen content. */ }
        finally { clients.remove(socket); }
    }
    static String read(InputStream input, String token) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream(); int suffix = 0;
        while (suffix != 0x0d0a0d0a) {
            int value = input.read(); if (value < 0 || header.size() >= 8192) throw new IOException("Invalid RPC header");
            header.write(value); suffix = (suffix << 8) | value;
        }
        String[] lines = new String(header.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
        if (!lines[0].equals("POST /v1 HTTP/1.1")) throw new IOException("Invalid RPC method");
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':'); if (colon <= 0) throw new IOException("Invalid RPC header");
            String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(key, lines[i].substring(colon + 1).trim()) != null) throw new IOException("Duplicate RPC header");
        }
        if (headers.containsKey("origin") || headers.containsKey("transfer-encoding")
            || !headers.getOrDefault("content-type", "").equals("application/json")
            || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), headers.getOrDefault("authorization", "").getBytes(StandardCharsets.UTF_8)))
            throw new IOException("Unauthenticated RPC");
        int length;
        try { length = Integer.parseInt(headers.getOrDefault("content-length", "-1")); } catch (NumberFormatException e) { throw new IOException("Invalid RPC length"); }
        if (length < 2 || length > 16_384) throw new IOException("Invalid RPC length");
        byte[] body = new byte[length]; new DataInputStream(input).readFully(body);
        return new String(body, StandardCharsets.UTF_8);
    }
    @Override public void close() {
        try { server.close(); } catch (IOException ignored) { }
        for (Socket socket : clients) { try { socket.close(); } catch (IOException ignored) { } }
        workers.shutdownNow();
    }
}
