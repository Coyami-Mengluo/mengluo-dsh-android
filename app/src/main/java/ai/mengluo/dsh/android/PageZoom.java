package ai.mengluo.dsh.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebView;
import android.widget.*;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Whole-page viewport scaling; never changes Harness files or the native menu size. */
final class PageZoom {
    static final int MIN = 50, MAX = 200, STEP = 10;
    private final SharedPreferences preferences;
    private final WebView web;
    private final Supplier<String> ready;
    private int percent;

    PageZoom(Context context, WebView web, Supplier<String> ready) {
        this(web, ready, context.getSharedPreferences("page-display", Context.MODE_PRIVATE));
    }
    PageZoom(WebView web, Supplier<String> ready, SharedPreferences preferences) {
        this.preferences = preferences;
        this.web = web; this.ready = ready;
        percent = clamp(preferences.getInt("zoom", 100));
        web.getSettings().setUseWideViewPort(true);
        web.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) apply();
        });
    }
    static int clamp(int value) { return Math.max(MIN, Math.min(MAX, value)); }
    int percent() { return percent; }
    void change(int value) {
        percent = clamp(value);
        preferences.edit().putInt("zoom", percent).apply();
        apply();
    }
    void apply() {
        String origin = ready.get();
        if (origin == null || !RuntimePolicy.trustedPage(web.getUrl(), origin) || web.getWidth() == 0) return;
        float density = web.getResources().getDisplayMetrics().density;
        int width = Math.max(1, Math.round(web.getWidth() / density * 100f / percent));
        String content = "width=" + width + ", initial-scale=" + percent / 100f
            + ", minimum-scale=" + percent / 100f + ", maximum-scale=" + percent / 100f + ", user-scalable=no";
        web.evaluateJavascript("(()=>{let m=document.querySelector('meta[name=viewport]');"
            + "if(!m){m=document.createElement('meta');m.name='viewport';document.head.append(m)}"
            + "m.content=" + JSONObject.quote(content) + ";})()", null);
    }
    LinearLayout controls(Ui ui) {
        LinearLayout box = ui.card(); box.setTag("page-zoom");
        TextView title = ui.text("页面缩放 · " + percent + "%", 15, true); title.setTag("page-zoom-value");
        box.addView(title);
        LinearLayout row = new LinearLayout(web.getContext());
        Button minus = ui.button("−", 0, false, () -> {}), reset = ui.button("重置", 0, false, () -> {}), plus = ui.button("+", 0, false, () -> {});
        minus.setContentDescription("缩小页面"); plus.setContentDescription("放大页面"); reset.setContentDescription("重置页面缩放");
        minus.setTag("page-zoom-minus"); plus.setTag("page-zoom-plus"); reset.setTag("page-zoom-reset");
        Runnable refresh = () -> { title.setText("页面缩放 · " + percent + "%"); minus.setEnabled(percent > MIN); plus.setEnabled(percent < MAX); };
        minus.setOnClickListener(v -> { change(percent - STEP); refresh.run(); });
        plus.setOnClickListener(v -> { change(percent + STEP); refresh.run(); });
        reset.setOnClickListener(v -> { change(100); refresh.run(); });
        for (Button button : new Button[]{minus, reset, plus}) row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row); refresh.run(); return box;
    }
}
