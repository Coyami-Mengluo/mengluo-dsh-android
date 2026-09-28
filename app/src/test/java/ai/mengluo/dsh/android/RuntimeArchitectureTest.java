package ai.mengluo.dsh.android;

import java.io.*;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeArchitectureTest {
    @Test public void matchingArchitecturesAreAccepted() throws Exception {
        check(183, "arm64-v8a", true);
        check(62, "x86_64", true);
    }
    @Test public void crossArchitecturePayloadsAreRejected() throws Exception {
        check(62, "arm64-v8a", false);
        check(183, "x86_64", false);
        check(40, "arm64-v8a", false);
    }
    @Test public void unknownAbiIsRejected() throws Exception { check(62, "armeabi-v7a", false); }
    private void check(int machine, String abi, boolean valid) throws Exception {
        File file = File.createTempFile("runtime-architecture-", ".elf");
        try {
            byte[] header = new byte[64];
            header[0] = 127; header[1] = 'E'; header[2] = 'L'; header[3] = 'F'; header[4] = 2; header[5] = 1;
            header[18] = (byte) machine; header[19] = (byte) (machine >> 8);
            Files.write(file.toPath(), header);
            if (valid) RuntimePolicy.requireElf64(file, abi);
            else assertThrows(IOException.class, () -> RuntimePolicy.requireElf64(file, abi));
        } finally { Files.deleteIfExists(file.toPath()); }
    }
}
