package ai.mengluo.dsh.android;

import java.util.regex.Pattern;

/** A conservative extra gate, not a claim that arbitrary app semantics can be inferred from labels. */
final class PhoneActionPolicy {
    private static final Pattern RISK = Pattern.compile(
        "发送|提交|发布|评论|回复|转发|分享|确认|确定|同意|允许|授权|登录|登陆|退出登录|安装|卸载|删除|清空|移除|清除|恢复|重置|格式化|支付|付款|转账|汇款|购买|下单|结算|订阅|开通|续费|充值|提现|绑定|解绑"
        + "|(?i)\\b(send|submit|post|publish|reply|forward|share|delete|remove|erase|trash|confirm|yes|ok|agree|accept|allow|authorize|grant|pay|payment|purchase|buy|checkout|order|subscribe|transfer|reset|install|uninstall|logout|login|log\\s+(in|out)|sign\\s+(in|out))\\b");
    private PhoneActionPolicy() { }
    /** Only the native settings page supplies skipActionConfirmation, never tool arguments. */
    static boolean needsActionConfirmation(boolean skipActionConfirmation, boolean requested, boolean risky) {
        return !skipActionConfirmation && (requested || risky);
    }
    static boolean needsConfirmation(String action, String label) {
        if (!action.equals("click")) return false;
        // Unlabelled controls are ambiguous; the model cannot opt out of this native check.
        return label == null || label.isBlank() || RISK.matcher(label).find();
    }
    static boolean needsTouchConfirmation(String startLabel, String endLabel, boolean routineScroll) {
        if (routineScroll) return RISK.matcher(startLabel == null ? "" : startLabel).find() || RISK.matcher(endLabel == null ? "" : endLabel).find();
        return needsConfirmation("click", startLabel) || needsConfirmation("click", endLabel);
    }
    /** Recheck the current target before dispatch; ordinary dynamic labels do not require approval.
     * The caller must compare the complete planned/current targets (including labels and geometry)
     * before setting sameConfirmedTargets. A prior approval is never transferred to a changed target.
     */
    static boolean needsConfirmationBeforeDispatch(String currentStartLabel, String currentEndLabel,
            boolean currentRoutineScroll, boolean confirmed, boolean sameConfirmedTargets) {
        return needsConfirmationBeforeDispatch(currentStartLabel, currentEndLabel, currentRoutineScroll,
            confirmed, sameConfirmedTargets, false);
    }
    static boolean needsConfirmationBeforeDispatch(String currentStartLabel, String currentEndLabel,
            boolean currentRoutineScroll, boolean confirmed, boolean sameConfirmedTargets, boolean skipActionConfirmation) {
        return !skipActionConfirmation && needsTouchConfirmation(currentStartLabel, currentEndLabel, currentRoutineScroll)
            && !(confirmed && sameConfirmedTargets);
    }
}
