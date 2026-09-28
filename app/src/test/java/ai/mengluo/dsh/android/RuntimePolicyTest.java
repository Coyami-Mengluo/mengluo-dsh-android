package ai.mengluo.dsh.android;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;

public class RuntimePolicyTest {
    @Test public void onlyExactVersionsAreAccepted() {
        assertEquals("0.1.7-rc.2", RuntimePolicy.exactVersion("0.1.7-rc.2"));
        for (String bad : new String[]{"latest", "x; echo broken", "../a", "1.2"}) {
            assertThrows(IllegalArgumentException.class, () -> RuntimePolicy.exactVersion(bad));
        }
    }
    @Test public void rejectsPathsOutsideWorkspace() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "workspace");
        assertEquals(new File(root, "main.js").getCanonicalFile(), RuntimePolicy.inside(root, "main.js"));
        for (String bad : new String[]{"../secret", "/etc/passwd", "..\\outside"}) {
            assertThrows(IOException.class, () -> RuntimePolicy.inside(root, bad));
        }
    }
    @Test public void onlyCurrentLoopbackOriginStaysInWebView() {
        String ready = "http://127.0.0.1:47821/?token=secret";
        assertTrue(RuntimePolicy.trustedPage("http://127.0.0.1:47821/settings", ready));
        for (String bad : new String[]{"http://127.0.0.1:1/", "https://example.com/", "file:///etc/passwd", "http://x@127.0.0.1:47821/", "http://127.0.0.1.evil.com:47821/"})
            assertFalse(RuntimePolicy.trustedPage(bad, ready));
    }
    @Test public void diagnosticsHideTokensAndKeys() {
        String value = RuntimePolicy.redact("http://127.0.0.1:23/?token=abcdefgh Bearer secret123 sk-abcdefghijklmnop");
        assertFalse(value.contains("abcdefgh")); assertFalse(value.contains("secret123"));
        assertFalse(value.contains("sk-"));
    }
    @Test public void shellArgumentQuotingPreservesLiteralInput() {
        assertEquals("'a'\"'\"'b'", RuntimePolicy.quote("a'b"));
    }
}
