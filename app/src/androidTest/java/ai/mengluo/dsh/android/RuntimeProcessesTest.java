package ai.mengluo.dsh.android;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class RuntimeProcessesTest {
    @Test(timeout = 20000) public void rapidSuccessfulCommandsKeepTheirExitStatusAndOutput() throws Exception {
        // These finish before the PID ticket can reliably be sampled. Launch must not
        // replace a successful exit with its former "process exited early" exception.
        for (int attempt = 0; attempt < 20; attempt++) {
            Result result = run("printf 'quick-success\\n'; exit 0", "", true);
            assertEquals("attempt " + attempt, 0, result.exit);
            assertEquals("quick-success\n", result.stdout);
            assertEquals("", result.stderr);
        }
    }

    @Test(timeout = 20000) public void rapidFailedCommandsKeepTheirExitStatusAndMergedError() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            Result result = run("printf 'before-failure\\n'; printf 'failure-detail\\n' >&2; exit 17", "", true);
            assertEquals("attempt " + attempt, 17, result.exit);
            assertEquals("before-failure\nfailure-detail\n", result.stdout);
            assertEquals("", result.stderr);
        }
    }

    @Test(timeout = 10000) public void missingCommandKeepsShell127AndItsDiagnostic() throws Exception {
        String missing = "/system/bin/mengluo-runtime-command-that-does-not-exist";
        Result result = run(missing, "", true);
        assertEquals(127, result.exit);
        assertTrue("Keep the original shell diagnostic: " + result.stdout, result.stdout.contains(missing));
        assertEquals("", result.stderr);
    }

    @Test(timeout = 10000) public void launchDoesNotConsumeOrMergeSeparateStreamsOrStdin() throws Exception {
        String command = "IFS= read -r input; printf 'stdout:%s\\n' \"$input\"; printf 'stderr:%s\\n' \"$input\" >&2";
        Result baseline = run(command, "input remains intact\n", false, false);
        Result result = run(command, "input remains intact\n", false);
        // The raw command is a control for the shell fixture and platform stream routing.
        System.out.println("RuntimeProcesses raw stream baseline: stdout=" + baseline.stdout.replace("\n", "\\n")
            + "; stderr=" + baseline.stderr.replace("\n", "\\n"));
        assertEquals(0, baseline.exit);
        assertEquals(0, result.exit);
        assertEquals("stdout:input remains intact\n", baseline.stdout);
        assertEquals("stderr:input remains intact\n", baseline.stderr);
        String expected = "stdout:input remains intact\nstderr:input remains intact\n";
        assertEquals("Raw shell must preserve both complete messages and stdin", expected, baseline.stdout + baseline.stderr);
        assertEquals("Launch must preserve both complete messages and stdin", expected, result.stdout + result.stderr);
        assertEquals("Launch must preserve the platform's stdout routing", baseline.stdout, result.stdout);
        assertEquals("Launch must preserve the platform's stderr routing", baseline.stderr, result.stderr);
    }

    @Test(timeout = 15000) public void stopsOnlyOwnedLaunchedTree() throws Exception {
        Process first = null, other = null;
        ExecutorService readers = readers();
        try {
            // A readiness line ensures each shell has created its child before stopping.
            first = RuntimeProcesses.launch(new ProcessBuilder("/system/bin/sh", "-c", "sleep 12 & echo ready; wait").redirectErrorStream(true), cache());
            other = RuntimeProcesses.launch(new ProcessBuilder("/system/bin/sh", "-c", "sleep 12 & echo ready; wait").redirectErrorStream(true), cache());
            assertEquals("ready\n", readAsync(readers, first.getInputStream(), true).get(2, TimeUnit.SECONDS));
            assertEquals("ready\n", readAsync(readers, other.getInputStream(), true).get(2, TimeUnit.SECONDS));
            assertTrue(RuntimeProcesses.stopAndWait(first, 2));
            assertFalse(first.isAlive());
            assertTrue("Stopping one launch must not stop the unrelated launch", other.isAlive());
        } finally {
            // Also clean the first process if launching/observing the second one fails.
            try { if (first != null) assertTrue(RuntimeProcesses.stopAndWait(first, 1)); }
            finally {
                try { if (other != null) assertTrue(RuntimeProcesses.stopAndWait(other, 1)); }
                finally { readers.shutdownNow(); }
            }
        }
    }

    private static File cache() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
    }

    private static Result run(String command, String stdin, boolean mergeErrors) throws Exception {
        return run(command, stdin, mergeErrors, true);
    }

    private static Result run(String command, String stdin, boolean mergeErrors, boolean wrapped) throws Exception {
        Process process = null;
        ExecutorService readers = readers();
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(mergeErrors);
            process = wrapped ? RuntimeProcesses.launch(builder, cache()) : builder.start();
            Future<String> stdout = readAsync(readers, process.getInputStream(), false);
            Future<String> stderr = readAsync(readers, process.getErrorStream(), false);
            try (OutputStream input = process.getOutputStream()) {
                if (!stdin.isEmpty()) input.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue("Harmless shell command did not finish in time", process.waitFor(3, TimeUnit.SECONDS));
            return new Result(process.exitValue(), stdout.get(2, TimeUnit.SECONDS), stderr.get(2, TimeUnit.SECONDS));
        } finally {
            try { if (process != null && process.isAlive()) assertTrue(RuntimeProcesses.stopAndWait(process, 1)); }
            finally { readers.shutdownNow(); }
        }
    }

    private static ExecutorService readers() {
        return Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "runtime-process-test-output");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static Future<String> readAsync(ExecutorService readers, InputStream input, boolean oneLine) {
        return readers.submit(() -> {
            try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                int next;
                while ((next = stream.read()) != -1) {
                    if (bytes.size() >= 16 * 1024) throw new IOException("Unexpectedly large test output");
                    bytes.write(next);
                    if (oneLine && next == '\n') break;
                }
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        });
    }

    private static final class Result {
        final int exit;
        final String stdout, stderr;
        Result(int exit, String stdout, String stderr) {
            this.exit = exit; this.stdout = stdout; this.stderr = stderr;
        }
    }
}
