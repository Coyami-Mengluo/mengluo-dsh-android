package ai.mengluo.dsh.android;

import android.content.*;
import android.graphics.Bitmap;
import android.os.Environment;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import android.webkit.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
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
    private ProjectFiles files(java.util.function.BooleanSupplier allowed) {
        return new ProjectFiles(working, privateFixture, new File("/storage"), Environment.getExternalStorageDirectory(), allowed);
    }

    @Test public void oldSelectedDirectoryIsNotUsedAsAnAccessRestriction() throws Exception {
        String missing = new File(publicFixture, "old-missing-project").getPath();
        assertTrue(preferences.edit().putString("directory", missing).commit());
        WorkspaceStore store = new WorkspaceStore(new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        });
        ArrayList<String> arguments = new ArrayList<>(); store.addBindings(arguments, privateFixture);
        assertTrue(arguments.contains("/storage:/storage")); assertFalse(arguments.stream().anyMatch(s -> s.contains(missing)));
        assertEquals(missing, preferences.getString("directory", "")); assertFalse(new File(missing).exists());
    }
    @Test public void movedFolderFailsWithoutRecreationAndDefaultIsNotRemapped() throws Exception {
        IO.text(new File(project, "keep.txt"), "original");
        File renamed = new File(publicFixture, "renamed"); assertTrue(project.renameTo(renamed));
        assertFalse(project.exists());
        assertEquals("original", IO.text(new File(renamed, "keep.txt")));
        ProjectFiles files = files(() -> true);
        assertEquals(working, files.resolve(new ProjectFiles.Project("private", "/workspace"), ""));
        assertThrows(IOException.class, () -> files.directory(new ProjectFiles.Project("old", project.getPath()), ""));
        assertEquals("original", files.readText(new ProjectFiles.Project("new", renamed.getPath()), "keep.txt"));
    }
    @Test public void publicEditorImportExportUseOriginalFilesAndHonorPermission() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        ProjectFiles files = files(allowed::get);
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
    @Test public void sameProcessRunsMultiplePublicProjectsWithNodePythonBashNpmAndLocalServer() throws Exception {
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
        File second = new File(publicFixture, "second-project"); assertTrue(second.mkdir());
        String command = "set -e\ntest \"$PWD\" = /workspace\ncd " + RuntimePolicy.quote(project.getPath()) + "\nnode run.cjs\nbash run.sh\npython3 -c 'open(\"output/python.txt\",\"w\").write(\"python-ok\")'\n"
            + "test \"$(cat /workspace/private-marker.txt)\" = private-original\n"
            + "npm pack ./fixture-package --ignore-scripts --offline\n"
            + "npm install ./mengluo-test-local-1.0.0.tgz --ignore-scripts --offline --no-audit --no-fund\n"
            + "node -e 'if(require(\"mengluo-test-local\")!==42)process.exit(1)'\n"
            + "cd " + RuntimePolicy.quote(second.getPath()) + "\nnode -e 'require(\"fs\").writeFileSync(\"second.txt\",\"second-ok\")'\n"
            + "test \"$(cat " + RuntimePolicy.quote("/sdcard/" + publicFixture.getName() + "/" + project.getName() + "/input.txt") + ")\" = read-from-public\n"
            + "test \"$(cat " + RuntimePolicy.quote("/storage/self/primary/" + publicFixture.getName() + "/second-project/second.txt") + ")\" = second-ok\n"
            + "echo EXTERNAL_WORKSPACE_EXECUTION_OK\n";
        String output = run(engine, List.of("/bin/bash", "-lc", command), true);
        assertTrue(output, output.contains("EXTERNAL_WORKSPACE_EXECUTION_OK"));
        org.json.JSONObject result = new org.json.JSONObject(IO.text(new File(project, "output/node.json")));
        assertEquals(project.getCanonicalPath(), result.getString("cwd")); assertTrue(result.getBoolean("server"));
        assertEquals("bash-ok", IO.text(new File(project, "output/bash.txt"))); assertEquals("python-ok", IO.text(new File(project, "output/python.txt")));
        assertEquals("second-ok", IO.text(new File(second, "second.txt")));
        assertEquals("private-original", IO.text(new File(working, "private-marker.txt"))); assertFalse(new File(working, "output").exists());
    }
    @Test public void harnessStartsWithPhoneStorageAndIsolatedProfile() throws Exception {
        Engine engine = new Engine(context); assertTrue(engine.installed());
        RuntimeStore.Slot slot = engine.versions.active();
        Process process = engine.spawn(List.of("/opt/node/bin/node", "--expose-internals", slot.cli(), "web", "--host", "127.0.0.1", "--port", "0", "--no-open"), home, working, profile, true);
        ExecutorService reader = Executors.newSingleThreadExecutor(); BlockingQueue<String> urls = new LinkedBlockingQueue<>();
        reader.submit(() -> { try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = input.readLine()) != null) { String url = Engine.readyAddress(line); if (url != null) urls.offer(url); }
        } catch (IOException ignored) { } });
        try {
            String url = urls.poll(120, TimeUnit.SECONDS); assertNotNull("Harness must announce its web address", url); HarnessPage.check(url);
            checkPickerDefaults(url);
        }
        finally { assertTrue(RuntimeProcesses.stopAndWait(process, 5)); reader.shutdownNow(); }
    }
    private void checkPickerDefaults(String url) throws Exception {
        AtomicReference<WebView> browser = new AtomicReference<>();
        AtomicReference<androidx.webkit.ScriptHandler> injection = new AtomicReference<>();
        AtomicReference<WebTaskEvents> taskEvents = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView view = new WebView(activity); browser.set(view); view.getSettings().setJavaScriptEnabled(true);
                view.getSettings().setDomStorageEnabled(true);
                TaskNotifications notices = new TaskNotifications(new ContextWrapper(context) {
                    @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
                });
                WebTaskEvents events = new WebTaskEvents(view, () -> url, notices); taskEvents.set(events);
                try { assertTrue(events.install(url)); } catch (IOException error) { throw new AssertionError(error); }
                assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
                try (InputStream input = context.getAssets().open("web-compat.js")) {
                    String script = new String(IO.bytes(input), StandardCharsets.UTF_8) + "\n"
                        + DirectoryDefaults.script(context, Environment.getExternalStorageDirectory());
                    String origin = "http://127.0.0.1:" + android.net.Uri.parse(url).getPort();
                    injection.set(WebViewCompat.addDocumentStartJavaScript(view, script, Set.of(origin)));
                } catch (IOException error) { throw new AssertionError(error); }
                view.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String address) { loaded.countDown(); }
                });
                FrameLayout frame = activity.findViewById(android.R.id.content).findViewWithTag("shell-frame");
                frame.addView(view, new FrameLayout.LayoutParams(-1, -1)); view.loadUrl(url);
            });
            try {
                assertTrue("Isolated official page must load", loaded.await(30, TimeUnit.SECONDS));
                AtomicBoolean connected = new AtomicBoolean(); long connectionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!connected.get() && System.nanoTime() < connectionDeadline) {
                    scenario.onActivity(activity -> connected.set(taskEvents.get().connected())); Thread.sleep(100);
                }
                assertTrue("The official Harness page event stream must reach the notification observer", connected.get());
                for (String path : new String[]{null, "/root", "/workspace", "/"}) {
                    String payload = path == null ? "{}" : new org.json.JSONObject().put("path", path).toString();
                    String script = "window.__pickerProbe=null;(async()=>{try{const r=await fetch('api/directoryPicker/list',"
                        + "{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:crypto.randomUUID(),method:'directoryPicker/list',payload:{args:"
                        + payload + "}})});const data=await r.json();window.__pickerProbe=JSON.stringify({ok:data.result.ok,path:data.result.value?.path,home:data.result.value?.home});"
                        + "}catch(e){window.__pickerProbe=JSON.stringify({ok:false,error:String(e)})}})()";
                    scenario.onActivity(activity -> browser.get().evaluateJavascript(script, null));
                    String response = null; long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (System.nanoTime() < until) {
                        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
                        scenario.onActivity(activity -> browser.get().evaluateJavascript("window.__pickerProbe", value -> { result.set(value); done.countDown(); }));
                        assertTrue(done.await(5, TimeUnit.SECONDS));
                        if (!"null".equals(result.get())) { response = new org.json.JSONArray("[" + result.get() + "]").getString(0); break; }
                        Thread.sleep(100);
                    }
                    assertNotNull("Official directory listing must finish", response);
                    org.json.JSONObject result = new org.json.JSONObject(response);
                    assertTrue(response, result.getBoolean("ok"));
                    assertEquals(path == null ? Environment.getExternalStorageDirectory().getPath() : path, result.getString("path"));
                    assertEquals("Linux HOME must remain private", "/root", result.getString("home"));
                }
                // Exercise the official UI as well, so a changed/custom RPC transport cannot make
                // the direct fetch probe above pass while the actual picker still opens /root.
                awaitPage(scenario, browser, "(()=>{const b=document.querySelector('button[aria-label=\"添加工作区\"],button[aria-label=\"Add workspace\"]');"
                    + "if(!b||b.disabled)return false;b.click();return true})()", "Official Add workspace button");
                awaitPage(scenario, browser, "(()=>{const b=document.querySelector('button[aria-label=\"编辑路径\"],button[aria-label=\"Edit path\"]');"
                    + "if(!b?.closest('[role=\"dialog\"]')?.querySelector('[role=\"navigation\"] button'))return false;"
                    + "if(!b||b.disabled)return false;b.click();return true})()", "Official directory picker");
                String expected = org.json.JSONObject.quote(Environment.getExternalStorageDirectory().getPath() + "/");
                awaitPage(scenario, browser, "(()=>{const input=document.querySelector('input[aria-label=\"编辑路径\"],input[aria-label=\"Edit path\"]');"
                    + "return input?.value===" + expected + "})()", "Official picker starts in phone storage");
            } finally {
                scenario.onActivity(activity -> { if (injection.get() != null) injection.get().remove(); if (taskEvents.get() != null) taskEvents.get().close(); browser.get().destroy(); });
            }
        }
    }
    private void awaitPage(ActivityScenario<MainActivity> scenario, AtomicReference<WebView> browser, String script, String description) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
            scenario.onActivity(activity -> browser.get().evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
            assertTrue(description + " script must finish", done.await(5, TimeUnit.SECONDS));
            if ("true".equals(result.get())) return;
            Thread.sleep(100);
        }
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> diagnostic = new AtomicReference<>();
        scenario.onActivity(activity -> browser.get().evaluateJavascript("JSON.stringify({dialogs:[...document.querySelectorAll('[role=\"dialog\"]')].map(d=>({title:d.querySelector('h2')?.textContent,navigation:!!d.querySelector('[role=\"navigation\"]')})),controls:[...document.querySelectorAll('button[aria-label]')].map(b=>({label:b.getAttribute('aria-label'),disabled:b.disabled})).slice(-30)})", value -> { diagnostic.set(value); done.countDown(); }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        fail(description + ": " + diagnostic.get());
    }
    @Test public void permissionPageHasNoProjectSelectionAndCancelLeavesSettingsAlone() throws Exception {
        Map<String, ?> original = new HashMap<>(context.getSharedPreferences("workspaces", 0).getAll());
        AtomicReference<WorkspaceDialog> dialog = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { dialog.set(new WorkspaceDialog(activity, Engine.get(activity), null, () -> {})); dialog.get().show(); });
            snapshot("storage-settings");
            assertFalse(clickButtonIfPresent("选择手机文件夹", false)); assertFalse(clickButtonIfPresent("使用应用内工作目录", false));
            clickButton("管理文件权限"); snapshot("storage-permission-confirmation"); clickButton("取消");
            scenario.onActivity(activity -> dialog.get().close());
        }
        assertEquals(original, context.getSharedPreferences("workspaces", 0).getAll());
    }
    @Test public void installationAndSmokeModeDoNotMountPhoneFiles() throws Exception {
        IO.text(new File(project, "private-to-install-test.txt"), "keep");
        Engine engine = new Engine(context);
        String result = run(engine, List.of("/bin/bash", "-lc", "test ! -e " + RuntimePolicy.quote(new File(project, "private-to-install-test.txt").getPath()) + " && echo ISOLATED_INSTALL_OK"), false);
        assertTrue(result, result.contains("ISOLATED_INSTALL_OK"));
        assertEquals("keep", IO.text(new File(project, "private-to-install-test.txt")));
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
    private String run(Engine engine, List<String> command, boolean phoneStorage) throws Exception {
        Process process = engine.spawn(command, home, working, profile, phoneStorage);
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
