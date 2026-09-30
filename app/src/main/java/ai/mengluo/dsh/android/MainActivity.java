package ai.mengluo.dsh.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Native setup plus the official UI; file-opening requests always require native confirmation. */
public final class MainActivity extends AppCompatActivity {
    private Engine engine;
    private Ui ui;
    private FrameLayout frame;
    private LinearLayout home;
    private WebView web;
    private PageZoom pageZoom;
    private SystemFiles systemFiles;
    private WebFiles webFiles;
    private ImageButton ball;
    private TextView state, statusChip, runtimeVersion;
    private Button updatesButton;
    private UpdatesDialog updatesDialog;
    private AndroidUpdates updates;
    private final Runnable updateObserver = () -> {
        if (updatesButton != null) updatesButton.setText(updates.available() ? "发现新版本 · 更新管理" : "更新管理");
        if (hasWindowFocus() && !isFinishing()) {
            String reminder = updates.takeReminder();
            if (reminder != null) com.google.android.material.snackbar.Snackbar.make(frame, reminder, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("查看", v -> updatesDialog.show()).show();
        }
    };
    private ProgressBar progress;
    private Button install, launch, sourceButton;
    private String loadedUrl;
    private ScriptHandler compatibilityScript;
    private ProjectBrowser projectBrowser;
    private WorkspaceDialog workspaceDialog;
    private TextView workspacePath;
    private LogExporter logExporter;
    private PermissionHelp permissionHelp;
    private boolean ballCollapsed, ballTouching, ballMenuOpen;
    private final Runnable collapseBall = () -> {
        if (isDestroyed() || ballTouching || ballMenuOpen || frame.getWidth() == 0) return;
        ballCollapsed = true;
        boolean right = getPreferences(0).getBoolean("ballRight", true);
        ball.animate().cancel();
        ball.animate().x(collapsedBallX(right)).alpha(.65f).setDuration(220)
            .setUpdateListener(animation -> updateBallGestureExclusion())
            .withEndAction(this::updateBallGestureExclusion).start();
    };
    private Insets gestures = Insets.NONE;
    private final Consumer<Engine> observer = ignored -> refresh();
    private int dp(float size) { return Math.round(getResources().getDisplayMetrics().density * size); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        engine = Engine.get(this);
        updates = AndroidUpdates.get(this); updatesDialog = new UpdatesDialog(this);
        ballCollapsed = saved != null && saved.getBoolean("ball-collapsed", false);
        ui = new Ui(this);
        permissionHelp = new PermissionHelp(this, ui);
        projectBrowser = createProjectBrowser(saved);
        workspaceDialog = new WorkspaceDialog(this, engine, saved, this::refresh);
        logExporter = new LogExporter(this, engine, saved);
        frame = new FrameLayout(this); frame.setBackgroundColor(ui.color(R.color.page));
        frame.setTag("shell-frame");
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT); getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
            Insets safe = safeInsets(insets);
            gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            view.post(() -> { sizeHome(); restoreBall(); });
            // The WebView receives the safe viewport; don't apply the same bar space twice.
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(frame);
        var bars = WindowCompat.getInsetsController(getWindow(), frame);
        boolean light = getResources().getBoolean(R.bool.light_system_bars);
        bars.setAppearanceLightStatusBars(light); bars.setAppearanceLightNavigationBars(light);
        web = new WebView(this); web.setTag("harness-web"); web.setVisibility(View.GONE);
        pageZoom = new PageZoom(this, web, () -> engine.readyUrl);
        systemFiles = new SystemFiles(this, projectFiles());
        webFiles = new WebFiles(web, () -> engine.readyUrl, systemFiles, () -> projectBrowser.showProjects());
        web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.getSettings().setSupportMultipleWindows(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (permissionHelp.onConsoleMessage(message, web, engine.readyUrl, frame)) return true;
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) engine.note("[web] " + message.message());
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String destination = request.getUrl().toString();
                if (engine.readyUrl != null && RuntimePolicy.trustedPage(destination, engine.readyUrl)) return false;
                if (request.isForMainFrame()) externalLink(request.getUrl());
                return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) { engine.note("页面加载失败：" + error.getDescription()); toast("页面加载失败，可从悬浮菜单返回运行环境查看日志"); }
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (engine.readyUrl != null && RuntimePolicy.trustedPage(url, engine.readyUrl)) {
                    pageZoom.apply(); webFiles.finished(url);
                    view.evaluateJavascript("JSON.stringify({title:document.title,promise:typeof Promise.withResolvers,abort:typeof AbortSignal.any})", value -> engine.note("[web] 浏览器接口检查：" + value));
                    permissionHelp.observePage(view, url, engine.readyUrl);
                }
            }
        });
        frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        buildHome();
        addBall(); engine.listen(observer); ViewCompat.requestApplyInsets(frame);
        updates.listen(updateObserver); updates.automaticCheck();
    }
    static Insets safeInsets(WindowInsetsCompat insets) {
        return insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.mandatorySystemGestures());
    }
    private void sizeHome() {
        if (home == null || frame.getWidth() == 0) return;
        int available = Math.max(0, frame.getWidth() - frame.getPaddingLeft() - frame.getPaddingRight());
        int width = Math.min(dp(getResources().getConfiguration().screenWidthDp >= 840 ? 980 : 640), available);
        if (home.getLayoutParams().width != width) { home.getLayoutParams().width = width; home.requestLayout(); }
    }
    private void buildHome() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout center = ui.column(0); center.setGravity(Gravity.CENTER_HORIZONTAL); scroll.addView(center);
        home = ui.column(20); home.setPadding(dp(20), dp(28), dp(20), dp(28));
        boolean wide = getResources().getConfiguration().screenWidthDp >= 840;
        center.addView(home, new LinearLayout.LayoutParams(Math.min(dp(wide ? 980 : 640), getResources().getDisplayMetrics().widthPixels), -2));
        LinearLayout intro = home, panels = home;
        if (wide) {
            home.setOrientation(LinearLayout.HORIZONTAL); intro = ui.column(0); panels = ui.column(0);
            intro.setPadding(dp(8), dp(8), dp(32), 0);
            home.addView(intro, new LinearLayout.LayoutParams(0, -2, 1)); home.addView(panels, new LinearLayout.LayoutParams(0, -2, 1.1f));
        }
        scroll.setTag("home"); frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout brandRow = new LinearLayout(this); brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brand = new ImageView(this); brand.setImageResource(R.drawable.app_icon); brand.setContentDescription("MengLuo 图标");
        brandRow.addView(brand, new LinearLayout.LayoutParams(dp(68), dp(68)));
        LinearLayout brandText = ui.column(0); brandText.setPadding(dp(16), 0, 0, 0);
        brandText.addView(label("MengLuo", 28, true)); TextView overline = ui.caption("DSH ANDROID"); overline.setLetterSpacing(.12f); brandText.addView(overline);
        brandRow.addView(brandText); intro.addView(brandRow); ui.gap(intro, 22);
        intro.addView(label("你的 AI 编程工作区", 24, true));
        intro.addView(ui.caption("基于 DeepSeek Harness，编写代码、运行脚本、管理项目。")); ui.gap(intro, 20);
        panels.addView(permissionHelp.installationCard());

        LinearLayout runtime = ui.card(); LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("Harness", 20, true), new LinearLayout.LayoutParams(0, -2, 1));
        statusChip = label("已就绪", 12, true); statusChip.setTextColor(ui.color(R.color.success)); statusChip.setBackground(ui.rounded(ui.color(R.color.success_surface), 30));
        statusChip.setPadding(dp(12), dp(6), dp(12), dp(6)); heading.addView(statusChip); runtime.addView(heading);
        runtimeVersion = ui.caption(""); runtime.addView(runtimeVersion); ui.gap(runtime, 8);
        state = ui.caption(engine.status); runtime.addView(state); ui.gap(runtime, 10);
        LinearProgressIndicator indicator = new LinearProgressIndicator(this); indicator.setIndicatorColor(ui.color(R.color.accent));
        indicator.setTrackColor(ui.color(R.color.tonal)); indicator.setTrackCornerRadius(dp(4)); indicator.setIndeterminate(true); progress = indicator;
        runtime.addView(progress, new LinearLayout.LayoutParams(-1, dp(6))); ui.gap(runtime, 8);
        launch = ui.button("启动 Harness", R.drawable.ic_arrow, true, () -> { if (engine.readyUrl != null) showHarness(); else service("start"); });
        runtime.addView(launch, new LinearLayout.LayoutParams(-1, -2));
        install = button("安装 Harness", () -> { updatesDialog.show(); if (updates.releases.isEmpty()) updates.checkHarness(true); });
        runtime.addView(install, new LinearLayout.LayoutParams(-1, -2)); panels.addView(runtime);

        LinearLayout download = ui.card(); download.addView(label("下载源", 17, true));
        download.addView(ui.caption("为基础环境、安装依赖、Harness 和 npm 插件选择下载线路。")); ui.gap(download, 6);
        sourceButton = ui.button(engine.downloadSource().title, R.drawable.ic_download, false, this::downloads);
        download.addView(sourceButton, new LinearLayout.LayoutParams(-1, -2)); panels.addView(download);
        LinearLayout directory = ui.card(); directory.addView(label("手机文件访问", 17, true));
        workspacePath = ui.caption(""); directory.addView(workspacePath);
        directory.addView(ui.button("文件权限", R.drawable.ic_workspace, false, workspaceDialog::show), new LinearLayout.LayoutParams(-1, -2));
        panels.addView(directory);
        updatesButton = ui.button("更新管理", R.drawable.ic_download, false, updatesDialog::show);
        panels.addView(updatesButton, new LinearLayout.LayoutParams(-1, -2));
        intro.addView(ui.pair(ui.button("代码文件", R.drawable.ic_workspace, false, () -> projectBrowser.showProjects()), ui.button("终端", R.drawable.ic_terminal, false, this::terminal)));
        ui.gap(intro, 8); MaterialButton logs = ui.button("查看运行日志", R.drawable.ic_logs, false, this::logs);
        logs.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)); intro.addView(logs, new LinearLayout.LayoutParams(-1, -2));
        MaterialButton permissions = ui.button("运行权限说明", 0, false, permissionHelp::showGuide);
        permissions.setTag("permission-help-button");
        permissions.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        intro.addView(permissions, new LinearLayout.LayoutParams(-1, -2));
    }
    private TextView label(String text, int size, boolean bold) {
        return ui.text(text, size, bold);
    }
    private Button button(String text, Runnable action) {
        return ui.button(text, 0, false, action);
    }
    private void service(String action) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        startForegroundService(new Intent(this, EngineService.class).setAction(action));
    }
    private void refresh() {
        if (isDestroyed()) return;
        workspacePath.setText(engine.workspaces.hasAccess() ? "已授权 · 直接在 Harness 中选择项目路径" : "授权后可在 Harness 中选择手机公共目录");
        state.setText(engine.status); progress.setVisibility(engine.busy ? View.VISIBLE : View.GONE);
        sourceButton.setText(engine.downloadSource().title);
        sourceButton.setEnabled(!engine.busy && !engine.checkingSource);
        boolean installed = engine.installed();
        runtimeVersion.setText(installed ? "版本 " + engine.currentVersion() : "首次安装时选择官方版本");
        permissionHelp.refresh(installed);
        statusChip.setText(engine.readyUrl != null ? "运行中" : engine.busy ? "处理中" : installed ? "已就绪" : "待安装");
        install.setEnabled(!engine.busy && !engine.checkingSource && engine.readyUrl == null); install.setText(installed ? "管理 Harness 版本" : "选择并安装 Harness");
        install.setVisibility(engine.readyUrl == null ? View.VISIBLE : View.GONE);
        launch.setVisibility(installed ? View.VISIBLE : View.GONE); launch.setEnabled(engine.readyUrl != null || !engine.busy);
        launch.setText(engine.readyUrl != null ? "回到 Harness" : "启动 Harness");
        if (engine.readyUrl != null && !engine.readyUrl.equals(loadedUrl)) {
            loadedUrl = engine.readyUrl;
            try { webFiles.install(loadedUrl); }
            catch (IOException error) { engine.note("系统文件打开适配未加载：" + error.getMessage()); }
            if (compatibilityScript != null) { compatibilityScript.remove(); compatibilityScript = null; }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                try (InputStream input = getAssets().open("web-compat.js")) {
                    String origin = "http://127.0.0.1:" + Uri.parse(loadedUrl).getPort();
                    String script = new String(IO.bytes(input), StandardCharsets.UTF_8) + "\n" + DirectoryDefaults.script(this, engine.workspaces.sharedRoot);
                    compatibilityScript = WebViewCompat.addDocumentStartJavaScript(web, script, Set.of(origin));
                } catch (Exception error) { engine.note("浏览器兼容处理失败：" + error.getMessage()); }
            } else engine.note("当前 WebView 不支持启动兼容脚本；若页面提示重连，请更新 Android System WebView");
            web.loadUrl(loadedUrl); showHarness();
        }
    }
    private void showHarness() {
        if (engine.readyUrl == null) { toast("请先启动 Harness"); return; }
        web.setVisibility(View.VISIBLE); frame.findViewWithTag("home").setVisibility(View.GONE); ball.bringToFront();
        permissionHelp.showPending(frame);
    }
    private void showHome() { permissionHelp.dismissNotice(); frame.findViewWithTag("home").setVisibility(View.VISIBLE); web.setVisibility(View.GONE); ball.bringToFront(); }
    private void addBall() {
        ball = new ImageButton(this); ball.setImageResource(R.drawable.menu_icon); ball.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ball.setTag("menu-ball"); ball.setContentDescription("MengLuo 菜单"); ball.setElevation(dp(6));
        GradientDrawable background = new GradientDrawable(); background.setColor(ui.color(R.color.surface)); background.setShape(GradientDrawable.OVAL);
        background.setStroke(dp(1), ui.color(R.color.outline)); ball.setBackground(background);
        // Set padding after the background; keep the whole square artwork inside the circle.
        ball.setPadding(dp(9), dp(9), dp(9), dp(9)); ball.setClipToOutline(true);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(52), dp(52)); frame.addView(ball, params);
        frame.post(() -> { restoreBall(); if (!ballCollapsed) scheduleBallCollapse(2000); });
        frame.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            sizeHome(); restoreBall();
        });
        ball.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> restoreBall());
        ball.setOnClickListener(view -> menu());
        ball.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY; boolean moved;
            @Override public boolean onTouch(View view, android.view.MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    ballTouching = true; expandBall();
                    downX = event.getRawX(); downY = event.getRawY(); startX = ball.getX(); startY = ball.getY(); moved = false; return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) moved = true;
                    if (moved) { ball.setX(Math.max(ballMinX(), Math.min(ballMaxX(), startX + dx))); ball.setY(Math.max(ballMinY(), Math.min(ballMaxY(), startY + dy))); updateBallGestureExclusion(); }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    ballTouching = false;
                    if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP) ball.performClick();
                    else { boolean right = ball.getX() + ball.getWidth() / 2f > frame.getWidth() / 2f; float y = (ball.getY() - ballMinY()) / Math.max(1, ballMaxY() - ballMinY());
                        positionBall(right, y); getPreferences(0).edit().putBoolean("ballRight", right).putFloat("ballY", y).apply(); }
                    if (!ballMenuOpen) scheduleBallCollapse(900);
                    return true;
                }
                return false;
            }
        });
    }
    private int ballMinX() { return Math.max(frame.getPaddingLeft(), gestures.left) + dp(6); }
    private int ballMaxX() { return Math.max(ballMinX(), frame.getWidth() - Math.max(frame.getPaddingRight(), gestures.right) - dp(58)); }
    private int ballMinY() { return frame.getPaddingTop() + dp(6); }
    private int ballMaxY() { return Math.max(ballMinY(), frame.getHeight() - Math.max(frame.getPaddingBottom(), gestures.bottom) - dp(58)); }
    private void restoreBall() {
        if (ball != null && frame.getWidth() > 0 && !ballTouching)
            positionBall(getPreferences(0).getBoolean("ballRight", true), getPreferences(0).getFloat("ballY", .4f));
    }
    private void positionBall(boolean right, float fraction) {
        ball.animate().cancel();
        ball.setX(ballCollapsed ? collapsedBallX(right) : right ? ballMaxX() : ballMinX());
        ball.setAlpha(ballCollapsed ? .65f : 1f);
        ball.setY(ballMinY() + (ballMaxY() - ballMinY()) * Math.max(0, Math.min(1, fraction)));
        updateBallGestureExclusion();
    }
    private float collapsedBallX(boolean right) {
        // Keep a 24dp visible handle inside the safe viewport, not under system bars.
        return right ? frame.getWidth() - frame.getPaddingRight() - dp(24)
            : frame.getPaddingLeft() - dp(28);
    }
    private void scheduleBallCollapse(long delay) {
        ball.removeCallbacks(collapseBall); ball.postDelayed(collapseBall, delay);
    }
    private void expandBall() {
        ball.removeCallbacks(collapseBall); ballCollapsed = false;
        positionBall(getPreferences(0).getBoolean("ballRight", true), getPreferences(0).getFloat("ballY", .4f));
    }
    private void updateBallGestureExclusion() {
        int left = Math.max(frame.getPaddingLeft(), Math.round(ball.getX()));
        int right = Math.min(frame.getWidth() - frame.getPaddingRight(), Math.round(ball.getX()) + dp(52));
        int top = Math.max(frame.getPaddingTop(), Math.round(ball.getY()));
        int bottom = Math.min(frame.getHeight() - frame.getPaddingBottom(), top + dp(52));
        // Only this small handle excludes Android's back gesture; the rest of the edge stays available.
        frame.setSystemGestureExclusionRects(right > left && bottom > top ? List.of(new Rect(left, top, right, bottom)) : List.of());
        if (permissionHelp != null) permissionHelp.positionNotice(frame);
    }
    private void menu() {
        permissionHelp.dismissNotice();
        expandBall(); ballMenuOpen = true;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setOnDismissListener(ignored -> { ballMenuOpen = false; if (!isDestroyed()) scheduleBallCollapse(1200); });
        LinearLayout content = ui.column(24); content.setTag("menu-content");
        View handle = new View(this); handle.setBackground(ui.rounded(ui.color(R.color.outline), 4));
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(dp(36), dp(4)); handleSize.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(handle, handleSize); ui.gap(content, 16); content.addView(label("快捷菜单", 23, true));
        content.addView(ui.caption("MengLuo · 让创作更简单")); ui.gap(content, 14);
        content.addView(ui.button("回到 Harness", R.drawable.ic_arrow, true, () -> { sheet.dismiss(); showHarness(); }), new LinearLayout.LayoutParams(-1, -2));
        ui.gap(content, 10); content.addView(pageZoom.controls(ui));
        content.addView(ui.button("手机文件访问", R.drawable.ic_workspace, false, () -> { sheet.dismiss(); workspaceDialog.show(); }), new LinearLayout.LayoutParams(-1, -2));
        String[] names = {"代码文件", "终端", "插件管理", "下载源", "更新管理", "权限说明", "运行环境", "运行日志"};
        int[] icons = {R.drawable.ic_workspace, R.drawable.ic_terminal, R.drawable.ic_plugins, R.drawable.ic_download, R.drawable.ic_download, R.drawable.ic_home, R.drawable.ic_home, R.drawable.ic_logs};
        Runnable[] actions = {projectBrowser::showProjects, this::terminal, this::plugins, this::downloads, updatesDialog::show, permissionHelp::showGuide, this::showHome, this::logs};
        for (int i = 0; i < names.length; i += 2) {
            final int left = i, right = i + 1; ui.gap(content, 8);
            content.addView(ui.pair(ui.button(names[left], icons[left], false, () -> { sheet.dismiss(); actions[left].run(); }),
                ui.button(names[right], icons[right], false, () -> { sheet.dismiss(); actions[right].run(); })));
        }
        ui.gap(content, 14);
        MaterialButton stop = ui.button("停止 Harness", R.drawable.ic_stop, false, () -> {
            sheet.dismiss(); new MaterialAlertDialogBuilder(this).setTitle("停止运行？").setMessage("正在执行的任务和命令会中止，已保存的文件保留。")
                .setNegativeButton("取消", null).setPositiveButton("停止", (d, w) -> { engine.stop(); stopService(new Intent(this, EngineService.class)); showHome(); }).show();
        });
        stop.setTextColor(ui.color(R.color.danger)); stop.setIconTint(android.content.res.ColorStateList.valueOf(ui.color(R.color.danger)));
        stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ui.color(R.color.danger_surface))); content.addView(stop, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            Insets safe = safeInsets(insets);
            content.setPadding(dp(24) + safe.left, dp(24), dp(24) + safe.right, dp(24) + safe.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        sheet.setContentView(scroll);
        sheet.getBehavior().setMaxWidth(dp(560)); sheet.getBehavior().setMaxHeight((int)(getResources().getDisplayMetrics().heightPixels * .9f));
        sheet.getBehavior().setSkipCollapsed(true); sheet.setOnShowListener(dialog -> {
            sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            if (sheet.getWindow() != null) {
                sheet.getWindow().setNavigationBarContrastEnforced(false);
                WindowCompat.getInsetsController(sheet.getWindow(), scroll).setAppearanceLightNavigationBars(getResources().getBoolean(R.bool.light_system_bars));
            }
            ViewCompat.requestApplyInsets(scroll);
        }); sheet.show();
    }
    private void downloads() {
        DownloadSource[] sources = DownloadSource.values();
        String[] choices = Arrays.stream(sources).map(source -> source.title + "\n" + source.registry).toArray(String[]::new);
        final DownloadSource[] selected = {engine.downloadSource()};
        LinearLayout content = ui.column(24); content.setPadding(dp(24), dp(8), dp(24), dp(8));
        content.addView(ui.caption("选择基础环境、Ubuntu 依赖、Harness、pnpm 和 npm 插件的下载线路。")); ui.gap(content, 14);
        RadioGroup group = new RadioGroup(this);
        for (int i = 0; i < sources.length; i++) {
            MaterialRadioButton option = new MaterialRadioButton(this); option.setId(View.generateViewId()); option.setText(choices[i]); option.setTextSize(14);
            option.setPadding(dp(12), dp(14), dp(12), dp(14)); option.setUseMaterialThemeColors(true);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2); size.bottomMargin = dp(8);
            DownloadSource source = sources[i]; group.addView(option, size); option.setChecked(source == selected[0]);
            option.setBackground(ui.rounded(ui.color(source == selected[0] ? R.color.tonal : R.color.page), 16));
            option.setOnCheckedChangeListener((button, checked) -> {
                option.setBackground(ui.rounded(ui.color(checked ? R.color.tonal : R.color.page), 16));
                if (checked) selected[0] = source;
            });
        }
        content.addView(group);
        content.addView(ui.caption("Ubuntu 安装依赖使用 USTC 镜像，下载失败时回退官方源；保留签名校验。GitHub 地址不变，手动 apt 命令仍使用原有配置。第三方镜像可能延迟同步。"));
        TextView result = ui.caption("后续下载生效。已启动的 Harness 需重启以同步设置。"); content.addView(result); ui.gap(content, 12);
        Button check = button("保存并检测目标版本", () -> {}); content.addView(check, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("下载源").setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> {
            Runnable save = () -> {
                try { engine.downloadSource(selected[0]); toast("下载源已保存"); dialog.dismiss(); }
                catch (Exception error) { result.setText(error.getMessage()); }
            };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> save.run());
            check.setOnClickListener(view -> {
                try { engine.downloadSource(selected[0]); }
                catch (Exception error) { result.setText(error.getMessage()); return; }
                check.setEnabled(false); result.setText("正在检查连接和目标版本…");
                engine.checkDownloadSource(message -> { if (!isDestroyed()) { check.setEnabled(true); result.setText(message); } });
            });
        }); dialog.show();
    }
    private void logs() { logs(engine::logs); }
    void logs(Supplier<String> snapshot) {
        TextView text = label(snapshot.get(), 12, false); text.setTag("runtime-log-text"); text.setTextIsSelectable(true); text.setTypeface(Typeface.MONOSPACE); text.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout content = ui.column(14);
        content.addView(ui.caption("界面显示近期日志。导出包含最多 3 MiB 历史记录及重启前日志；常见凭据已脱敏，分享前仍请检查。"));
        content.addView(text);
        ScrollView scroll = new ScrollView(this); scroll.setTag("runtime-log-scroll"); scroll.addView(content);
        // Run after text wrapping/layout, once only: reading older lines must not snap back down.
        OneShotPreDrawListener.add(scroll, () -> scroll.scrollTo(0, content.getBottom()));
        new MaterialAlertDialogBuilder(this).setTitle("运行日志").setView(scroll)
            .setPositiveButton("关闭", null).setNegativeButton("刷新", (dialog, which) -> logs(snapshot))
            .setNeutralButton("导出日志", (dialog, which) -> logExporter.start()).show();
    }
    private void terminal() {
        EditText command = new TextInputEditText(this); command.setHint("命令，例如 node -v"); command.setMinLines(3); command.setTypeface(Typeface.MONOSPACE);
        new MaterialAlertDialogBuilder(this).setTitle("工作区终端").setMessage("从 /workspace 启动，可用 cd 切换目录。手机内部存储：" + engine.workspaces.sharedRoot.getPath() + "\n项目路径由 Harness 自己管理。")
            .setView(ui.input(command)).setNegativeButton("取消", null).setPositiveButton("执行", (dialog, which) -> engine.command(command.getText().toString(), result -> { toast(result); logs(); })).show();
    }
    private void plugins() {
        String[] actions = {"查看已安装插件", "安装或更新指定插件"};
        new MaterialAlertDialogBuilder(this).setTitle("插件管理").setItems(actions, (dialog, which) -> {
            if (which == 0) { engine.command("dsh plugin --profile web list", result -> { toast(result); logs(); }); return; }
            EditText spec = new TextInputEditText(this); spec.setHint("npm 包名或 github:作者/仓库");
            new MaterialAlertDialogBuilder(this).setTitle("安装 / 更新插件").setMessage("插件能执行代码，请仅安装可信来源。不会自动更新插件。")
                .setView(ui.input(spec)).setNegativeButton("取消", null).setPositiveButton("确认安装", (d, w) -> {
                    String value = spec.getText().toString().trim();
                    if (!value.matches("(?:@[A-Za-z0-9_.-]+/)?[A-Za-z0-9_.-]+(?:@[A-Za-z0-9_.^-]+)?|github:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:#[A-Za-z0-9_.-]+)?")) { toast("请输入有效的 npm 包名或 GitHub 插件地址"); return; }
                    engine.command("dsh plugin --profile web add " + RuntimePolicy.quote(value), result -> { toast(result + "；如未生效，请停止并重新启动 Harness"); logs(); });
                }).show();
        }).setNegativeButton("关闭", null).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (!logExporter.result(request, result, data)) projectBrowser.onActivityResult(request, result, data);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("ball-collapsed", ballCollapsed); projectBrowser.save(state); logExporter.save(state); workspaceDialog.save(state); super.onSaveInstanceState(state);
    }
    private ProjectBrowser createProjectBrowser(Bundle saved) {
        return new ProjectBrowser(this, projectFiles(), engine.profile, saved);
    }
    private ProjectFiles projectFiles() {
        return new ProjectFiles(engine.workspace, engine.rootfs, engine.workspaces.storageRoot,
            engine.workspaces.sharedRoot, engine.workspaces::hasAccess);
    }
    private void externalLink(Uri uri) {
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) { toast("不支持此链接类型"); return; }
        new MaterialAlertDialogBuilder(this).setTitle("在外部浏览器打开？").setMessage(uri.getHost()).setNegativeButton("取消", null)
            .setPositiveButton("打开", (dialog, which) -> { try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (ActivityNotFoundException missing) { toast("未找到浏览器"); } }).show();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override public void onBackPressed() { if (web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack(); else if (web.getVisibility() == View.VISIBLE) showHome(); else super.onBackPressed(); }
    @Override protected void onResume() { super.onResume(); if (updates != null) updates.automaticCheck(); if (workspaceDialog != null) { workspaceDialog.onResume(); refresh(); } }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused && updates != null) updateObserver.run(); }
    @Override protected void onDestroy() { updates.unlisten(updateObserver); updatesDialog.close(); ball.removeCallbacks(collapseBall); ball.animate().cancel(); engine.unlisten(observer); projectBrowser.close(); workspaceDialog.close(); logExporter.close(); permissionHelp.close(); webFiles.close(); systemFiles.close(); if (compatibilityScript != null) compatibilityScript.remove(); web.destroy(); super.onDestroy(); }
}
