package ai.mengluo.dsh.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class PhoneStoragePathsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File storage, primary, workspace, rootfs;
    private ProjectFiles files;
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    @Before public void setup() throws Exception {
        storage = temporary.newFolder("storage"); primary = new File(storage, "emulated/0"); assertTrue(primary.mkdirs());
        workspace = temporary.newFolder("workspace"); rootfs = temporary.newFolder("rootfs");
        files = new ProjectFiles(workspace, rootfs, storage, primary, allowed::get);
    }
    private ProjectFiles.Project project(String path) { return new ProjectFiles.Project("test", path); }
    @Test public void multiplePublicProjectsAndStorageVolumesNeedNoSelection() throws Exception {
        for (String path : new String[]{"emulated/0/Documents/中文 project's", "emulated/0/Download/second", "ABCD-1234/third"}) {
            File folder = new File(storage, path); assertTrue(folder.mkdirs());
            var entry = project("/storage/" + path); files.create(entry, "", "code.js");
            files.saveText(entry, "code.js", "", path);
            assertEquals(path, new String(Files.readAllBytes(new File(folder, "code.js").toPath()), StandardCharsets.UTF_8));
        }
        assertEquals(0, workspace.list().length); assertEquals(0, rootfs.list().length);
    }
    @Test public void primaryAliasesReachSameOriginalFile() throws Exception {
        File folder = new File(primary, "Documents/demo"); assertTrue(folder.mkdirs());
        Files.write(new File(folder, "keep.txt").toPath(), "original".getBytes(StandardCharsets.UTF_8));
        for (String alias : new String[]{"/storage/emulated/0", "/sdcard", "/storage/self/primary"})
            assertEquals("original", files.readText(project(alias + "/Documents/demo"), "keep.txt"));
    }
    @Test public void revocationNeverMapsPublicFilesIntoPrivateRootfs() throws Exception {
        assertTrue(new File(rootfs, "storage/emulated/0").mkdirs());
        allowed.set(false);
        for (String path : new String[]{"/storage", "/storage/emulated/0", "/sdcard", "/storage/self/primary"})
            assertThrows(IOException.class, () -> files.resolve(project(path), "keep.txt"));
        assertEquals(workspace, files.directory(project("/workspace"), ""));
        assertEquals(0, new File(rootfs, "storage/emulated/0").list().length);
    }
    @Test public void movedProjectsAreNotRecreatedOrRepointed() throws Exception {
        File folder = new File(primary, "old"); assertTrue(folder.mkdir()); File moved = new File(primary, "new"); assertTrue(folder.renameTo(moved));
        assertThrows(IOException.class, () -> files.directory(project("/sdcard/old"), ""));
        assertFalse(folder.exists()); assertEquals(moved, files.directory(project("/sdcard/new"), ""));
    }
    @Test public void traversalAndUnmappedMountAliasesAreRejected() {
        for (String path : new String[]{"/sdcard/../private", "/storage/../../root", "/mnt/shared"})
            assertThrows(IOException.class, () -> files.resolve(project(path), ""));
    }
    @Test public void androidMediaIsNotBlanketBlockedByTheShell() throws Exception {
        File media = new File(primary, "Android/media/demo"); assertTrue(media.mkdirs());
        assertEquals(media, files.directory(project("/sdcard/Android/media/demo"), ""));
    }
}
