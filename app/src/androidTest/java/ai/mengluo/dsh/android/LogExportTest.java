package ai.mengluo.dsh.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LogExportTest {
    @Test public void usesUserSelectedTextDocumentAndKeepsPendingState() {
        Intent intent = LogExporter.intent();
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction()); assertEquals("text/plain", intent.getType());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE)); assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".log"));
        Bundle saved = new Bundle(); saved.putBoolean("logs.export-pending", true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                LogExporter exporter = new LogExporter(activity, Engine.get(activity), saved);
                Bundle restored = new Bundle(); exporter.save(restored); assertTrue(restored.getBoolean("logs.export-pending"));
                assertFalse(exporter.result(ProjectBrowser.EXPORT, Activity.RESULT_CANCELED, null));
                assertTrue(exporter.result(LogExporter.EXPORT, Activity.RESULT_CANCELED, null));
                Bundle cleared = new Bundle(); exporter.save(cleared); assertFalse(cleared.getBoolean("logs.export-pending")); exporter.close();
            });
        }
    }
    @Test public void pickerCallbackExportsLongHistoryWithSecretsRedacted() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation(); Context context = instrumentation.getTargetContext();
        File directory = new File(context.getFilesDir(), "updates"); directory.mkdirs();
        File destination = new File(directory, "log-export-test-" + UUID.randomUUID() + ".log");
        var uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", destination);
        IntentFilter picker = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE); picker.addDataType("text/plain");
        var monitor = instrumentation.addMonitor(picker,
            new Instrumentation.ActivityResult(Activity.RESULT_OK, new Intent().setData(uri)), true);
        String marker = "export-marker-" + UUID.randomUUID();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Engine engine = Engine.get(context); engine.note(marker);
            for (int i = 0; i < 900; i++) engine.note("export-fixture " + i + "x".repeat(80));
            engine.note("?token=export-secret-token API_KEY=export-secret-key");
            assertFalse(engine.logs().contains(marker));
            scenario.onActivity(activity -> {
                try {
                    // Exercise the real dialog button and MainActivity result callback, not just the writer.
                    var method = MainActivity.class.getDeclaredMethod("logs"); method.setAccessible(true); method.invoke(activity);
                    for (android.view.View window : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                        android.widget.Button button = window.findViewById(android.R.id.button3);
                        if (button != null && "导出日志".contentEquals(button.getText())) { button.performClick(); return; }
                    }
                    fail("Export button missing");
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            String exported = "";
            while (System.nanoTime() < until) {
                if (destination.isFile()) { exported = IO.text(destination); if (exported.contains("export-fixture 899") && exported.contains("API_KEY=[REDACTED]")) break; }
                Thread.sleep(100);
            }
            assertEquals(1, monitor.getHits()); assertTrue(exported.contains(marker)); assertTrue(exported.contains("export-fixture 899"));
            assertFalse(exported.contains("export-secret-token")); assertFalse(exported.contains("export-secret-key"));
        } finally { instrumentation.removeMonitor(monitor); Files.deleteIfExists(destination.toPath()); }
    }
}
