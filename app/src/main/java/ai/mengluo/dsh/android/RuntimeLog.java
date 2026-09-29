package ai.mengluo.dsh.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Bounded, redacted history. No chat databases, settings or project files are collected. */
final class RuntimeLog implements Closeable {
    static final int SEGMENT_BYTES = 1024 * 1024;
    private static final int TAIL_CHARS = 64_000;
    private final File directory;
    private final int segmentBytes;
    private final StringBuilder tail = new StringBuilder();
    private FileOutputStream output;
    private long size;
    private boolean diskFailed;

    RuntimeLog(File directory) { this(directory, SEGMENT_BYTES); }
    RuntimeLog(File directory, int segmentBytes) {
        this.directory = directory; this.segmentBytes = segmentBytes;
        try {
            if (Files.isSymbolicLink(directory.toPath())) throw new IOException("日志目录不能是符号链接");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建日志目录");
            for (int i = 2; i >= 0; i--) {
                File file = segment(i);
                if (file.isFile()) {
                    if (file.length() > segmentBytes) throw new IOException("日志文件超出保留上限");
                    appendTail(RuntimePolicy.redact(IO.text(file)));
                }
            }
        } catch (IOException error) { failed(); }
    }
    private File segment(int index) throws IOException {
        File file = new File(directory, "runtime-" + index + ".log");
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("日志文件不能是符号链接");
        return file;
    }
    private void appendTail(String value) {
        tail.append(value);
        if (tail.length() > TAIL_CHARS) tail.delete(0, tail.length() - TAIL_CHARS);
    }
    synchronized void append(String value) {
        String clean = RuntimePolicy.redact(value);
        if (clean.length() > 24_000) clean = clean.substring(0, 24_000) + " [单条日志过长，已截断]";
        String line = "[" + Instant.now() + "] " + clean + "\n";
        appendTail(line);
        if (diskFailed) return;
        try {
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > segmentBytes) throw new IOException("单条日志超出文件上限");
            if (output == null) {
                File file = segment(0); size = file.length(); output = new FileOutputStream(file, true);
            }
            if (size + bytes.length > segmentBytes) {
                output.close(); output = null;
                Files.deleteIfExists(segment(2).toPath());
                if (segment(1).exists()) Files.move(segment(1).toPath(), segment(2).toPath());
                if (segment(0).exists()) Files.move(segment(0).toPath(), segment(1).toPath());
                output = new FileOutputStream(segment(0)); size = 0;
            }
            output.write(bytes); size += bytes.length;
        } catch (IOException error) { failed(); }
    }
    private void failed() {
        diskFailed = true;
        try { if (output != null) output.close(); } catch (IOException ignored) { }
        output = null;
        appendTail("[日志] 历史日志无法写入，当前仅保留内存中的近期日志。\n");
    }
    synchronized String tail() { return tail.toString(); }
    synchronized byte[] snapshot(String header) throws IOException {
        StringBuilder all = new StringBuilder(header).append('\n')
            .append("最多保留最近 3 MiB 运行日志；常见凭据已脱敏，分享前请检查路径及其他私人内容。\n\n");
        if (diskFailed) all.append(tail);
        else {
            if (output != null) output.flush();
            for (int i = 2; i >= 0; i--) {
                File file = segment(i);
                if (file.isFile()) {
                    if (file.length() > segmentBytes) throw new IOException("日志文件超出保留上限");
                    all.append(IO.text(file));
                }
            }
        }
        return RuntimePolicy.redact(all.toString()).getBytes(StandardCharsets.UTF_8);
    }
    @Override public synchronized void close() throws IOException { if (output != null) output.close(); output = null; }
}
