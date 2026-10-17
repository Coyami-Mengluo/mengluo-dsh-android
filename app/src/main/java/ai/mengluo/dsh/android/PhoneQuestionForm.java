package ai.mengluo.dsh.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.*;
import java.util.function.Consumer;

/** An explicit, task-local reply form. Its drafts survive ordinary overlay refreshes. */
@SuppressLint("ViewConstructor") // The task question and callback are supplied by PhoneOverlay, never XML.
final class PhoneQuestionForm extends LinearLayout {
    private final PhoneQuestion question;
    private final Ui ui;
    private final Map<String, EditText> inputs = new LinkedHashMap<>();
    private final Map<String, List<CompoundButton>> options = new LinkedHashMap<>();
    private final Button submit;
    private final TextView feedback;
    private boolean submitting, updatingDraft;
    private String error = "";

    PhoneQuestionForm(Context context, PhoneQuestion question, Consumer<Map<String, PhoneQuestion.Answer>> answer) {
        super(context); this.question = question; ui = new Ui(context);
        setOrientation(VERTICAL); setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(false);
        scroll.setClipToPadding(false); scroll.setPadding(0, 0, ui.dp(3), 0);
        LinearLayout questions = ui.column(0);
        questions.addView(ui.caption("手机操作已暂停。请选择选项，或直接输入回答；每道题都需要回复。"));
        for (int itemIndex = 0; itemIndex < question.items.size(); itemIndex++) {
            PhoneQuestion.Item item = question.items.get(itemIndex);
            PhoneQuestion.Answer draft = question.drafts.getOrDefault(item.id(), new PhoneQuestion.Answer(List.of(), ""));
            if (itemIndex > 0) ui.gap(questions, 16);
            if (!item.header().isBlank()) questions.addView(ui.caption(item.header()));
            questions.addView(ui.text(item.question(), 15, true));
            if (!item.detail().isBlank()) questions.addView(ui.text(item.detail(), 13, false));
            List<CompoundButton> choices = new ArrayList<>(); options.put(item.id(), choices);
            LinearLayout choiceContainer;
            if (item.multiple()) choiceContainer = ui.column(0);
            else {
                RadioGroup radios = new RadioGroup(context); radios.setOrientation(VERTICAL); choiceContainer = radios;
            }
            for (int index = 0; index < item.options().size(); index++) {
                PhoneQuestion.Option option = item.options().get(index);
                CompoundButton choice = item.multiple() ? new CheckBox(context) : new RadioButton(context);
                choice.setId(View.generateViewId()); choice.setTag("phone-question-option-" + item.id() + "-" + index);
                choice.setText(option.label() + (option.description().isBlank() ? "" : "\n" + option.description()));
                choice.setTextSize(14); choice.setTextColor(ui.color(R.color.ink));
                choice.setButtonTintList(ColorStateList.valueOf(ui.color(R.color.accent)));
                choice.setMinHeight(ui.dp(48)); choice.setPadding(ui.dp(3), ui.dp(7), ui.dp(3), ui.dp(7));
                choice.setFilterTouchesWhenObscured(true);
                choices.add(choice); choiceContainer.addView(choice, new LinearLayout.LayoutParams(-1, -2));
                choice.setChecked((item.multiple() || draft.text().isBlank()) && draft.selected().contains(option.label()));
            }
            if (!choices.isEmpty()) {
                questions.addView(ui.caption(item.multiple() ? "可多选，也可在下方补充" : "请选择一项，或直接输入其他回答"));
                questions.addView(choiceContainer);
            }
            TextInputLayout field = new TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle);
            field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            field.setBoxCornerRadii(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            field.setHint(choices.isEmpty() ? "请输入你的回答" : item.multiple() ? "补充或直接输入回答" : "直接输入其他回答");
            EditText input = new TextInputEditText(context);
            input.setTag("phone-question-input-" + item.id());
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
            input.setMinLines(2); input.setMaxLines(5); input.setGravity(Gravity.TOP | Gravity.START);
            input.setTextSize(14); input.setTextColor(ui.color(R.color.ink));
            input.setFilterTouchesWhenObscured(true); input.setText(draft.text());
            input.setOnEditorActionListener((text, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                hideKeyboard(); requestFocus(); return true;
            });
            field.addView(input, new LinearLayout.LayoutParams(-1, -2)); inputs.put(item.id(), input);
            LinearLayout.LayoutParams fieldLayout = new LinearLayout.LayoutParams(-1, -2); fieldLayout.topMargin = ui.dp(8);
            questions.addView(field, fieldLayout);
        }
        scroll.addView(questions, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        feedback = ui.caption(""); feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setTag("phone-question-feedback"); addView(feedback, new LinearLayout.LayoutParams(-1, -2));
        submit = ui.button("发送回答", 0, true, () -> {
            if (submitting || !question.valid(question.drafts)) return;
            hideKeyboard(); requestFocus(); answer.accept(question.snapshot(question.drafts));
        });
        submit.setTag("phone-question-submit"); submit.setFilterTouchesWhenObscured(true);
        addView(submit, new LinearLayout.LayoutParams(-1, -2));
        // Listeners are attached only after every input exists and initial radio selection settles.
        for (PhoneQuestion.Item item : question.items) {
            for (CompoundButton choice : options.get(item.id())) choice.setOnCheckedChangeListener((button, checked) -> {
                if (updatingDraft) return;
                updatingDraft = true;
                if (!item.multiple() && checked && inputs.get(item.id()).length() > 0) inputs.get(item.id()).setText("");
                updatingDraft = false; updateDraft(item);
            });
            inputs.get(item.id()).addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (updatingDraft) return;
                    updatingDraft = true;
                    if (!item.multiple() && !s.toString().isBlank() && !options.get(item.id()).isEmpty()) ((RadioGroup) options.get(item.id()).get(0).getParent()).clearCheck();
                    updatingDraft = false; updateDraft(item);
                }
                @Override public void afterTextChanged(Editable value) { }
            });
            updateDraft(item);
        }
        refresh(false, "");
    }

    private void updateDraft(PhoneQuestion.Item item) {
        List<String> selected = new ArrayList<>(); List<CompoundButton> choices = options.get(item.id());
        for (int index = 0; index < choices.size(); index++) if (choices.get(index).isChecked()) selected.add(item.options().get(index).label());
        question.drafts.put(item.id(), new PhoneQuestion.Answer(List.copyOf(selected), inputs.get(item.id()).getText().toString()));
        updateState();
    }

    void refresh(boolean submitting, String error) {
        this.submitting = submitting; this.error = error == null ? "" : error;
        for (EditText input : inputs.values()) input.setEnabled(!submitting);
        for (List<CompoundButton> choices : options.values()) for (CompoundButton choice : choices) choice.setEnabled(!submitting);
        updateState();
    }

    private void updateState() {
        boolean valid = question.valid(question.drafts); submit.setEnabled(!submitting && valid);
        submit.setText(submitting ? "正在发送…" : error.isEmpty() ? "发送回答" : "重新发送回答");
        String message = submitting ? "正在发送你的回答，请稍候。" : !error.isEmpty() ? error : valid ? "回答准备好了，点击发送后 AI 才会继续。" : "请为每道题选择选项或输入回答。";
        if (!feedback.getText().toString().equals(message)) feedback.setText(message);
        feedback.setTextColor(ui.color(error.isEmpty() ? R.color.muted : R.color.danger));
    }

    void hideKeyboard() {
        InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
        if (ime != null && getWindowToken() != null) ime.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    void revealFocusedInput() {
        View focused = findFocus();
        if (focused instanceof EditText) {
            android.graphics.Rect cursor = new android.graphics.Rect(); focused.getFocusedRect(cursor);
            focused.requestRectangleOnScreen(cursor, true);
        }
    }
}
