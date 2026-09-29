package ai.mengluo.dsh.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class RuntimeLogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private String snapshot(RuntimeLog log) throws Exception { return new String(log.snapshot("test"), StandardCharsets.UTF_8); }
    @Test public void persistsAcrossRestartAndRedactsBeforeDiskWrite() throws Exception {
        File directory = temporary.newFolder();
        try (RuntimeLog log = new RuntimeLog(directory)) {
            log.append("old-session ?token=secret123 DEEPSEEK_API_KEY=private-value sk-abcdefghijklmnop");
            assertFalse(IO.text(new File(directory, "runtime-0.log")).contains("private-value"));
        }
        try (RuntimeLog log = new RuntimeLog(directory)) {
            log.append("new-session 安装失败"); String value = snapshot(log);
            assertTrue(value.contains("old-session")); assertTrue(value.contains("new-session 安装失败"));
            assertTrue(value.indexOf("old-session") < value.indexOf("new-session"));
            assertFalse(value.contains("secret123")); assertFalse(value.contains("sk-abcdefghijklmnop"));
        }
    }
    @Test public void rotatesToThreeBoundedFilesInChronologicalOrder() throws Exception {
        File directory = temporary.newFolder();
        try (RuntimeLog log = new RuntimeLog(directory, 512)) {
            for (int i = 0; i < 80; i++) log.append(String.format("entry-%03d ", i) + "中文".repeat(12));
            String exported = snapshot(log); assertFalse(exported.contains("entry-000")); assertTrue(exported.contains("entry-079"));
            assertTrue(exported.indexOf("entry-078") < exported.indexOf("entry-079"));
            assertEquals(3, directory.listFiles().length);
            for (File file : directory.listFiles()) assertTrue(file.length() <= 512);
        }
    }
    @Test public void exportRetainsMoreThanUiTail() throws Exception {
        try (RuntimeLog log = new RuntimeLog(temporary.newFolder())) {
            log.append("first-marker");
            for (int i = 0; i < 1000; i++) log.append("installation-output-" + i + "x".repeat(90));
            assertFalse(log.tail().contains("first-marker")); assertTrue(snapshot(log).contains("first-marker"));
            assertTrue(log.tail().length() <= 64_000);
        }
    }
    @Test public void diskFailureDoesNotBreakInstallationOrExport() throws Exception {
        File notDirectory = temporary.newFile();
        try (RuntimeLog log = new RuntimeLog(notDirectory)) {
            log.append("recent-error"); assertTrue(snapshot(log).contains("recent-error"));
            assertTrue(snapshot(log).contains("内存"));
        }
    }
    @Test public void longSingleLineIsBoundedAndNewLinesStillSurvive() throws Exception {
        try (RuntimeLog log = new RuntimeLog(temporary.newFolder())) {
            log.append("x".repeat(100_000)); log.append("last-marker");
            assertTrue(snapshot(log).contains("已截断")); assertTrue(snapshot(log).contains("last-marker"));
            assertTrue(snapshot(log).length() < 30_000);
        }
    }
    @Test public void errorSummaryIsBoundedAndKeepsAptErrorOverProgress() {
        CommandFailure.Output output = new CommandFailure.Output();
        output.add("E: Failed to fetch https://example.com/?token=secret123");
        for (int i = 0; i < 20; i++) output.add("Reading package lists " + i);
        String error = new CommandFailure("apt-get", "退出码 100", output).getMessage();
        assertTrue(error.contains("E: Failed to fetch")); assertFalse(error.contains("secret123")); assertTrue(error.length() < 1900);
    }
}
