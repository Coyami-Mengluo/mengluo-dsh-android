package ai.mengluo.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RuntimeOutputTest {
    private List<String> read(String text) throws Exception {
        List<String> result = new ArrayList<>();
        RuntimeOutput.read(new StringReader(text), result::add);
        return result;
    }
    @Test public void preservesNormalLinesAndFinalPartialLine() throws Exception {
        assertEquals(List.of("a", "", "b", "c", "last"), read("a\r\n\nb\rc\nlast"));
        assertEquals(List.of("a"), read("a\n"));
        assertTrue(read("").isEmpty());
    }
    @Test public void acceptsExactLimitButOmitsEntireOversizedLine() throws Exception {
        String maximum = "x".repeat(RuntimeOutput.LINE_LIMIT);
        assertEquals(List.of(maximum), read(maximum));
        assertEquals(List.of(RuntimeOutput.OMITTED), read(maximum + "secret"));
        assertEquals(List.of(RuntimeOutput.OMITTED, "next"), read(maximum + "secret\r\nnext"));
    }
    @Test(timeout = 5000) public void unbrokenOutputIsDiscardedWithFixedMemoryAndOneNotice() throws Exception {
        List<String> lines = new ArrayList<>();
        Reader huge = new Reader() {
            long remaining = 32L * 1024 * 1024;
            public int read(char[] buffer, int offset, int length) {
                assertTrue("Read requests must remain bounded", length <= 4096);
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length);
                Arrays.fill(buffer, offset, offset + count, 'x'); remaining -= count;
                return count;
            }
            public void close() { }
        };
        RuntimeOutput.read(huge, lines::add);
        assertEquals(List.of(RuntimeOutput.OMITTED), lines);
    }
    @Test public void resumesAtNextLineAndKeepsUtf8AcrossByteBoundaries() throws Exception {
        String address = "dsh web: http://127.0.0.1:47821/?token=test-token";
        String value = "x".repeat(RuntimeOutput.LINE_LIMIT + 1) + "\n" + address + "\n中文😀";
        InputStream bytes = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) { return super.read(buffer, offset, Math.min(3, length)); }
        };
        List<String> lines = new ArrayList<>();
        RuntimeOutput.read(new InputStreamReader(bytes, StandardCharsets.UTF_8), lines::add);
        assertEquals(List.of(RuntimeOutput.OMITTED, address, "中文😀"), lines);
        assertEquals("http://127.0.0.1:47821/?token=test-token", Engine.readyAddress(lines.get(1)));
    }
}
