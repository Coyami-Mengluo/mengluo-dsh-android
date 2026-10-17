package ai.mengluo.dsh.android;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;

/** Bounded, task-local display data. Never persisted or used to authorize an action. */
final class PhoneProgress {
    private final LinkedHashMap<String, String> messages = new LinkedHashMap<>();
    private final ArrayDeque<String> steps = new ArrayDeque<>();
    private String purpose = "", current = "等待下一步", latest = "";
    void begin(String value) { clear(); purpose = limit(value, 300); }
    void clear() { messages.clear(); steps.clear(); purpose = ""; current = "等待下一步"; latest = ""; }
    void step(String value) {
        current = limit(value, 160);
        if (!current.equals(steps.peekLast())) { steps.addLast(current); while (steps.size() > 20) steps.removeFirst(); }
    }
    void message(String id, String role, String text) {
        if (id == null || id.isBlank() || id.length() > 128 || !(role.equals("user") || role.equals("assistant"))) return;
        String value = limit(text, 800).strip();
        if (value.isEmpty()) return;
        messages.put(id, (role.equals("user") ? "你：" : "AI：") + value);
        while (messages.size() > 20) messages.remove(messages.keySet().iterator().next());
        if (role.equals("assistant")) latest = value;
    }
    String caption() { return current + "\n" + (latest.isEmpty() ? purpose : limit(latest, 120)); }
    String conversation() { return messages.isEmpty() ? "任务：" + purpose + "\n\n等待本次任务的对话…" : String.join("\n\n", messages.values()); }
    String steps() { return steps.isEmpty() ? current : String.join("\n", steps); }
    static String limit(String text, int max) {
        if (text == null) return "";
        String clean = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        if (clean.length() <= max) return clean;
        int end = max - 1;
        if (Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end) + "…";
    }
}
