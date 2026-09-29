package ai.mengluo.dsh.android;

import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;
import java.io.*;
import java.nio.file.Files;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.*;

/** App-UID extraction regression; no existing runtime, settings or workspace is touched. */
@RunWith(AndroidJUnit4.class)
public class ArchiveInstallerDeviceTest {
    @Test public void copiesArchiveHardLinksWithoutLinkPermission() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixture = Files.createTempDirectory(context.getCacheDir().toPath(), "archive-test-").toFile();
        File archive = new File(fixture, "fixture.tgz"), root = new File(fixture, "root");
        assertTrue(root.mkdir());
        try {
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(new FileOutputStream(archive)))) {
                TarArchiveEntry source = new TarArchiveEntry("usr/bin/tool");
                source.setSize(4); source.setMode(0755);
                tar.putArchiveEntry(source); tar.write(new byte[] {1, 2, 3, 4}); tar.closeArchiveEntry();
                TarArchiveEntry copy = new TarArchiveEntry("usr/bin/alias", TarConstants.LF_LINK);
                copy.setLinkName("usr/bin/tool"); tar.putArchiveEntry(copy); tar.closeArchiveEntry();
                TarArchiveEntry link = new TarArchiveEntry("bin", TarConstants.LF_SYMLINK);
                link.setLinkName("usr/bin"); tar.putArchiveEntry(link); tar.closeArchiveEntry();
            }
            assertTrue(android.os.Process.myUid() >= 10000);
            ArchiveInstaller.extract(archive, root, 0, ignored -> {});
            assertCopy(root, "usr/bin/tool", "usr/bin/alias");
            assertEquals("usr/bin", Os.readlink(new File(root, "bin").getPath()));
        } finally { removeFixture(fixture); }
    }
    @Test public void extractsVerifiedPhoneAndEmulatorUbuntuArchives() throws Exception {
        // Optional local fixtures pushed by the maintainer; never downloads or runs guest binaries.
        String directory = InstrumentationRegistry.getArguments().getString("archiveFixtureDir");
        assumeNotNull(directory);
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String abi : new String[] {"arm64-v8a", "x86_64"}) {
            File root = Files.createTempDirectory(context.getCacheDir().toPath(), "ubuntu-extract-" + abi + "-").toFile();
            try {
                File archive = new File(directory, abi + ".tgz");
                String hash = IO.text(new File(directory, abi + ".sha256")).trim();
                ArchiveInstaller.verify(archive, hash);
                ArchiveInstaller.extract(archive, root, 0, ignored -> {});
                assertCopy(root, "usr/bin/perl", "usr/bin/perl5.38.2");
                assertCopy(root, "usr/bin/gunzip", "usr/bin/uncompress");
                assertEquals("usr/bin", Os.readlink(new File(root, "bin").getPath()));
            } finally { removeFixture(root); }
        }
    }
    private static void assertCopy(File root, String sourceName, String copyName) throws Exception {
        File source = new File(root, sourceName), copy = new File(root, copyName);
        assertTrue(copy.isFile()); assertFalse(Files.isSymbolicLink(copy.toPath()));
        assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(copy.toPath()));
        var sourceStat = Os.stat(source.getPath()); var copyStat = Os.stat(copy.getPath());
        assertEquals(sourceStat.st_mode & 0777, copyStat.st_mode & 0777);
        assertTrue((copyStat.st_mode & 0111) != 0);
        assertNotEquals(sourceStat.st_ino, copyStat.st_ino);
        assertEquals(1, copyStat.st_nlink);
    }
    private static void removeFixture(File file) throws IOException {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot enumerate test fixture");
            for (File child : children) removeFixture(child);
        }
        Files.delete(file.toPath());
    }
}
