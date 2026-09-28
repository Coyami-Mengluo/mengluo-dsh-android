package ai.mengluo.dsh.android;

import android.content.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/** Native update surface. Never exposed to plugin/web JavaScript. */
final class UpdatesDialog {
    private final AppCompatActivity activity;
    private final Ui ui;
    private final Engine engine;
    private final AndroidUpdates updates;
    private AlertDialog dialog;
    private TextView runtime, harnessState, clientState;
    private Button checkHarness, choose, previous, checkApk, download, install;
    private LinearProgressIndicator harnessProgress, apkProgress;
    private final Runnable observer = this::refresh;
    private final java.util.function.Consumer<Engine> engineObserver = value -> refresh();
    UpdatesDialog(AppCompatActivity activity) {
        this.activity = activity; ui = new Ui(activity); engine = Engine.get(activity); updates = AndroidUpdates.get(activity);
    }
    void show() {
        if (dialog != null) { dialog.show(); return; }
        LinearLayout content = ui.column(20); content.setTag("updates-content");
        LinearLayout harness = ui.card(); harness.addView(ui.text("Harness", 20, true));
        runtime = ui.caption(""); harness.addView(runtime); harnessState = ui.caption(""); harness.addView(harnessState);
        harnessProgress = new LinearProgressIndicator(activity); harnessProgress.setIndeterminate(true); harness.addView(harnessProgress);
        checkHarness = ui.button("检查官方版本", 0, false, () -> updates.checkHarness(true));
        choose = ui.button("安装 / 切换版本", 0, true, this::choose);
        harness.addView(checkHarness, new LinearLayout.LayoutParams(-1, -2)); harness.addView(choose, new LinearLayout.LayoutParams(-1, -2));
        previous = ui.button("回退上一版", 0, false, () -> confirm(null)); harness.addView(previous, new LinearLayout.LayoutParams(-1, -2));
        harness.addView(ui.caption("先下载、隔离测试，再原子切换。失败保留当前版本。回退仅切换运行程序，不撤销新版对项目、聊天或插件数据的修改。")); content.addView(harness);
        LinearLayout client = ui.card(); client.addView(ui.text("客户端", 20, true));
        client.addView(ui.caption("当前 " + BuildConfig.VERSION_NAME + " · " + BuildConfig.RUNTIME_ABI));
        clientState = ui.caption(""); client.addView(clientState);
        apkProgress = new LinearProgressIndicator(activity); apkProgress.setMax(100); client.addView(apkProgress);
        checkApk = ui.button("检查客户端更新", 0, false, () -> updates.checkApk(true)); client.addView(checkApk, new LinearLayout.LayoutParams(-1, -2));
        download = ui.button("下载更新", 0, true, () -> activity.startForegroundService(new Intent(activity, EngineService.class).setAction("apk-download")));
        client.addView(download, new LinearLayout.LayoutParams(-1, -2));
        install = ui.button("系统确认安装", 0, true, () -> {
            if (engine.busy) { error("请等待当前运行环境操作完成"); return; }
            new MaterialAlertDialogBuilder(activity).setTitle("安装客户端更新？").setMessage("请先结束正在运行的任务并保存文件。安卓系统确认安装后会结束当前 App；已保存的数据保留。")
                .setNegativeButton("取消", null).setPositiveButton("继续", (d, w) -> {
                    try { updates.install(activity); } catch (Exception e) { error(e.getMessage()); }
                }).show();
        }); client.addView(install, new LinearLayout.LayoutParams(-1, -2));
        client.addView(ui.caption("只从项目 GitHub Releases 下载，核验哈希、包名、版本与签名。由系统确认安装，不会静默覆盖。")); content.addView(client);
        MaterialSwitch automatic = new MaterialSwitch(activity); automatic.setText("每天自动检查更新"); automatic.setChecked(updates.automatic());
        automatic.setOnCheckedChangeListener((button, checked) -> updates.automatic(checked)); content.addView(automatic);
        content.addView(ui.caption("App 使用期间生效。只检查并提醒，不自动下载，不自动安装或升级插件。手动检查间隔 30 秒。"));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        dialog = new MaterialAlertDialogBuilder(activity).setTitle("更新管理").setView(scroll).setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(d -> { updates.unlisten(observer); engine.unlisten(engineObserver); dialog = null; });
        dialog.show(); updates.listen(observer); engine.listen(engineObserver);
    }
    private void refresh() {
        if (dialog == null || activity.isDestroyed()) return;
        runtime.setText(engine.installed() ? "当前 " + engine.currentVersion() : "尚未安装");
        harnessState.setText(engine.busy ? engine.status : updates.harnessStatus + "\n" + engine.status);
        harnessProgress.setVisibility(engine.busy || updates.checkingHarness ? View.VISIBLE : View.GONE);
        checkHarness.setEnabled(!updates.checkingHarness && !engine.busy);
        choose.setEnabled(!engine.busy && !engine.checkingSource);
        RuntimeStore.Slot prior = engine.versions.previous(); previous.setVisibility(prior == null ? View.GONE : View.VISIBLE);
        previous.setText(prior == null ? "回退上一版" : "回退到 " + prior.version); previous.setEnabled(!engine.busy);
        clientState.setText(updates.apkStatus + (updates.apk != null && updates.apk.code > BuildConfig.VERSION_CODE ? "\n\n" + updates.apk.notes : ""));
        checkApk.setEnabled(!updates.checkingApk && !updates.downloading);
        apkProgress.setVisibility(updates.downloading ? View.VISIBLE : View.GONE);
        apkProgress.setProgressCompat(updates.progress, true);
        boolean newer = updates.apk != null && updates.apk.code > BuildConfig.VERSION_CODE;
        download.setVisibility(newer && !updates.downloaded() ? View.VISIBLE : View.GONE); download.setEnabled(!updates.downloading);
        install.setVisibility(newer && updates.downloaded() ? View.VISIBLE : View.GONE); install.setEnabled(!engine.busy);
    }
    private void choose() {
        if (updates.releases.isEmpty()) { updates.checkHarness(true); error("正在获取版本目录，完成后再选择安装版本"); return; }
        String[] names = updates.releases.stream().map(r -> r.version + (r.version.equals(engine.currentVersion()) && engine.installed() ? " · 当前" : "")).toArray(String[]::new);
        new MaterialAlertDialogBuilder(activity).setTitle("选择 Harness 版本").setItems(names, (d, index) -> confirm(updates.releases.get(index).version)).setNegativeButton("取消", null).show();
    }
    private void confirm(String version) {
        String title = version == null ? "回退到上一版？" : "安装 Harness " + version + "？";
        new MaterialAlertDialogBuilder(activity).setTitle(title)
            .setMessage("下载源：" + engine.downloadSource().title + "\n\n请先结束正在运行的任务。确认后会停止 Harness，完成后可重新启动。保留项目和插件数据；跨版本兼容性取决于官方，新版本所需 Node 若超出当前支持范围会拒绝切换。")
            .setNegativeButton("取消", null).setPositiveButton("确认", (d, w) -> {
                if (engine.busy || engine.checkingSource) { error("请等待当前操作完成"); return; }
                engine.stop();
                activity.startForegroundService(new Intent(activity, EngineService.class).setAction(version == null ? "rollback" : "update-harness").putExtra("version", version));
            }).show();
    }
    private void error(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    void close() { if (dialog != null) dialog.dismiss(); }
}
