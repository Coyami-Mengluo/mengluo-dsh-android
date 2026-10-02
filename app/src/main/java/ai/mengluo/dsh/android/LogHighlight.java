package ai.mengluo.dsh.android;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

final class LogHighlight {
    static CharSequence render(Context context, String source) {
        SpannableString result = new SpannableString(source);
        for (int start = 0; start < source.length();) {
            int newline = source.indexOf('\n', start), end = newline < 0 ? source.length() : newline;
            String line = source.substring(start, end);
            int prefix = LogLevel.timestampEnd(line);
            if (prefix > 0) color(result, start, start + prefix, context.getColor(R.color.muted));
            int color = switch (LogLevel.of(line)) {
                case INFO -> R.color.accent;
                case SUCCESS -> R.color.success;
                case WARNING -> R.color.warning;
                case ERROR -> R.color.danger;
                default -> R.color.ink;
            };
            color(result, start + prefix, end, context.getColor(color));
            start = end + 1;
        }
        return result;
    }
    private static void color(SpannableString text, int start, int end, int color) {
        if (end > start) text.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    private LogHighlight() {}
}
