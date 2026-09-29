package ai.mengluo.dsh.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public class ProotCompatibilityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void everyNewProcessEnablesCompatibilityAndReusesPersistentBackingFiles() throws Exception {
        File root = temporary.newFolder("rootfs");
        ProcessBuilder first = new ProcessBuilder("proot", "-0", "-r", root.getPath(), "env", "-i", "dpkg");
        List<String> original = List.copyOf(first.command());
        ProotCompatibility.configure(first, root);
        assertEquals(List.of("proot", "--link2symlink", "-L"), first.command().subList(0, 3));
        assertEquals(original.subList(1, original.size()), first.command().subList(3, first.command().size()));
        File store = new File(first.environment().get("PROOT_L2S_DIR"));
        assertEquals(new File(root.getCanonicalFile(), ".l2s"), store);
        File existing = new File(store, "backing"); Files.write(existing.toPath(), "keep installed files".getBytes(StandardCharsets.UTF_8));
        ProcessBuilder next = new ProcessBuilder(original);
        ProotCompatibility.configure(next, root);
        assertEquals(first.command(), next.command());
        assertEquals(store.getPath(), next.environment().get("PROOT_L2S_DIR"));
        assertEquals("keep installed files", new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void doesNotReplaceAConflictingStoreOrRecreateAMissingRoot() throws Exception {
        File root = temporary.newFolder("rootfs");
        File conflict = new File(root, ".l2s"); Files.write(conflict.toPath(), "keep me".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ProotCompatibility.configure(new ProcessBuilder("proot"), root));
        assertEquals("keep me", new String(Files.readAllBytes(conflict.toPath()), StandardCharsets.UTF_8));
        File missing = new File(temporary.getRoot(), "missing");
        assertThrows(IOException.class, () -> ProotCompatibility.configure(new ProcessBuilder("proot"), missing));
        assertFalse(missing.exists());
    }
}
