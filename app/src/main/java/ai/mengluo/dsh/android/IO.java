package ai.mengluo.dsh.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class IO {
    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024]; int length;
        while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
    }
    static byte[] bytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); copy(input, output); return output.toByteArray();
    }
    static String text(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    static void text(File file, String value) throws IOException { Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); }
    static void atomicText(File file, String value) throws IOException {
        android.util.AtomicFile atomic = new android.util.AtomicFile(file);
        FileOutputStream output = atomic.startWrite();
        try { output.write(value.getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(output); }
        catch (IOException error) { atomic.failWrite(output); throw error; }
    }
}
