package ai.mengluo.dsh.android;

import java.util.regex.Pattern;

/** Display-only classification. Stored/exported log text is never rewritten. */
final class LogLevel {
    enum Level { NORMAL, INFO, SUCCESS, WARNING, ERROR }
    private static final Pattern TIMESTAMP = Pattern.compile("^\\[[0-9]{4}-[^]]+]\\s*");
    private static final Pattern REQUESTED_STOP = Pattern.compile("^\\[runtime] Harness 已退出，退出码 -?[0-9]+（已请求停止）$");
    private static final Pattern ERROR = Pattern.compile("(?i)(?:^|[\\s\\[:])(?:error|fatal|exception|eacces|eperm|enoent|err_[a-z_]+)(?:$|[\\s\\]:])|\\b[a-z]+Exception\\b|(?:失败|错误|拒绝访问|权限不足|超时)|(?:退出码|exit code)\\s*[1-9][0-9]*");
    private static final Pattern WARNING = Pattern.compile("(?i)(?:^|[\\s\\[:])(?:warn(?:ing)?|deprecated)(?:$|[\\s\\]:])|(?:警告|重试|回退|未授权|不可用)");
    private static final Pattern SUCCESS = Pattern.compile("(?i)^(?:\\[[^]]+]\\s*)*(?:[✓✔]|success\\b|installed\\b|ready\\b)|(?:安装完成|更新完成|验证通过|测试通过|启动成功)");
    private static final Pattern INFO = Pattern.compile("(?i)^\\[(?:info|desktop|runtime|notifications|web)]|^(?:启动 |正在|准备|下载|解压|校验)");

    static int timestampEnd(String line) {
        var match = TIMESTAMP.matcher(line); return match.find() ? match.end() : 0;
    }
    static Level of(String line) {
        String body = line.substring(timestampEnd(line));
        if (REQUESTED_STOP.matcher(body).matches()) return Level.INFO;
        if (ERROR.matcher(body).find()) return Level.ERROR;
        if (WARNING.matcher(body).find()) return Level.WARNING;
        if (SUCCESS.matcher(body).find()) return Level.SUCCESS;
        if (INFO.matcher(body).find()) return Level.INFO;
        return Level.NORMAL;
    }
    private LogLevel() {}
}
