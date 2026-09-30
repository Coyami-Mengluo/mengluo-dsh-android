package ai.mengluo.dsh.android;

import android.content.*;
import android.graphics.Bitmap;
import android.os.Environment;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Temporary public/private projects only; the runner grants/restores permission outside this process. */
@RunWith(AndroidJUnit4.class)
public class ExternalWorkspaceTest {
    private Context context;
    private File publicFixture, project, privateFixture, working, home, profile;
    private SharedPreferences preferences;

    @Before public void setup() throws Exception {
        Assume.assumeTrue("Opt in on a prepared test device with -e externalWorkspaceTests true",
            "true".equals(InstrumentationRegistry.getArguments().getString("externalWorkspaceTests")));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Grant all-files access to the test emulator app before starting this suite", Environment.isExternalStorageManager());
        publicFixture = Files.createTempDirectory(Environment.getExternalStorageDirectory().toPath(), "MengLuo-workspace-test-").toFile();
        project = new File(publicFixture, "中文 project's"); assertTrue(project.mkdir());
        privateFixture = Files.createTempDirectory(context.getCacheDir().toPath(), "external-workspace-").toFile();
        working = new File(privateFixture, "workspace"); home = new File(privateFixture, "home"); profile = new File(privateFixture, "profile");
        assertTrue(working.mkdir()); assertTrue(home.mkdir()); assertTrue(profile.mkdir());
        preferences = context.getSharedPreferences("workspace-test-" + UUID.randomUUID(), 0);
    }
    @After public void cleanup() throws Exception {
        if (preferences != null) assertTrue(preferences.edit().clear().commit());
        removeFixture(publicFixture); removeFixture(privateFixture);
    }
    private void removeFixture(File directory) throws Exception {
        if (directory == null || !directory.exists()) return;
        String name = directory.getName();
        assertTrue("Never remove user directories", name.startsWith("MengLuo-workspace-test-") || name.startsWith("external-workspace-"));
        try (var paths = Files.walk(directory.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
        }
    }
    private WorkspaceStore store() { return new WorkspaceStore(preferences, Environment.getExternalStorageDirectory(), Environment::isExternalStorageManager); }

    @Test public void selectionPersistsAndRevokedPermissionNeverFallsBack() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        WorkspaceStore store = new WorkspaceStore(preferences, Environment.getExternalStorageDirectory(), allowed::get); store.select(project);
        assertEquals(project.getCanonicalFile(), store().active());
        allowed.set(false);
        assertThrows(IOException.class, store::active); assertEquals(project.getCanonicalFile(), store().selected());
        // An explicit return to the private directory is allowed without broad file access.
        store.select(null); assertNull(store.active()); assertEquals("/workspace", store.guestPath());
        assertTrue(project.exists());
    }
    @Test public void movedFolderFailsWithoutRecreationAndDefaultIsNotRemapped() throws Exception {
        WorkspaceStore store = store(); store.select(project); IO.text(new File(project, "keep.txt"), "original");
        File renamed = new File(publicFixture, "renamed"); assertTrue(project.renameTo(renamed));
        assertThrows(IOException.class, store::active); assertFalse(project.exists());
        assertEquals("original", IO.text(new File(renamed, "keep.txt")));
        ProjectFiles files = new ProjectFiles(working, privateFixture, renamed, () -> true);
        assertEquals(working, files.resolve(new ProjectFiles.Project("private", "/workspace"), ""));
        assertThrows(IOException.class, () -> files.resolve(new ProjectFiles.Project("old", project.getPath()), "keep.txt"));
    }
    @Test public void publicEditorImportExportUseOriginalFilesAndHonorPermission() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        ProjectFiles files = new ProjectFiles(working, privateFixture, project, allowed::get);
        ProjectFiles.Project entry = new ProjectFiles.Project("public", project.getPath());
        files.importFile(entry, "", "source.js", new ByteArrayInputStream("console.log(1)".getBytes(StandardCharsets.UTF_8)), context.getCacheDir());
        files.saveText(entry, "source.js", "console.log(1)", "console.log(42)");
        assertEquals("console.log(42)", IO.text(new File(project, "source.js")));
        assertFalse(new File(working, "source.js").exists());
        ByteArrayOutputStream copy = new ByteArrayOutputStream(); files.exportFile(entry, "source.js", copy);
        assertEquals("console.log(42)", new String(copy.toByteArray(), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> files.saveText(entry, "source.js", "stale", "overwrite"));
        allowed.set(false); assertThrows(IOException.class, () -> files.readText(entry, "source.js"));
        allowed.set(true); assertEquals("console.log(42)", files.readText(entry, "source.js"));
    }
    @Test public void nodePythonBashNpmAndLocalServerRunInRealSelectedDirectory() throws Exception {
        Engine engine = new Engine(context); assertTrue("Install the runtime in the test emulator first", engine.installed());
        assertTrue("Runs as the app UID", android.os.Process.myUid() >= 10000);
        IO.text(new File(working, "private-marker.txt"), "private-original");
        IO.text(new File(project, "input.txt"), "read-from-public");
        IO.text(new File(project, "run.cjs"), "const assert=require('node:assert/strict'),fs=require('node:fs'),http=require('node:http');\n"
            + "assert.equal(fs.readFileSync('input.txt','utf8'),'read-from-public');fs.mkdirSync('output',{recursive:true});\n"
            + "const s=http.createServer((q,r)=>r.end(fs.readFileSync('input.txt')));s.listen(0,'127.0.0.1',async()=>{try{"
            + "const r=await fetch('http://127.0.0.1:'+s.address().port);assert.equal(await r.text(),'read-from-public');"
            + "fs.writeFileSync('output/node.json',JSON.stringify({cwd:process.cwd(),server:true}));s.close();}catch(e){console.error(e);process.exit(1)}});\n");
        IO.text(new File(project, "run.sh"), "printf 'bash-ok' > output/bash.txt\n");
        File pkg = new File(project, "fixture-package"); assertTrue(pkg.mkdir());
        IO.text(new File(pkg, "package.json"), "{\"name\":\"mengluo-test-local\",\"version\":\"1.0.0\",\"main\":\"index.js\"}");
        IO.text(new File(pkg, "index.js"), "module.exports=42;\n");
        String command = "set -e\nnode run.cjs\nbash run.sh\npython3 -c 'open(\"output/python.txt\",\"w\").write(\"python-ok\")'\n"
            + "test \"$(cat /workspace/private-marker.txt)\" = private-original\n"
            + "npm pack ./fixture-package --ignore-scripts --offline\n"
            + "npm install ./mengluo-test-local-1.0.0.tgz --ignore-scripts --offline --no-audit --no-fund\n"
            + "node -e 'if(require(\"mengluo-test-local\")!==42)process.exit(1)'\necho EXTERNAL_WORKSPACE_EXECUTION_OK\n";
        String output = run(engine, List.of("/bin/bash", "-lc", command), project);
        assertTrue(output, output.contains("EXTERNAL_WORKSPACE_EXECUTION_OK"));
        org.json.JSONObject result = new org.json.JSONObject(IO.text(new File(project, "output/node.json")));
        assertEquals(project.getCanonicalPath(), result.getString("cwd")); assertTrue(result.getBoolean("server"));
        assertEquals("bash-ok", IO.text(new File(project, "output/bash.txt"))); assertEquals("python-ok", IO.text(new File(project, "output/python.txt")));
        assertEquals("private-original", IO.text(new File(working, "private-marker.txt"))); assertFalse(new File(working, "output").exists());
    }
    @Test public void harnessStartsWithPublicCwdAndIsolatedProfile() throws Exception {
        Engine engine = new Engine(context); assertTrue(engine.installed());
        RuntimeStore.Slot slot = engine.versions.active();
        Process process = engine.spawn(List.of("/opt/node/bin/node", "--expose-internals", slot.cli(), "web", "--host", "127.0.0.1", "--port", "0", "--no-open"), home, working, profile, project);
        ExecutorService reader = Executors.newSingleThreadExecutor(); BlockingQueue<String> urls = new LinkedBlockingQueue<>();
        reader.submit(() -> { try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = input.readLine()) != null) { String url = Engine.readyAddress(line); if (url != null) urls.offer(url); }
        } catch (IOException ignored) { } });
        try { String url = urls.poll(120, TimeUnit.SECONDS); assertNotNull("Harness must announce its web address", url); HarnessPage.check(url); }
        finally { assertTrue(RuntimeProcesses.stopAndWait(process, 5)); reader.shutdownNow(); }
    }
    @Test public void settingsAndFolderPickerRenderAndCancelWithoutChangingSelection() throws Exception {
        String original = new WorkspaceStore(context).guestPath();
        AtomicReference<WorkspaceDialog> dialog = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { dialog.set(new WorkspaceDialog(activity, Engine.get(activity), null, () -> fail("Must not switch without confirmation"))); dialog.get().show(); });
            snapshot("workspace-settings");
            scenario.onActivity(activity -> { dialog.get().close(); dialog.set(new WorkspaceDialog(activity, Engine.get(activity), null, () -> fail())); dialog.get().browse(publicFixture, 0); });
            Thread.sleep(700); snapshot("workspace-picker");
            scenario.onActivity(activity -> dialog.get().close());
        }
        assertEquals(original, new WorkspaceStore(context).guestPath());
    }
    @Test public void pickerConfirmationPersistsOnlyChosenProjectAndBusyChangeIsRejected() throws Exception {
        Engine isolated = new Engine(new ContextWrapper(context) {
            @Override public File getFilesDir() { return privateFixture; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("workspaces") ? preferences : super.getSharedPreferences(name, mode);
            }
        });
        CountDownLatch saved = new CountDownLatch(1); AtomicReference<WorkspaceDialog> dialog = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                dialog.set(new WorkspaceDialog(activity, isolated, null, saved::countDown)); dialog.get().browse(project, 0);
            });
            awaitButton("使用此文件夹"); clickButton("使用此文件夹");
            assertNull(isolated.workspaces.selected()); // Choosing a row isn't consent to switch.
            clickButton("确认切换"); assertTrue("Directory switch must finish", saved.await(15, TimeUnit.SECONDS));
            assertEquals(project.getCanonicalFile(), store().active());
            isolated.busy = true;
            AtomicReference<String> rejected = new AtomicReference<>(); isolated.changeWorkspace(null, false, rejected::set);
            assertNotNull(rejected.get()); assertEquals(project.getCanonicalFile(), store().selected()); isolated.busy = false;
            scenario.onActivity(activity -> { dialog.get().show(); }); snapshot("workspace-selected");
            scenario.onActivity(activity -> dialog.get().close());
        }
        assertTrue(project.isDirectory()); assertTrue(working.isDirectory());
    }
    private static View button(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text) && view.isShown()) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View result = button(group.getChildAt(i), text); if (result != null) return result; }
        return null;
    }
    private static boolean clickButtonIfPresent(String text, boolean click) {
        AtomicBoolean found = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (View window : WindowInspector.getGlobalWindowViews()) {
                View target = button(window, text); if (target != null) { found.set(true); if (click) target.performClick(); return; }
            }
        }); return found.get();
    }
    private static void awaitButton(String text) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!clickButtonIfPresent(text, false)) { assertTrue("Missing button: " + text, System.nanoTime() < until); Thread.sleep(100); }
    }
    private static void clickButton(String text) throws Exception { awaitButton(text); assertTrue(clickButtonIfPresent(text, true)); }
    private String run(Engine engine, List<String> command, File external) throws Exception {
        Process process = engine.spawn(command, home, working, profile, external);
        ExecutorService reader = Executors.newSingleThreadExecutor(); Future<String> output = reader.submit(() -> new String(IO.bytes(process.getInputStream()), StandardCharsets.UTF_8));
        try {
            assertTrue("Command timeout", process.waitFor(120, TimeUnit.SECONDS)); String text = output.get(10, TimeUnit.SECONDS);
            assertEquals(text, 0, process.exitValue()); return text;
        } finally { RuntimeProcesses.stopAndWait(process, 5); reader.shutdownNow(); }
    }
    private void snapshot(String name) throws Exception {
        Thread.sleep(500); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // Public test folder allows retrieving screenshots without root or a debuggable production APK.
        File output = new File(context.getExternalFilesDir(null), "ui-verification"); assertTrue(output.isDirectory() || output.mkdirs());
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (OutputStream stream = new FileOutputStream(new File(output, name + ".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
        finally { image.recycle(); }
    }
}
