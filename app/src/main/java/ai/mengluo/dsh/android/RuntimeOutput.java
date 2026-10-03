package ai.mengluo.dsh.android;

import java.io.IOException;
import java.io.Reader;
import java.util.function.Consumer;

/** Bound memory before redaction/logging, including output that never contains a newline. */
final class RuntimeOutput {
    static final int LINE_LIMIT = 24 * 1024;
    static final String OMITTED = "[runtime] 警告：单条进程输出过长，已省略该行；后续日志继续记录";

    static void read(Reader input, Consumer<String> output) throws IOException {
        char[] buffer = new char[4096];
        StringBuilder line = new StringBuilder();
        boolean discarded = false, skipLf = false;
        int count;
        while ((count = input.read(buffer)) != -1) {
            for (int i = 0; i < count; i++) {
                char value = buffer[i];
                if (skipLf) { skipLf = false; if (value == '\n') continue; }
                if (value == '\r' || value == '\n') {
                    if (!discarded) output.accept(line.toString());
                    line.setLength(0); discarded = false; skipLf = value == '\r';
                } else if (!discarded) {
                    if (line.length() == LINE_LIMIT) {
                        // Do not emit partial secrets or split a token across several log records.
                        line.setLength(0); discarded = true; output.accept(OMITTED);
                    } else line.append(value);
                }
            }
        }
        if (!discarded && line.length() > 0) output.accept(line.toString());
    }
}
