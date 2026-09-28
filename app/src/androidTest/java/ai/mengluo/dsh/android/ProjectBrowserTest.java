package ai.mengluo.dsh.android;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated fixtures in cache only: never edits the user's profile or code. */
@RunWith(AndroidJUnit4.class)
public class ProjectBrowserTest {
    private File fixture, workspace, rootfs, profile;
    @Before public void setup() throws Exception {
        fixture = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(), "projects-test-").toFile();
        workspace = new File(fixture, "workspace"); rootfs = new File(fixture, "rootfs"); profile = new File(fixture, "profile");
        assertTrue(workspace.mkdirs()); assertTrue(new File(rootfs, "root/114514").mkdirs()); assertTrue(new File(profile, "storages").mkdirs());
        IO.text(new File(profile, "storages/workspace.json"), "{\"unit\":{\"name\":\"workspace\",\"version\":2},\"global\":{\"workspaceIds\":[\"project\",\"duplicate\",\"stale\"]},\"tables\":{\"workspaces\":{\"project\":{\"title\":\"114514\",\"path\":\"/root/114514\"},\"duplicate\":{\"title\":\"again\",\"path\":\"/root/114514/\"},\"stale\":{\"title\":\"old\",\"path\":\"/root/missing\"}}}}");
        IO.text(new File(rootfs, "root/114514/example.html"), "<!doctype html><title>项目文件测试</title>");
    }
    @After public void cleanup() throws Exception {
        try (var stream = Files.walk(fixture.toPath())) {
            for (var path : stream.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) Files.delete(path);
        }
    }
    @Test public void catalogKeepsDefaultSelectedAndStaleProjects() {
        ProjectCatalog catalog = ProjectCatalog.read(profile);
        assertNull(catalog.warning); assertEquals(3, catalog.projects.size());
        assertEquals("/workspace", catalog.projects.get(0).path); assertEquals("114514", catalog.projects.get(1).title);
        assertEquals("/root/missing", catalog.projects.get(2).path);
    }
    @Test public void corruptIndexFallsBackWithoutEditingIt() throws Exception {
        File index = new File(profile, "storages/workspace.json"); IO.text(index, "partial");
        ProjectCatalog catalog = ProjectCatalog.read(profile); assertNotNull(catalog.warning); assertEquals(1, catalog.projects.size());
        assertEquals("partial", IO.text(index));
    }
    @Test public void refusesGuestSymlinksAndProfileMount() throws Exception {
        ProjectFiles files = new ProjectFiles(workspace, rootfs);
        ProjectFiles.Project project = new ProjectFiles.Project("114514", "/root/114514");
        File link = new File(rootfs, "root/114514/link"); Files.createSymbolicLink(link.toPath(), profile.toPath());
        assertThrows(IOException.class, () -> files.resolve(project, "link/storages/workspace.json"));
        assertThrows(IOException.class, () -> files.importFile(project, "link", "test.txt", new ByteArrayInputStream(new byte[]{1}), fixture));
        File projectLink = new File(rootfs, "root/alias"); Files.createSymbolicLink(projectLink.toPath(), workspace.toPath());
        assertThrows(IOException.class, () -> files.directory(new ProjectFiles.Project("alias", "/root/alias"), ""));
    }
    @Test public void documentPickerStateRetainsSelectedProjectAcrossRecreation() {
        Bundle saved = new Bundle(); saved.putString("files.project", "/root/114514"); saved.putString("files.title", "114514");
        saved.putString("files.relative", "subdir"); saved.putInt("files.request", ProjectBrowser.IMPORT);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ProjectBrowser browser = new ProjectBrowser(activity, workspace, rootfs, profile, saved);
                Bundle output = new Bundle(); browser.save(output);
                assertEquals("/root/114514", output.getString("files.project")); assertEquals("subdir", output.getString("files.relative"));
                browser.onActivityResult(ProjectBrowser.IMPORT, android.app.Activity.RESULT_CANCELED, null);
                Bundle cleared = new Bundle(); browser.save(cleared); assertFalse(cleared.containsKey("files.project")); browser.close();
            });
        }
    }
    @Test public void fileTransfersAndSafeSaveWorkOnAndroidFilesystem() throws Exception {
        ProjectFiles files = new ProjectFiles(workspace, rootfs);
        ProjectFiles.Project project = new ProjectFiles.Project("114514", "/root/114514");
        files.importFile(project, "", "导入.txt", new ByteArrayInputStream("原文".getBytes(java.nio.charset.StandardCharsets.UTF_8)), fixture);
        files.saveText(project, "导入.txt", "原文", "已保存");
        assertEquals("已保存", files.readText(project, "导入.txt"));
        assertFalse(new File(workspace, "导入.txt").exists());
        ByteArrayOutputStream output = new ByteArrayOutputStream(); files.exportFile(project, "导入.txt", output);
        assertEquals("已保存", new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> files.saveText(project, "导入.txt", "原文", "覆盖"));
        assertThrows(IOException.class, () -> files.importFile(project, "", "导入.txt", new ByteArrayInputStream(new byte[]{1}), fixture));
        assertEquals("已保存", files.readText(project, "导入.txt"));
    }
    @Test public void projectPickerOpensSelectedFilesAndEditor() throws Exception {
        AtomicReference<ProjectBrowser> browser = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { browser.set(new ProjectBrowser(activity, workspace, rootfs, profile, null)); browser.get().showProjects(); });
            idle(); clickListRow("114514"); idle();
            assertTrue("Selected project file is visible", visibleText("example.html"));
            snapshot("projects-browser"); clickListRow("example.html"); idle(); clickListRow("查看 / 编辑文本"); idle();
            assertTrue("Editor shows the selected project's file", visibleText("项目文件测试"));
            assertEquals("<!doctype html><title>项目文件测试</title>", IO.text(new File(rootfs, "root/114514/example.html")));
            scenario.onActivity(activity -> browser.get().close());
        }
    }
    private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private static boolean visibleText(String text) {
        final boolean[] found = {false}; InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (View window : WindowInspector.getGlobalWindowViews()) if (find(window, text) != null) found[0] = true;
        }); return found[0];
    }
    private static View find(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View result = find(((ViewGroup) view).getChildAt(i), text); if (result != null) return result; }
        return null;
    }
    private static void clickListRow(String text) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (View window : WindowInspector.getGlobalWindowViews()) {
                View match = find(window, text); if (match == null) continue;
                View row = match;
                while (row.getParent() instanceof View && !(row.getParent() instanceof ListView)) row = (View) row.getParent();
                if (row.getParent() instanceof ListView) {
                    ListView list = (ListView) row.getParent(); int position = list.getPositionForView(row);
                    list.performItemClick(row, position, list.getItemIdAtPosition(position)); return;
                }
            }
            fail("Missing selectable row: " + text);
        });
    }
    private static void snapshot(String name) throws Exception {
        // Wait for Material dialog enter/exit animations before evaluating the pixels.
        Thread.sleep(550); idle();
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "ui-verification"); directory.mkdirs();
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        finally { screenshot.recycle(); }
    }
}
