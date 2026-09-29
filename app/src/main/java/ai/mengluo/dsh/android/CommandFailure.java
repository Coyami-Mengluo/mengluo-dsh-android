package ai.mengluo.dsh.android;

import java.io.IOException;
import java.util.*;

/** Retains bounded, redacted diagnostics so exit 100 is not the only visible failure detail. */
final class CommandFailure extends IOException {
    final boolean dpkgInterrupted, localPackageState;
    CommandFailure(String command, String reason, Output output) {
        super(command + "：" + reason + (output.summary().isEmpty() ? "" : "\n" + output.summary()));
        dpkgInterrupted = output.dpkgInterrupted; localPackageState = output.localPackageState;
    }
    static final class Output {
        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private boolean dpkgInterrupted, localPackageState;
        synchronized void add(String line) {
            String clean = RuntimePolicy.redact(line).trim();
            if (clean.isEmpty()) return;
            if (clean.startsWith("E: dpkg was interrupted,")) { dpkgInterrupted = true; localPackageState = true; }
            if (clean.matches("(?i).*(could not get lock|unable to acquire.*lock|permission denied|no space left on device|read-only file system).*")
                && !clean.startsWith("ERROR: ld.so:")) localPackageState = true;
            lines.addLast(clean.length() > 600 ? clean.substring(0, 600) + "…" : clean);
            while (lines.size() > 30) lines.removeFirst();
        }
        synchronized String summary() {
            ArrayList<String> errors = new ArrayList<>();
            for (String line : lines) if (!line.startsWith("ERROR: ld.so:")
                && line.matches("(?i)^(E:|Err:|dpkg:|npm (ERR!|error)|Error:|W: Failed to fetch).*")) errors.add(line);
            List<String> selected = errors.isEmpty() ? new ArrayList<>(lines) : errors;
            String result = String.join("\n", selected.subList(Math.max(0, selected.size() - 4), selected.size()));
            return result.length() > 1800 ? result.substring(result.length() - 1800) : result;
        }
    }
}
