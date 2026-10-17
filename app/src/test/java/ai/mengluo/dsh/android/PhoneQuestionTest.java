package ai.mengluo.dsh.android;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PhoneQuestionTest {
    private static final List<PhoneQuestion.Option> OPTIONS = List.of(
        new PhoneQuestion.Option("A", "First choice"), new PhoneQuestion.Option("B", "Second choice"));

    private static PhoneQuestion form(boolean multiple) {
        return new PhoneQuestion("request", List.of(new PhoneQuestion.Item("answer", "AI 提问", "Choose a route", OPTIONS, multiple)));
    }
    private static Map<String, PhoneQuestion.Answer> answer(List<String> selected, String text) {
        return Map.of("answer", new PhoneQuestion.Answer(selected, text));
    }
    private static void invalid(Runnable create) {
        assertThrows(IllegalArgumentException.class, create::run);
    }

    @Test public void singleChoiceAcceptsOneOptionOrCustomText() {
        PhoneQuestion question = form(false);
        assertTrue(question.valid(answer(List.of("A"), "")));
        assertTrue(question.valid(answer(List.of(), "Use the scenic route")));
        assertFalse("A single-choice custom answer replaces its selected option", question.valid(answer(List.of("A"), "Another route")));
        assertFalse(question.valid(answer(List.of("A", "B"), "")));
    }
    @Test public void multipleChoiceAcceptsSelectedOptionsAndAdditionalText() {
        PhoneQuestion question = form(true);
        Map<String, PhoneQuestion.Answer> result = question.snapshot(answer(List.of("B", "A"), "  Additional detail\nsecond line  "));
        assertEquals(List.of("B", "A"), result.get("answer").selected());
        assertEquals("Additional detail\nsecond line", result.get("answer").text());
        assertTrue(question.valid(answer(List.of(), "Only a custom answer")));
    }
    @Test public void textOnlyQuestionRequiresNonblankCustomText() {
        PhoneQuestion question = new PhoneQuestion("request", List.of(new PhoneQuestion.Item("answer", "", "What should happen?", List.of(), false)));
        assertTrue(question.valid(answer(List.of(), "Continue with the fixture")));
        assertFalse(question.valid(answer(List.of(), " \n\t ")));
        assertFalse(question.valid(answer(List.of("A"), "")));
    }
    @Test public void invalidEmptyAndUnknownAnswersNeverBecomeSnapshots() {
        PhoneQuestion question = form(true);
        List<Map<String, PhoneQuestion.Answer>> invalidAnswers = new ArrayList<>();
        invalidAnswers.add(null); invalidAnswers.add(Map.of());
        invalidAnswers.add(answer(List.of(), "")); invalidAnswers.add(answer(List.of(), " \t\n"));
        invalidAnswers.add(answer(List.of("unknown"), ""));
        invalidAnswers.add(answer(List.of("A", "A"), ""));
        invalidAnswers.add(answer(Arrays.asList((String)null), ""));
        invalidAnswers.add(answer(null, "text")); invalidAnswers.add(answer(List.of(), null));
        invalidAnswers.add(answer(List.of(), "x".repeat(2001)));
        invalidAnswers.add(Map.of("other", new PhoneQuestion.Answer(List.of("A"), "")));
        for (Map<String, PhoneQuestion.Answer> value : invalidAnswers) {
            assertFalse(question.valid(value)); invalid(() -> question.snapshot(value));
        }
    }
    @Test public void everyItemNeedsItsOwnAnswerAndExtraIdsAreRefused() {
        PhoneQuestion question = new PhoneQuestion("request", List.of(
            new PhoneQuestion.Item("first", "First", "Choose", OPTIONS, false),
            new PhoneQuestion.Item("second", "Second", "Describe", List.of(), false)));
        Map<String, PhoneQuestion.Answer> complete = new LinkedHashMap<>();
        complete.put("first", new PhoneQuestion.Answer(List.of("A"), ""));
        assertFalse(question.valid(complete));
        complete.put("second", new PhoneQuestion.Answer(List.of(), "Details"));
        assertTrue(question.valid(complete));
        assertEquals(List.of("first", "second"), new ArrayList<>(question.snapshot(complete).keySet()));
        complete.put("extra", new PhoneQuestion.Answer(List.of(), "Extra"));
        assertFalse(question.valid(complete));
    }
    @Test public void constructorCopiesCollectionsAndSnapshotCannotBeChangedByDraftEdits() {
        List<PhoneQuestion.Option> options = new ArrayList<>(OPTIONS);
        List<PhoneQuestion.Item> items = new ArrayList<>(List.of(new PhoneQuestion.Item("answer", "Title", "Question", options, true)));
        PhoneQuestion question = new PhoneQuestion("request", items);
        options.clear(); items.clear();
        assertEquals(1, question.items.size()); assertEquals(2, question.items.get(0).options().size());
        assertThrows(UnsupportedOperationException.class, () -> question.items.clear());
        assertThrows(UnsupportedOperationException.class, () -> question.items.get(0).options().clear());
        List<String> selected = new ArrayList<>(List.of("B"));
        Map<String, PhoneQuestion.Answer> source = new HashMap<>(answer(selected, "  Original  "));
        Map<String, PhoneQuestion.Answer> snapshot = question.snapshot(source);
        selected.add("A"); source.clear();
        assertEquals(List.of("B"), snapshot.get("answer").selected());
        assertEquals("Original", snapshot.get("answer").text());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get("answer").selected().add("A"));
    }
    @Test public void exactSizeLimitsAreAccepted() {
        List<PhoneQuestion.Option> options = new ArrayList<>();
        for (int i = 0; i < 8; i++) options.add(new PhoneQuestion.Option(i + "x".repeat(199), "d".repeat(500)));
        List<PhoneQuestion.Item> items = new ArrayList<>();
        Map<String, PhoneQuestion.Answer> answers = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            String id = i + "i".repeat(127);
            items.add(new PhoneQuestion.Item(id, "h".repeat(80), "q".repeat(1000), options, true, "d".repeat(8000)));
            answers.put(id, new PhoneQuestion.Answer(options.stream().map(PhoneQuestion.Option::label).toList(), "t".repeat(2000)));
        }
        PhoneQuestion question = new PhoneQuestion("r".repeat(256), items);
        assertTrue(question.valid(answers)); assertEquals(4, question.snapshot(answers).size());
    }
    @Test public void constructorRejectsInvalidQuestionAndItemBounds() {
        PhoneQuestion.Item item = new PhoneQuestion.Item("answer", "", "Question", OPTIONS, false);
        for (String id : Arrays.asList(null, "", " ", "r".repeat(257))) invalid(() -> new PhoneQuestion(id, List.of(item)));
        invalid(() -> new PhoneQuestion("request", null));
        invalid(() -> new PhoneQuestion("request", List.of()));
        invalid(() -> new PhoneQuestion("request", Arrays.asList((PhoneQuestion.Item)null)));
        invalid(() -> new PhoneQuestion("request", List.of(item, item)));
        invalid(() -> new PhoneQuestion("request", List.of(item, item, item, item, item)));
        for (String id : Arrays.asList(null, "", " ", "i".repeat(129))) invalid(() -> new PhoneQuestion("request", List.of(new PhoneQuestion.Item(id, "", "Question", OPTIONS, false))));
        for (String prompt : Arrays.asList(null, "", " \n", "q".repeat(1001))) invalid(() -> new PhoneQuestion("request", List.of(new PhoneQuestion.Item("answer", "", prompt, OPTIONS, false))));
        for (String header : Arrays.asList(null, "h".repeat(81))) invalid(() -> new PhoneQuestion("request", List.of(new PhoneQuestion.Item("answer", header, "Question", OPTIONS, false))));
        for (String detail : Arrays.asList(null, "d".repeat(8001))) invalid(() -> new PhoneQuestion("request", List.of(new PhoneQuestion.Item("answer", "", "Question", OPTIONS, false, detail))));
        invalid(() -> new PhoneQuestion("request", List.of(new PhoneQuestion.Item("answer", "", "Question", null, false))));
    }
    @Test public void constructorRejectsInvalidOptionsAndDuplicateLabels() {
        List<List<PhoneQuestion.Option>> choices = new ArrayList<>();
        choices.add(Arrays.asList((PhoneQuestion.Option)null));
        for (String label : Arrays.asList(null, "", " ", "x".repeat(201))) choices.add(List.of(new PhoneQuestion.Option(label, "")));
        choices.add(List.of(new PhoneQuestion.Option("A", null)));
        choices.add(List.of(new PhoneQuestion.Option("A", "d".repeat(501))));
        choices.add(List.of(new PhoneQuestion.Option("A", "first"), new PhoneQuestion.Option("A", "second")));
        List<PhoneQuestion.Option> tooMany = new ArrayList<>();
        for (int i = 0; i < 9; i++) tooMany.add(new PhoneQuestion.Option("Option " + i, ""));
        choices.add(tooMany);
        for (List<PhoneQuestion.Option> options : choices) invalid(() -> new PhoneQuestion("request", List.of(new PhoneQuestion.Item("answer", "", "Question", options, false))));
    }
}
