package ai.mengluo.dsh.android;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProotLibrariesTest {
    private Context context;
    private Path fixture;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fixture = Files.createTempDirectory(context.getCacheDir().toPath(), "proot-libraries-test-");
    }
    @After public void cleanup() throws Exception {
        if (fixture != null) try (var paths = Files.walk(fixture)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
        }
    }
    @Test public void aliasesUseInstalledLibrariesAndRefreshAfterApkPathChanges() throws Exception {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File aliases = fixture.resolve("aliases").toFile();
        ProcessBuilder builder = new ProcessBuilder("unused");
        builder.environment().put("LD_PRELOAD", "/old-apk/libtalloc.so");
        ProotLibraries.configure(builder, nativeDir, aliases);
        assertFalse(builder.environment().containsKey("LD_PRELOAD"));
        assertEquals(aliases.getAbsolutePath() + ":" + nativeDir.getAbsolutePath(), builder.environment().get("LD_LIBRARY_PATH"));
        Path talloc = aliases.toPath().resolve("libtalloc.so.2");
        assertEquals(new File(nativeDir, "libtalloc.so").toPath(), Files.readSymbolicLink(talloc));
        ProotLibraries.configure(builder, nativeDir, aliases);
        assertEquals(2, aliases.list().length);
        Path nextApk = Files.createDirectory(fixture.resolve("next-apk"));
        IO.text(nextApk.resolve("libtalloc.so").toFile(), "fixture-only");
        IO.text(nextApk.resolve("libandroid-shmem.so").toFile(), "fixture-only");
        ProotLibraries.configure(builder, nextApk.toFile(), aliases);
        assertEquals(nextApk.resolve("libtalloc.so"), Files.readSymbolicLink(talloc));
        assertEquals(2, aliases.list().length);
        // Updating an alias never changes/deletes either target.
        assertEquals("fixture-only", IO.text(nextApk.resolve("libtalloc.so").toFile()));
        assertTrue(new File(nativeDir, "libtalloc.so").isFile());
    }
    @Test public void refusesToReplaceForeignFilesOrFollowAliasDirectorySymlinks() throws Exception {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        Path aliases = Files.createDirectory(fixture.resolve("aliases"));
        Path foreign = aliases.resolve("libtalloc.so.2"); IO.text(foreign.toFile(), "keep");
        assertThrows(IOException.class, () -> ProotLibraries.configure(new ProcessBuilder("unused"), nativeDir, aliases.toFile()));
        assertEquals("keep", IO.text(foreign.toFile()));
        Path link = fixture.resolve("linked-directory"); Files.createSymbolicLink(link, aliases);
        assertThrows(IOException.class, () -> ProotLibraries.configure(new ProcessBuilder("unused"), nativeDir, link.toFile()));
    }
    @Test public void actualGuestStartsWithoutAndroidPreloadsAndKeepsToolsWorking() throws Exception {
        Engine engine = Engine.get(context);
        assertTrue("Use a prepared test emulator; never install a runtime from this test", engine.installed());
        assertTrue(android.os.Process.myUid() >= 10000);
        File home = Files.createDirectory(fixture.resolve("home")).toFile();
        File working = Files.createDirectory(fixture.resolve("workspace")).toFile();
        File profile = Files.createDirectory(fixture.resolve("profile")).toFile();
        String script = "set -eu\n"
            + "test -z \"${LD_PRELOAD+x}\"\ntest -z \"${LD_LIBRARY_PATH+x}\"\n"
            + "node -e 'if(process.env.LD_PRELOAD||process.env.LD_LIBRARY_PATH)process.exit(9);"
            + "require(\"fs\").writeFileSync(\"result.txt\",\"node-ok\");console.log(\"NODE_READY\")'\n"
            + "test \"$(cat result.txt)\" = node-ok\nln result.txt linked.txt\ntest \"$(cat linked.txt)\" = node-ok\n"
            + "git --version\npython3 --version\nnpm --version\npnpm --version\ndpkg --version\necho GUEST_TOOLS_READY\n";
        Process process = engine.spawn(List.of("/bin/bash", "-lc", script), home, working, profile, false);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<String> output = reader.submit(() -> new String(IO.bytes(process.getInputStream()), StandardCharsets.UTF_8));
        try {
            assertTrue("Guest commands timed out", process.waitFor(90, TimeUnit.SECONDS));
            String text = output.get(5, TimeUnit.SECONDS);
            assertEquals(text, 0, process.exitValue());
            assertTrue(text, text.contains("NODE_READY")); assertTrue(text, text.contains("GUEST_TOOLS_READY"));
            assertFalse(text, text.contains("LD_PRELOAD")); assertFalse(text, text.contains("cannot be preloaded"));
            assertEquals("node-ok", IO.text(new File(working, "result.txt")));
        } finally { RuntimeProcesses.stopAndWait(process, 5); reader.shutdownNow(); }
    }
}
