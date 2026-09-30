package ai.mengluo.dsh.android;

import android.content.*;
import android.os.Environment;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Opt-in prepare, external permission revoke, denied, regrant, verify. No user profile access. */
@RunWith(AndroidJUnit4.class)
public class WorkspacePermissionRestartTest {
    @Test public void revocationKeepsPrivateProjectsUsableAndPublicPathsReal() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String phase = InstrumentationRegistry.getArguments().getString("workspacePermissionPhase");
        org.junit.Assume.assumeTrue("Explicit prepare/denied/verify phase required", phase != null);
        assertTrue(List.of("prepare", "denied", "verify").contains(phase));
        SharedPreferences prefs = context.getSharedPreferences("test-workspace-permission-restart", 0);
        if (phase.equals("prepare")) {
            assertTrue(Environment.isExternalStorageManager()); assertFalse("Clean up previous fixture first", prefs.contains("directory"));
            File folder = Files.createTempDirectory(Environment.getExternalStorageDirectory().toPath(), "MengLuo-permission-test-").toFile();
            File isolated = Files.createTempDirectory(context.getCacheDir().toPath(), "storage-permission-").toFile();
            assertTrue(new File(isolated, "workspace").mkdir()); assertTrue(new File(isolated, "home").mkdir()); assertTrue(new File(isolated, "profile").mkdir());
            IO.text(new File(folder, "keep.txt"), "permission-restart-original");
            assertTrue(prefs.edit().putString("directory", folder.getPath()).putString("private", isolated.getPath()).commit()); return;
        }
        String saved = prefs.getString("directory", null), savedPrivate = prefs.getString("private", null);
        assertNotNull("Run prepare first", saved); assertNotNull(savedPrivate);
        File folder = new File(saved), isolated = new File(savedPrivate);
        assertEquals(Environment.getExternalStorageDirectory().getCanonicalFile(), folder.getCanonicalFile().getParentFile());
        assertTrue(folder.getName().startsWith("MengLuo-permission-test-"));
        assertEquals(context.getCacheDir().getCanonicalFile(), isolated.getCanonicalFile().getParentFile());
        assertTrue(isolated.getName().startsWith("storage-permission-"));
        Engine engine = new Engine(context);
        ProjectFiles files = new ProjectFiles(new File(isolated, "workspace"), engine.rootfs,
            engine.workspaces.storageRoot, engine.workspaces.sharedRoot, engine.workspaces::hasAccess);
        if (phase.equals("denied")) {
            assertFalse(Environment.isExternalStorageManager());
            assertThrows(IOException.class, () -> files.readText(new ProjectFiles.Project("public", folder.getPath()), "keep.txt"));
            boolean hostReadable;
            try { assertEquals("permission-restart-original", IO.text(new File(folder, "keep.txt"))); hostReadable = true; }
            catch (IOException denied) { hostReadable = false; }
            String script = "const fs=require('fs'),assert=require('assert');assert.equal(process.cwd(),'/workspace');"
                + "fs.writeFileSync('still-private.txt','private-ok');let readable=false;"
                + "try{assert.equal(fs.readFileSync(process.argv[1],'utf8'),'permission-restart-original');readable=true}"
                + "catch(e){if(!['EACCES','EPERM','ENOENT'].includes(e.code))throw e}"
                + "assert.equal(readable," + hostReadable + ");console.log('ANDROID_PERMISSION_MATCHES')";
            Process process = engine.spawn(List.of("/opt/node/bin/node", "-e", script, new File(folder, "keep.txt").getPath()),
                new File(isolated, "home"), new File(isolated, "workspace"), new File(isolated, "profile"), true);
            ExecutorService reader = Executors.newSingleThreadExecutor();
            Future<String> output = reader.submit(() -> new String(IO.bytes(process.getInputStream()), StandardCharsets.UTF_8));
            try {
                assertTrue(process.waitFor(45, TimeUnit.SECONDS)); String text = output.get(5, TimeUnit.SECONDS);
                assertEquals(text, 0, process.exitValue()); assertTrue(text, text.contains("ANDROID_PERMISSION_MATCHES"));
            } finally { RuntimeProcesses.stopAndWait(process, 5); reader.shutdownNow(); }
            assertFalse(new File(engine.rootfs, folder.getPath().substring(1)).exists());
            assertEquals(saved, prefs.getString("directory", null)); return;
        }
        assertTrue(Environment.isExternalStorageManager());
        assertEquals("permission-restart-original", files.readText(new ProjectFiles.Project("public", folder.getPath()), "keep.txt"));
        assertEquals("private-ok", IO.text(new File(isolated, "workspace/still-private.txt")));
        Files.delete(new File(folder, "keep.txt").toPath()); Files.delete(folder.toPath());
        try (var paths = Files.walk(isolated.toPath())) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(item);
        }
        assertTrue(prefs.edit().clear().commit());
    }
}
