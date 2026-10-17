package ai.mengluo.dsh.android;

import java.util.*;

/** Task-local question data and drafts. Plain text only; never persisted. */
final class PhoneQuestion {
    record Option(String label, String description) { }
    record Item(String id, String header, String question, List<Option> options, boolean multiple, String detail) {
        Item(String id, String header, String question, List<Option> options, boolean multiple) { this(id, header, question, options, multiple, ""); }
    }
    record Answer(List<String> selected, String text) { }
    final String id;
    final List<Item> items;
    final Map<String, Answer> drafts = new HashMap<>();
    PhoneQuestion(String id, List<Item> items) {
        if (id == null || id.isBlank() || id.length() > 256 || items == null || items.isEmpty() || items.size() > 4) throw new IllegalArgumentException("invalid_question");
        Set<String> ids = new HashSet<>(); List<Item> safe = new ArrayList<>();
        for (Item item : items) {
            if (item == null || item.id() == null || item.id().isBlank() || item.id().length() > 128 || !ids.add(item.id())
                || item.question() == null || item.question().isBlank() || item.question().length() > 1000
                || item.header() == null || item.header().length() > 80 || item.detail() == null || item.detail().length() > 8000
                || item.options() == null || item.options().size() > 8) throw new IllegalArgumentException("invalid_question");
            Set<String> labels = new HashSet<>();
            for (Option option : item.options()) if (option == null || option.label() == null || option.label().isBlank()
                || option.label().length() > 200 || !labels.add(option.label()) || option.description() == null || option.description().length() > 500) throw new IllegalArgumentException("invalid_question");
            safe.add(new Item(item.id(), item.header(), item.question(), List.copyOf(item.options()), item.multiple(), item.detail()));
        }
        this.id = id; this.items = List.copyOf(safe);
    }
    boolean valid(Map<String, Answer> answers) {
        if (answers == null || answers.size() != items.size()) return false;
        for (Item item : items) {
            Answer answer = answers.get(item.id());
            if (answer == null || answer.selected() == null || answer.text() == null || answer.text().length() > 2000
                || (answer.selected().isEmpty() && answer.text().isBlank()) || (!item.multiple() && answer.selected().size() > 1)
                || (!item.multiple() && !answer.selected().isEmpty() && !answer.text().isBlank())
                || new HashSet<>(answer.selected()).size() != answer.selected().size()) return false;
            for (String selected : answer.selected()) if (item.options().stream().noneMatch(option -> option.label().equals(selected))) return false;
        }
        return true;
    }
    Map<String, Answer> snapshot(Map<String, Answer> answers) {
        if (!valid(answers)) throw new IllegalArgumentException("invalid_answer");
        Map<String, Answer> safe = new LinkedHashMap<>();
        for (Item item : items) { Answer answer = answers.get(item.id()); safe.put(item.id(), new Answer(List.copyOf(answer.selected()), answer.text().strip())); }
        return Collections.unmodifiableMap(safe);
    }
}
