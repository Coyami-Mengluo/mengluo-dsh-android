package ai.mengluo.dsh.android;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.File;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class RuntimeProcessesTest {
    @Test public void stopsOnlyOwnedLaunchedTree() throws Exception {
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        Process first = RuntimeProcesses.launch(new ProcessBuilder("/system/bin/sh", "-c", "sleep 300 & wait"), cache);
        Process other = RuntimeProcesses.launch(new ProcessBuilder("/system/bin/sh", "-c", "sleep 300 & wait"), cache);
        try { assertTrue(RuntimeProcesses.stopAndWait(first, 2)); assertFalse(first.isAlive()); assertTrue(other.isAlive()); }
        finally { assertTrue(RuntimeProcesses.stopAndWait(other, 2)); }
    }
}
