package ai.mengluo.dsh.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;

public class WorkspacePathsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File shared, project;
    @Before public void setup() throws Exception {
        shared = temporary.newFolder("shared"); project = new File(shared, "Documents/中文 project's"); assertTrue(project.mkdirs());
    }
    @Test public void acceptsRealProjectAndProbeLeavesNoFiles() throws Exception {
        assertEquals(project.getCanonicalFile(), WorkspacePaths.validate(shared, project, false));
        WorkspacePaths.probeWrite(project); assertEquals(0, project.list().length);
    }
    @Test public void rootCanBeBrowsedButNotSelected() throws Exception {
        assertEquals(shared.getCanonicalFile(), WorkspacePaths.validate(shared, shared, true));
        assertThrows(IOException.class, () -> WorkspacePaths.validate(shared, shared, false));
    }
    @Test public void blocksPrivateOutsideAndAndroidDirectories() throws Exception {
        File outside = temporary.newFolder("private"), android = new File(shared, "Android/data/another.app"); assertTrue(android.mkdirs());
        assertThrows(IOException.class, () -> WorkspacePaths.validate(shared, outside, false));
        assertThrows(IOException.class, () -> WorkspacePaths.validate(shared, android, false));
        assertThrows(IOException.class, () -> WorkspacePaths.validate(shared, new File("relative"), false));
    }
    @Test public void traversalIsRejectedEvenWhenItResolvesInsideRoot() {
        assertThrows(IOException.class, () -> WorkspacePaths.validate(shared, new File(project, ".."), false));
        assertThrows(IOException.class, () -> WorkspacePaths.validate(shared, new File(project, "."), false));
    }
    @Test public void missingFoldersAreNotRecreatedAndFilesCannotBeSelected() throws Exception {
        File missing = new File(shared, "missing");
        assertThrows(IOException.class, () -> WorkspacePaths.validate(shared, missing, false)); assertFalse(missing.exists());
        File file = new File(project, "text.txt"); Files.write(file.toPath(), "keep".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> WorkspacePaths.validate(shared, file, false)); assertEquals("keep", new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
    }
}
