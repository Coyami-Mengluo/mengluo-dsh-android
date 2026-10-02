package ai.mengluo.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;
import static ai.mengluo.dsh.android.LogLevel.Level.*;

public class LogLevelTest {
    @Test public void recognizesRuntimeAndPackageFailures() {
        for (String line : new String[]{"[2026-10-02T12:00:00Z] 安装失败：命令退出码 100", "ERROR: ld.so: cannot load", "npm error EACCES", "java.io.IOException: denied", "dpkg: error processing archive", "Error: operation timed out"})
            assertEquals(line, ERROR, LogLevel.of(line));
    }
    @Test public void failureTakesPrecedenceOverWarning() {
        assertEquals(ERROR, LogLevel.of("[WARN] 请求失败后重试"));
    }
    @Test public void requestedStopIsNotShownAsACrashButUnexpectedExitRemainsAnError() {
        assertEquals(INFO, LogLevel.of("[2026-10-02T12:00:00Z] [runtime] Harness 已退出，退出码 143（已请求停止）"));
        assertEquals(ERROR, LogLevel.of("[runtime] Harness 已退出，退出码 17（进程自行结束）"));
        assertEquals(ERROR, LogLevel.of("[runtime] Harness 启动超时：超过 120 秒仍未通过页面检查"));
    }
    @Test public void successesAndNormalOutput() {
        assertEquals(WARNING, LogLevel.of("npm WARN deprecated example"));
        assertEquals(SUCCESS, LogLevel.of("[2026-10-02T12:00:00Z] 安装完成"));
        assertEquals(INFO, LogLevel.of("[web] 浏览器接口检查"));
        assertEquals(NORMAL, LogLevel.of("/workspace/errors/example.js"));
        assertEquals(NORMAL, LogLevel.of("0 errors, 0 warnings"));
        assertEquals(NORMAL, LogLevel.of("node=v24.19.0 platform=linux arch=arm64"));
        assertEquals(0, LogLevel.timestampEnd("[web] hello"));
        assertTrue(LogLevel.timestampEnd("[2026-10-02T12:00:00Z] hello") > 0);
    }
}
