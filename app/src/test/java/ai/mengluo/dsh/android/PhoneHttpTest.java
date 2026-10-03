package ai.mengluo.dsh.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneHttpTest {
    private String read(String request) throws Exception { return PhoneHttp.read(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), "a".repeat(64)); }
    private String header = "POST /v1 HTTP/1.1\r\nAuthorization: Bearer " + "a".repeat(64) + "\r\nContent-Type: application/json\r\n";
    @Test public void boundedAuthenticatedPost() throws Exception { assertEquals("{}", read(header + "Content-Length: 2\r\n\r\n{}")); }
    @Test public void browserOriginsAndQueryTokensAreRejected() {
        assertThrows(IOException.class, () -> read(header + "Origin: http://127.0.0.1:8080\r\nContent-Length: 2\r\n\r\n{}"));
        assertThrows(IOException.class, () -> read(header.replace("/v1", "/v1?token=anything") + "Content-Length: 2\r\n\r\n{}"));
        assertThrows(IOException.class, () -> read(header.replace("POST", "GET") + "Content-Length: 2\r\n\r\n{}"));
    }
    @Test public void wrongTokensAndRequestSmugglingAreRejected() {
        assertThrows(IOException.class, () -> read(header.replace("Bearer a", "Bearer b") + "Content-Length: 2\r\n\r\n{}"));
        assertThrows(IOException.class, () -> read(header + "Content-Length: 2\r\nContent-Length: 3\r\n\r\n{}"));
        assertThrows(IOException.class, () -> read(header + "Transfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n{}"));
        assertThrows(IOException.class, () -> read(header + "Content-Length: 16385\r\n\r\n{}"));
        assertThrows(IOException.class, () -> read(header + "Content-Length: 3\r\n\r\n{}"));
        assertThrows(IOException.class, () -> read(header + "X-Long: " + "a".repeat(8192)));
    }
}
