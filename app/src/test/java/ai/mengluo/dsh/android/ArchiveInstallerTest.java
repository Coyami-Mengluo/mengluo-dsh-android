package ai.mengluo.dsh.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.*;

public class ArchiveInstallerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final class Entry {
        final String name, value;
        final byte type;
        Entry(String name, String value, byte type) { this.name = name; this.value = value; this.type = type; }
    }
    private static Entry file(String name, String text) { return new Entry(name, text, TarConstants.LF_NORMAL); }
    private static Entry hard(String name, String source) { return new Entry(name, source, TarConstants.LF_LINK); }
    private static Entry symbolic(String name, String source) { return new Entry(name, source, TarConstants.LF_SYMLINK); }
    private File archive(Entry... entries) throws Exception {
        File archive = temporary.newFile();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(new FileOutputStream(archive)))) {
            for (Entry value : entries) {
                TarArchiveEntry entry = new TarArchiveEntry(value.name, value.type);
                entry.setMode(0755);
                byte[] data = value.value.getBytes(StandardCharsets.UTF_8);
                if (value.type == TarConstants.LF_NORMAL) entry.setSize(data.length);
                else entry.setLinkName(value.value);
                tar.putArchiveEntry(entry);
                if (value.type == TarConstants.LF_NORMAL) tar.write(data);
                tar.closeArchiveEntry();
            }
        }
        return archive;
    }
    private static class TestFiles implements ArchiveInstaller.FileOperations {
        final Map<File, Integer> modes = new HashMap<>();
        final List<String> symlinks = new ArrayList<>();
        public void chmod(File file, int mode) { modes.put(file, mode); }
        public void symlink(String source, File target) { symlinks.add(target.getName() + "=" + source); }
    }
    private void extract(File archive, File root, int strip, TestFiles files, long limit) throws Exception {
        ArchiveInstaller.extract(archive, root, strip, ignored -> {}, files, limit);
    }
    @Test public void ubuntuAliasesBecomeIndependentFilesWithExecutableMode() throws Exception {
        File root = temporary.newFolder(); TestFiles files = new TestFiles();
        extract(archive(file("usr/bin/perl", "perl"), hard("usr/bin/perl5.38.2", "usr/bin/perl"),
            file("usr/bin/gunzip", "gunzip"), hard("usr/bin/uncompress", "usr/bin/gunzip")), root, 0, files, 1024);
        File source = new File(root, "usr/bin/perl"), copy = new File(root, "usr/bin/perl5.38.2");
        assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(copy.toPath()));
        assertEquals(Integer.valueOf(0755), files.modes.get(copy.getCanonicalFile()));
        assertEquals("gunzip", IO.text(new File(root, "usr/bin/uncompress")));
        assertFalse(Files.isSymbolicLink(copy.toPath()));
        IO.text(copy, "changed");
        assertEquals("perl", IO.text(source)); // Not a hard link on permissive hosts either.
    }
    @Test public void resolvesForwardReferencesAndChainsAfterStrippingBothPaths() throws Exception {
        File root = temporary.newFolder();
        extract(archive(hard("./node/bin/third", "./node/bin/second"), hard("node/bin/second", "node/bin/node"),
            file("node/bin/node", "node")), root, 1, new TestFiles(), 1024);
        assertEquals("node", IO.text(new File(root, "bin/third")));
        assertFalse(new File(root, "node").exists());
    }
    @Test public void copiedBytesAlsoCountTowardExtractionLimit() throws Exception {
        File root = temporary.newFolder();
        IOException error = assertThrows(IOException.class, () -> extract(
            archive(file("original", "1234"), hard("copy", "original")), root, 0, new TestFiles(), 7));
        assertTrue(error.getMessage().contains("大小超限"));
        assertFalse(new File(root, "copy").exists());
    }
    @Test public void copyAtExactExtractionLimitIsAllowed() throws Exception {
        File root = temporary.newFolder();
        extract(archive(file("original", "1234"), hard("copy", "original")), root, 0, new TestFiles(), 8);
        assertEquals("1234", IO.text(new File(root, "copy")));
    }
    @Test public void rejectsMissingSourcesAndCycles() throws Exception {
        for (Entry[] entries : new Entry[][] {
            {hard("copy", "missing")}, {hard("a", "b"), hard("b", "a")}, {hard("a", "a")}
        }) assertThrows(IOException.class, () -> extract(archive(entries), temporary.newFolder(), 0, new TestFiles(), 1024));
    }
    @Test public void rejectsSourcesOutsideArchiveIncludingPreexistingFiles() throws Exception {
        File root = temporary.newFolder();
        IO.text(new File(root, "preexisting"), "private");
        assertThrows(IOException.class, () -> extract(archive(hard("copy", "preexisting")), root, 0, new TestFiles(), 1024));
        assertFalse(new File(root, "copy").exists());
    }
    @Test public void rejectsSourceTraversalAndAbsolutePathsBeforeStripping() throws Exception {
        for (String source : new String[] {"../secret", "/etc/passwd", "x/../../secret", "../node/file", "x\\file"}) {
            assertThrows(IOException.class, () -> extract(archive(hard("node/copy", source)), temporary.newFolder(), 1, new TestFiles(), 1024));
        }
    }
    @Test public void rejectsTargetTraversal() throws Exception {
        File root = temporary.newFolder();
        assertThrows(IOException.class, () -> extract(archive(file("original", "safe"), hard("../escape", "original")), root, 0, new TestFiles(), 1024));
        assertFalse(new File(root.getParentFile(), "escape").exists());
    }
    @Test public void rejectsDuplicateCopyDestinations() throws Exception {
        for (Entry[] entries : new Entry[][] {
            {file("original", "safe"), file("copy", "keep"), hard("copy", "original")},
            {file("original", "safe"), hard("copy", "original"), hard("copy", "original")}
        }) assertThrows(IOException.class, () -> extract(archive(entries), temporary.newFolder(), 0, new TestFiles(), 1024));
    }
    @Test public void neverCopiesSymbolicLinksOrCreatesThemBeforeCopies() throws Exception {
        File root = temporary.newFolder(); TestFiles files = new TestFiles();
        assertThrows(IOException.class, () -> extract(archive(file("original", "safe"),
            symbolic("alias", "original"), hard("copy", "alias")), root, 0, files, 1024));
        assertTrue(files.symlinks.isEmpty());
        assertFalse(new File(root, "copy").exists());
    }
    @Test public void keepsLinuxSymbolicLinksDeferredAndUnchanged() throws Exception {
        File root = temporary.newFolder(); TestFiles files = new TestFiles() {
            @Override public void symlink(String source, File target) {
                assertTrue(new File(root, "copy").isFile());
                super.symlink(source, target);
            }
        };
        extract(archive(symbolic("bin", "usr/bin"), symbolic("mtab", "/proc/mounts"),
            file("original", "safe"), hard("copy", "original")), root, 0, files, 1024);
        assertEquals(Arrays.asList("bin=usr/bin", "mtab=/proc/mounts"), files.symlinks);
    }
    @Test public void cancellationDuringExtractionPreventsFurtherFilesAndLinks() throws Exception {
        File root = temporary.newFolder(); OperationCancellation cancellation = new OperationCancellation(Runnable::run);
        TestFiles files = new TestFiles() {
            @Override public void chmod(File file, int mode) { super.chmod(file, mode); cancellation.cancel(); }
        };
        File archive = archive(file("first", "content"), file("second", "never"), symbolic("alias", "first"));
        assertThrows(OperationCancellation.Stopped.class, () -> ArchiveInstaller.extract(archive, root, 0, ignored -> {}, files, 1024, cancellation));
        assertFalse(new File(root, "second").exists()); assertTrue(files.symlinks.isEmpty());
    }
}
