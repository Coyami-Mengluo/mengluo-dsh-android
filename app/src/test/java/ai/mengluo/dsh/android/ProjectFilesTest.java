package ai.mengluo.dsh.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ProjectFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File workspace, rootfs, cache;
    private ProjectFiles files;
    private final ProjectFiles.Project selected = new ProjectFiles.Project("114514", "/root/114514");
    @Before public void setup() throws Exception {
        workspace = temporary.newFolder("workspace"); rootfs = temporary.newFolder("rootfs"); cache = temporary.newFolder("cache");
        files = new ProjectFiles(workspace, rootfs); assertTrue(new File(rootfs, "root/114514/nested").mkdirs());
    }
    @Test public void mapsGuestProjectsAndWorkspaceBindSeparately() throws Exception {
        assertEquals(new File(rootfs, "root/114514"), files.directory(selected, ""));
        assertEquals(workspace, files.directory(new ProjectFiles.Project("default", "/workspace/"), ""));
        assertEquals(new File(workspace, "other/main.js"), files.resolve(new ProjectFiles.Project("other", "/workspace/other"), "main.js"));
    }
    @Test public void rejectsTraversalAndSpecialBindMounts() {
        for (String path : new String[]{"/", "/proc", "/dev", "/sys", "/root/.dsh", "/root/.dsh/storages", "/opt/harness", "/workspace/../root"})
            assertThrows(IOException.class, () -> files.resolve(new ProjectFiles.Project("bad", path), ""));
        for (String path : new String[]{"../other", "/root", "a/../../outside", "..\\outside", "bad\0name"})
            assertThrows(IOException.class, () -> files.resolve(selected, path));
        assertThrows(IOException.class, () -> files.resolve(new ProjectFiles.Project("root", "/root"), ".dsh/credentials.yml"));
    }
    @Test public void createImportExportAndEditStayInSelectedProject() throws Exception {
        File created = files.create(selected, "nested", "代码.js");
        files.saveText(selected, "nested/代码.js", "", "console.log(42)");
        assertEquals("console.log(42)", files.readText(selected, "nested/代码.js"));
        assertTrue(created.isFile()); assertFalse(new File(workspace, "nested/代码.js").exists());
        byte[] imported = "<html>你好</html>".getBytes(StandardCharsets.UTF_8);
        files.importFile(selected, "nested", "test.html", new ByteArrayInputStream(imported), cache);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); files.exportFile(selected, "nested/test.html", output);
        assertArrayEquals(imported, output.toByteArray());
        assertTrue(files.resolve(selected, "nested/test.html").isFile()); assertEquals(0, cache.list().length);
    }
    @Test public void existingFilesAndConcurrentEditsAreNotOverwritten() throws Exception {
        File created = files.create(selected, "", "same.txt"); Files.write(created.toPath(), "newer".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> files.create(selected, "", "same.txt"));
        assertThrows(IOException.class, () -> files.importFile(selected, "", "same.txt", new ByteArrayInputStream(new byte[]{1}), cache));
        assertThrows(IOException.class, () -> files.saveText(selected, "same.txt", "older", "replace"));
        assertEquals("newer", files.readText(selected, "same.txt"));
        assertFalse(files.list(selected, "").stream().anyMatch(f -> f.getName().startsWith(".mengluo-save-")));
    }
    @Test public void importsRefusePathLikeDisplayNames() {
        for (String bad : new String[]{"../outside", "/root/other", "sub/file", "..", "", "a\\b"})
            assertThrows(IOException.class, () -> files.importFile(selected, "", bad, new ByteArrayInputStream(new byte[]{1}), cache));
    }
    @Test public void failedAndOversizedImportsLeaveNoPartialFile() throws Exception {
        InputStream broken = new InputStream() { @Override public int read() throws IOException { throw new IOException("provider unavailable"); } };
        assertThrows(IOException.class, () -> files.importFile(selected, "", "failed.bin", broken, cache));
        InputStream huge = new InputStream() {
            long left = ProjectFiles.IMPORT_LIMIT + 1;
            @Override public int read() { if (left == 0) return -1; left--; return 0; }
            @Override public int read(byte[] buffer, int offset, int length) { if (left == 0) return -1; int size = (int) Math.min(left, length); left -= size; return size; }
        };
        assertThrows(IOException.class, () -> files.importFile(selected, "", "huge.bin", huge, cache));
        assertFalse(files.resolve(selected, "failed.bin").exists()); assertFalse(files.resolve(selected, "huge.bin").exists());
        assertEquals(0, cache.list().length);
    }
    @Test public void binaryAndLargeFilesCanStillBeExported() throws Exception {
        byte[] binary = new byte[]{0, 1, (byte) 255}; File file = files.create(selected, "", "image.bin"); Files.write(file.toPath(), binary);
        assertThrows(IOException.class, () -> files.readText(selected, "image.bin"));
        ByteArrayOutputStream output = new ByteArrayOutputStream(); files.exportFile(selected, "image.bin", output); assertArrayEquals(binary, output.toByteArray());
        File large = files.create(selected, "", "large.txt");
        try (RandomAccessFile data = new RandomAccessFile(large, "rw")) { data.setLength(ProjectFiles.TEXT_LIMIT + 1); }
        assertThrows(IOException.class, () -> files.readText(selected, "large.txt"));
        ByteArrayOutputStream exported = new ByteArrayOutputStream(); files.exportFile(selected, "large.txt", exported);
        assertEquals(ProjectFiles.TEXT_LIMIT + 1, exported.size());
    }
    @Test public void staleProjectIsNotRecreatedSilently() {
        ProjectFiles.Project missing = new ProjectFiles.Project("missing", "/root/missing");
        assertThrows(IOException.class, () -> files.directory(missing, ""));
        assertThrows(IOException.class, () -> files.create(missing, "", "file"));
        assertFalse(new File(rootfs, "root/missing").exists());
    }
    @Test public void listingDoesNotTruncateAtTwoHundredFiles() throws Exception {
        for (int i = 0; i < 205; i++) files.create(selected, "nested", "file-" + i);
        assertEquals(205, files.list(selected, "nested").size());
    }
}
