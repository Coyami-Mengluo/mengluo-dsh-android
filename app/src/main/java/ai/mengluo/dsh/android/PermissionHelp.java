package ai.mengluo.dsh.android;

import android.content.SharedPreferences;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.graphics.Color;
import android.content.res.ColorStateList;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Optional guidance only: never changes Harness permissions or executes a command. */
final class PermissionHelp {
    static final String SIGNAL = "mengluo:sandbox-unavailable-help";
    static final String SCRIPT_PATH = "/__mengluo_permission_help__.js";
    static final String INTRO_SEEN = "install-hint-dismissed";
    static final String RUNTIME_SEEN = "runtime-hint-shown";
    private static final String SUMMARY = "安卓执行代码时，若提示 Bash 沙箱不可用，可对可信任务使用「完全访问（Full access）」。";
    private static final String RISK = "完全访问会放开工作区限制，命令可能读写本 App 的其他项目、配置和 API 密钥。仅运行可信代码。";
    private final AppCompatActivity activity;
    private final Ui ui;
    private final SharedPreferences preferences;
    private LinearLayout introduction, notice;
    private HintBubble bubble;
    private AlertDialog guide;
    private boolean pending;

    PermissionHelp(AppCompatActivity activity, Ui ui) {
        this(activity, ui, activity.getSharedPreferences("permission-help", 0));
    }

    PermissionHelp(AppCompatActivity activity, Ui ui, SharedPreferences preferences) {
        this.activity = activity; this.ui = ui;
        this.preferences = preferences;
    }

    View installationCard() {
        introduction = ui.card(); introduction.setTag("permission-introduction");
        introduction.addView(ui.text("运行代码前", 17, true));
        introduction.addView(ui.caption(SUMMARY + "\n" + RISK));
        ui.gap(introduction, 6);
        introduction.addView(ui.pair(ui.button("如何切换", 0, false, this::showGuide), ui.button("知道了", 0, false, () -> {
            preferences.edit().putBoolean(INTRO_SEEN, true).apply();
            introduction.setVisibility(View.GONE);
        })));
        return introduction;
    }

    void refresh(boolean installed) {
        if (introduction != null) introduction.setVisibility(installed || preferences.getBoolean(INTRO_SEEN, false) ? View.GONE : View.VISIBLE);
    }

    void showGuide() {
        if (activity.isFinishing() || activity.isDestroyed() || (guide != null && guide.isShowing())) return;
        guide = new MaterialAlertDialogBuilder(activity).setTitle("完全访问 · 使用说明")
            .setMessage("在 Harness 会话中，点击输入框下方的权限选项（通常为「工作区内修改 / Workspace Write」），选择「完全访问 / Full access」，阅读并确认官方提示，再重试任务。\n\n"
                + RISK + "同时会减少操作确认，但不会获得手机 Root 权限。\n\n"
                + "这里不会替你切换权限。缺少浏览器、依赖或代码本身出错，仍需单独处理。")
            .setPositiveButton("知道了", null).create();
        guide.setOnDismissListener(ignored -> guide = null); guide.show();
    }

    void observePage(WebView web, String url, String readyUrl) {
        if (preferences.getBoolean(RUNTIME_SEEN, false) || !RuntimePolicy.trustedPage(url, readyUrl)) return;
        String origin = "http://127.0.0.1:" + Uri.parse(readyUrl).getPort();
        try (InputStream input = activity.getAssets().open("permission-hint.js")) {
            String script = new String(IO.bytes(input), StandardCharsets.UTF_8);
            web.evaluateJavascript(script + "(" + JSONObject.quote(origin) + ");\n//# sourceURL=" + origin + SCRIPT_PATH, null);
        } catch (java.io.IOException missing) {
            // The optional help must not prevent the official page from loading.
            android.util.Log.w("MengLuoPermissionHelp", "Permission guidance asset is unavailable", missing);
        }
    }

    boolean onConsoleMessage(ConsoleMessage message, WebView web, String readyUrl, FrameLayout frame) {
        if (!SIGNAL.equals(message.message()) || message.messageLevel() != ConsoleMessage.MessageLevel.LOG) return false;
        if (readyUrl == null || !RuntimePolicy.trustedPage(web.getUrl(), readyUrl)) return true;
        String source = "http://127.0.0.1:" + Uri.parse(readyUrl).getPort() + SCRIPT_PATH;
        if (!source.equals(message.sourceId()) || preferences.getBoolean(RUNTIME_SEEN, false)) return true;
        pending = true;
        if (web.getVisibility() == View.VISIBLE) showPending(frame);
        return true;
    }

    void showPending(FrameLayout frame) {
        if (!pending || preferences.getBoolean(RUNTIME_SEEN, false) || activity.isFinishing() || activity.isDestroyed()) return;
        pending = false;
        // Persist when shown, not only when dismissed: rotation/restart must not nag again.
        preferences.edit().putBoolean(RUNTIME_SEEN, true).apply();
        notice = ui.column(14); notice.setTag("permission-runtime-hint");
        bubble = new HintBubble(ui); notice.setBackground(bubble); notice.setVisibility(View.INVISIBLE);
        LinearLayout heading = new LinearLayout(activity); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(ui.text("代码运行提示", 14, true), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton close = new ImageButton(activity); close.setImageResource(R.drawable.ic_close);
        close.setTag("permission-hint-close");
        close.setImageTintList(ColorStateList.valueOf(ui.color(R.color.muted))); close.setBackgroundColor(Color.TRANSPARENT);
        close.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); close.setContentDescription("关闭提示");
        close.setOnClickListener(ignored -> dismissNotice()); heading.addView(close, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        notice.addView(heading);
        notice.addView(ui.caption("沙箱不可用时，可尝试「完全访问」。它会放开工作区限制，仅限可信代码。"));
        TextView details = ui.text("查看说明", 13, true); details.setTextColor(ui.color(R.color.accent)); details.setGravity(Gravity.CENTER_VERTICAL);
        details.setMinHeight(ui.dp(44)); details.setOnClickListener(ignored -> { dismissNotice(); showGuide(); }); notice.addView(details);
        int available = frame.getWidth() - frame.getPaddingLeft() - frame.getPaddingRight() - ui.dp(64);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Math.max(ui.dp(160), Math.min(ui.dp(280), available)), -2);
        frame.addView(notice, params);
        notice.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> positionNotice(frame));
        notice.post(() -> { if (notice != null) { positionNotice(frame); notice.setVisibility(View.VISIBLE); } });
    }

    void positionNotice(FrameLayout frame) {
        if (notice == null || notice.getWidth() == 0) return;
        View ball = frame.findViewWithTag("menu-ball");
        if (ball == null) return;
        boolean right = ball.getX() + ball.getWidth() / 2f >= frame.getWidth() / 2f;
        int leftPadding = ui.dp(right ? 14 : 23), rightPadding = ui.dp(right ? 23 : 14);
        if (notice.getPaddingLeft() != leftPadding || notice.getPaddingRight() != rightPadding)
            notice.setPadding(leftPadding, ui.dp(4), rightPadding, ui.dp(2));
        float center = ball.getY() + ball.getHeight() / 2f;
        float x = right ? ball.getX() - notice.getWidth() - ui.dp(2) : ball.getX() + ball.getWidth() + ui.dp(2);
        float left = frame.getPaddingLeft() + ui.dp(6), top = frame.getPaddingTop() + ui.dp(6);
        notice.setX(Math.max(left, Math.min(frame.getWidth() - frame.getPaddingRight() - notice.getWidth() - ui.dp(6), x)));
        notice.setY(Math.max(top, Math.min(frame.getHeight() - frame.getPaddingBottom() - notice.getHeight() - ui.dp(6), center - notice.getHeight() / 2f)));
        bubble.anchor(right, center - notice.getY());
    }

    void dismissNotice() {
        if (notice != null && notice.getParent() instanceof FrameLayout parent) parent.removeView(notice);
        notice = null;
        bubble = null;
    }

    void close() {
        dismissNotice();
        if (guide != null) guide.dismiss();
    }
}
