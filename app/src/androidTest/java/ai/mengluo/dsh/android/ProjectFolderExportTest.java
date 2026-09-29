package ai.mengluo.dsh.android;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProjectFolderExportTest {
    private File fixture, workspace, rootfs; private ProjectFiles files; private ContentResolver resolver; private Uri tree;
    private final ProjectFiles.Project project = new ProjectFiles.Project("demo", "/workspace/demo");
    @Before public void setup() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext(); resolver = context.getContentResolver();
        fixture = Files.createTempDirectory(context.getCacheDir().toPath(), "folder-export-test-").toFile();
        workspace = new File(fixture, "workspace"); rootfs = new File(fixture, "rootfs"); rootfs.mkdirs();
        new File(workspace, "demo/中文/empty").mkdirs(); IO.text(new File(workspace, "demo/中文/page.html"), "HTML 内容");
        IO.text(new File(workspace, "demo/.env.example"), "example"); files = new ProjectFiles(workspace, rootfs);
        tree = resolver.call(ExportTestProvider.AUTHORITY, "fixture.new", null, null).getParcelable("uri");
    }
    @After public void cleanup() throws Exception {
        Bundle args = new Bundle(); args.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)));
        resolver.call(ExportTestProvider.AUTHORITY, "fixture.remove", null, args);
        try (var stream = Files.walk(fixture.toPath())) { for (var path : stream.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) Files.delete(path); }
    }
    private Uri root() { return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)); }
    private Map<String, Uri> children(Uri parent) {
        HashMap<String, Uri> result = new HashMap<>();
        Uri query = DocumentsContract.buildChildDocumentsUri(ExportTestProvider.AUTHORITY, DocumentsContract.getDocumentId(parent));
        try (var cursor = resolver.query(query, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            while (cursor.moveToNext()) result.put(cursor.getString(0), DocumentsContract.buildDocumentUri(ExportTestProvider.AUTHORITY, cursor.getString(1)));
        }
        return result;
    }
    private String read(Uri uri) throws Exception { try (InputStream input = resolver.openInputStream(uri)) { return new String(IO.bytes(input), StandardCharsets.UTF_8); } }
    private ProjectExport.Plan plan() throws Exception { return ProjectExport.scan(files, project, "", () -> false, (n,d,t) -> {}); }
    @Test public void realContentResolverCreatesFreshTreeKeepsEmptyFoldersAndNeverOverwritesExistingFiles() throws Exception {
        DocumentTreeExport target = new DocumentTreeExport(resolver, tree, "demo");
        ProjectExport.copy(plan(), target, () -> false, (n,d,t) -> {}); target.finish();
        Map<String, Uri> top = children(root()); assertEquals(2, top.size()); assertEquals("existing-user-file", read(top.get("keep.txt")));
        Map<String, Uri> exported = children(top.get(target.name)); assertEquals(2, exported.size());
        assertEquals("example", read(exported.get(".env.example")));
        Map<String, Uri> nested = children(exported.get("中文")); assertTrue(children(nested.get("empty")).isEmpty());
        assertEquals("HTML 内容", read(nested.get("page.html")));
    }
    @Test public void cancelledCopyLeavesExplicitIncompleteMarkerAndOriginalFiles() throws Exception {
        DocumentTreeExport target = new DocumentTreeExport(resolver, tree, "demo");
        assertThrows(IOException.class, () -> ProjectExport.copy(plan(), target, () -> true, (n,d,t) -> {}));
        Uri output = children(root()).get(target.name); assertTrue(children(output).keySet().stream().anyMatch(name -> name.endsWith(".incomplete")));
        assertEquals("HTML 内容", IO.text(new File(workspace, "demo/中文/page.html")));
    }
    @Test public void symlinksAreListedAsSkippedAndCannotLeakExternalContents() throws Exception {
        File outside = new File(fixture, "secret"); IO.text(outside, "do-not-export");
        Files.createSymbolicLink(new File(workspace, "demo/link").toPath(), outside.toPath());
        var plan = plan(); assertEquals(1, plan.skippedCount); assertTrue(plan.summary().contains("link"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ProjectExport.zip(plan, bytes, () -> false, (n,d,t) -> {});
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) { assertFalse(entry.getName().endsWith("link")); assertFalse(new String(IO.bytes(zip), StandardCharsets.UTF_8).contains("do-not-export")); }
        }
        var oldPlan = plan(); Files.delete(new File(workspace, "demo/中文/page.html").toPath());
        Files.createSymbolicLink(new File(workspace, "demo/中文/page.html").toPath(), outside.toPath());
        assertThrows(IOException.class, () -> ProjectExport.zip(oldPlan, new ByteArrayOutputStream(), () -> false, (n,d,t) -> {}));
    }
    @Test public void folderPickerStateSurvivesRecreationAndCancellationClearsIt() {
        Bundle saved = new Bundle(); saved.putString("folder.project", project.path); saved.putString("folder.title", project.title);
        saved.putString("folder.relative", "中文"); saved.putInt("folder.request", ProjectFolderExporter.TREE);
        try (var scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ProjectFolderExporter exporter = new ProjectFolderExporter(activity, files, fixture, saved);
                Bundle restored = new Bundle(); exporter.save(restored); assertEquals("中文", restored.getString("folder.relative"));
                assertFalse(exporter.result(LogExporter.EXPORT, Activity.RESULT_CANCELED, null));
                assertTrue(exporter.result(ProjectFolderExporter.TREE, Activity.RESULT_CANCELED, null));
                Bundle cleared = new Bundle(); exporter.save(cleared); assertFalse(cleared.containsKey("folder.project")); exporter.close();
            });
        }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, ProjectFolderExporter.intent(ProjectFolderExporter.TREE, "demo").getAction());
        assertEquals("application/zip", ProjectFolderExporter.intent(ProjectFolderExporter.ZIP, "demo").getType());
    }
    @Test public void zipResultCallbackWritesArchiveAndCleansTemporaryFile() throws Exception {
        Uri destination = DocumentsContract.createDocument(resolver, root(), "application/zip", "test.zip");
        Bundle saved = new Bundle(); saved.putString("folder.project", project.path); saved.putString("folder.title", project.title);
        saved.putString("folder.relative", ""); saved.putInt("folder.request", ProjectFolderExporter.ZIP);
        AtomicReference<ProjectFolderExporter> exporter = new AtomicReference<>();
        try (var scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                exporter.set(new ProjectFolderExporter(activity, files, fixture, saved));
                assertTrue(exporter.get().result(ProjectFolderExporter.ZIP, Activity.RESULT_OK, new Intent().setData(destination)));
            });
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10); boolean done = false;
            while (System.nanoTime() < deadline) {
                Thread.sleep(100);
                try (ZipInputStream zip = new ZipInputStream(resolver.openInputStream(destination))) {
                    Set<String> entries = new HashSet<>(); ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) { entries.add(entry.getName()); IO.bytes(zip); }
                    if (entries.contains("demo/中文/page.html") && entries.contains("demo/中文/empty/")) { done = true; break; }
                } catch (IOException unfinished) { }
            }
            assertTrue("ZIP callback completes", done); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> exporter.get().close());
            assertFalse(Arrays.stream(fixture.listFiles()).anyMatch(file -> file.getName().startsWith("folder-export-")));
        }
    }
}
