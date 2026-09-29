package ai.mengluo.dsh.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static org.junit.Assert.*;

public class ProjectExportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File workspace, rootfs;
    private ProjectFiles files;
    private final ProjectFiles.Project project = new ProjectFiles.Project("例子", "/workspace/demo");
    @Before public void setup() throws Exception {
        workspace = temporary.newFolder("workspace"); rootfs = temporary.newFolder("rootfs");
        assertTrue(new File(workspace, "demo/子目录/空文件夹").mkdirs());
        IO.text(new File(workspace, "demo/.hidden"), "hidden"); IO.text(new File(workspace, "demo/子目录/你好.html"), "你好");
        Files.write(new File(workspace, "demo/binary.bin").toPath(), new byte[]{0, 1, (byte)255});
        files = new ProjectFiles(workspace, rootfs);
    }
    private ProjectExport.Plan scan(String relative) throws Exception {
        return ProjectExport.scan(files, project, relative, () -> false, (name, done, total) -> {});
    }
    private Map<String, byte[]> zip(ProjectExport.Plan plan) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ProjectExport.zip(plan, output, () -> false, (name, done, total) -> {});
        HashMap<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) {
                assertNull("No duplicate entries", entries.put(entry.getName(), zip.readAllBytes())); zip.closeEntry();
            }
        }
        return entries;
    }
    @Test public void zipKeepsHierarchyEmptyDirectoriesHiddenUnicodeAndBinaryFiles() throws Exception {
        var plan = scan(""); var result = zip(plan);
        assertTrue(result.containsKey("demo/")); assertTrue(result.containsKey("demo/子目录/空文件夹/"));
        assertEquals("hidden", new String(result.get("demo/.hidden"), StandardCharsets.UTF_8));
        assertEquals("你好", new String(result.get("demo/子目录/你好.html"), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{0,1,(byte)255}, result.get("demo/binary.bin")); assertEquals(3, plan.fileCount);
        assertEquals("你好", IO.text(new File(workspace, "demo/子目录/你好.html")));
    }
    @Test public void subfolderExportDoesNotIncludeSiblings() throws Exception {
        var plan = scan("子目录"); var result = zip(plan);
        assertEquals(1, plan.fileCount); assertFalse(result.keySet().stream().anyMatch(name -> name.contains("binary") || name.contains(".hidden")));
        assertTrue(result.containsKey("子目录/空文件夹/"));
    }
    @Test public void copySinkGetsDirectoriesBeforeTheirFiles() throws Exception {
        HashMap<String, byte[]> result = new HashMap<>(); HashSet<String> directories = new HashSet<>(Set.of(""));
        ProjectExport.copy(scan(""), new ProjectExport.Sink() {
            @Override public void directory(String path) { assertTrue(directories.contains(parent(path))); directories.add(path); }
            @Override public OutputStream file(String path) {
                assertTrue(directories.contains(parent(path)));
                return new ByteArrayOutputStream() { @Override public void close() { result.put(path, toByteArray()); } };
            }
            private String parent(String path) { int i = path.lastIndexOf('/'); return i < 0 ? "" : path.substring(0, i); }
        }, () -> false, (name, done, total) -> assertTrue(done <= total));
        assertTrue(directories.contains("子目录/空文件夹")); assertEquals(3, result.size());
    }
    @Test public void changesAfterScanAreReportedInsteadOfClaimedComplete() throws Exception {
        var plan = scan(""); IO.text(new File(workspace, "demo/.hidden"), "concurrently changed");
        assertThrows(IOException.class, () -> zip(plan));
    }
    @Test public void deletionAfterScanIsReported() throws Exception {
        var plan = scan(""); Files.delete(new File(workspace, "demo/binary.bin").toPath());
        assertThrows(IOException.class, () -> zip(plan));
    }
    @Test public void cancellationStopsScanningAndCopyingWithoutEditingSource() throws Exception {
        assertThrows(IOException.class, () -> ProjectExport.scan(files, project, "", () -> true, (n,d,t) -> {}));
        var plan = scan(""); AtomicBoolean cancelled = new AtomicBoolean(); ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> ProjectExport.zip(plan, output, cancelled::get, (n,d,t) -> cancelled.set(true)));
        assertTrue(new File(workspace, "demo/binary.bin").isFile());
    }
    @Test public void runtimeProfileMountIsSkippedWhenExportingParent() throws Exception {
        assertTrue(new File(rootfs, "root/.dsh").mkdirs()); IO.text(new File(rootfs, "root/.dsh/credentials"), "private");
        IO.text(new File(rootfs, "root/project.txt"), "code");
        var plan = ProjectExport.scan(files, new ProjectFiles.Project("home", "/root"), "", () -> false, (n,d,t) -> {});
        assertEquals(1, plan.skippedCount); assertTrue(plan.summary().contains(".dsh"));
        assertFalse(zip(plan).keySet().stream().anyMatch(path -> path.contains(".dsh")));
    }
    @Test public void giantFileFailsBeforeDestinationWrites() throws Exception {
        try (RandomAccessFile large = new RandomAccessFile(new File(workspace, "demo/huge"), "rw")) { large.setLength(ProjectExport.MAX_BYTES + 1); }
        assertThrows(IOException.class, () -> scan(""));
    }
    @Test public void depthLimitAndPathValidationRejectInvalidTrees() throws Exception {
        assertThrows(IOException.class, () -> scan("../other"));
        File nested = new File(workspace, "demo"); for (int i = 0; i < 66; i++) { nested = new File(nested, "x"); assertTrue(nested.mkdir()); }
        assertThrows(IOException.class, () -> scan(""));
    }
}
