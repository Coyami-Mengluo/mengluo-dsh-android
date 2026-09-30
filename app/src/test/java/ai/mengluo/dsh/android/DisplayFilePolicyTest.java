package ai.mengluo.dsh.android;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class DisplayFilePolicyTest {
    @Test public void zoomHasUsableBounds() {
        assertEquals(50, PageZoom.clamp(0)); assertEquals(200, PageZoom.clamp(10000));
        assertEquals(100, PageZoom.clamp(100)); assertEquals(80, PageZoom.clamp(80));
    }
    @Test public void systemOpenAcceptsExactProjectPathsNotCommandsOrRuntimeSecrets() throws Exception {
        for (String path : new String[]{"/workspace/hello.html", "/root/my-project/图 #1.png", "/storage/emulated/0/Download/test.pdf", "/sdcard/Documents/test.txt"})
            assertEquals(path, SystemFiles.absolutePath(path));
        for (String path : new String[]{"/", "relative.txt", "https://example.com/image.png", "/workspace/../root/.dsh/key", "/root/.dsh/credentials", "/etc/passwd", "/proc/1/mem", "/workspace/bad\nfile", "/workspace/a\\b"})
            assertThrows(path, IOException.class, () -> SystemFiles.absolutePath(path));
    }
}
