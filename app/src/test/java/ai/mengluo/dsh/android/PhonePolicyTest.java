package ai.mengluo.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhonePolicyTest {
    @Test public void onlyNativeOptInSkipsRequestedAndRiskBasedActionConfirmation() {
        for (boolean requested : new boolean[]{false, true}) for (boolean risky : new boolean[]{false, true}) {
            assertEquals(requested || risky, PhoneActionPolicy.needsActionConfirmation(false, requested, risky));
            assertFalse(PhoneActionPolicy.needsActionConfirmation(true, requested, risky));
        }
    }
    @Test public void optInAlsoSkipsTheDispatchRecheckWithoutInventingTargetApproval() {
        for (String label : new String[]{null, "", "Search", "Send", "Delete", "确认支付"}) {
            for (boolean confirmed : new boolean[]{false, true}) for (boolean same : new boolean[]{false, true}) {
                assertFalse(PhoneActionPolicy.needsConfirmationBeforeDispatch(label, label, false, confirmed, same, true));
                assertEquals(PhoneActionPolicy.needsConfirmationBeforeDispatch(label, label, false, confirmed, same),
                    PhoneActionPolicy.needsConfirmationBeforeDispatch(label, label, false, confirmed, same, false));
            }
        }
    }
    @Test public void normalNavigationAndInputNeedNoStepPrompt() {
        for (String label : new String[]{"Search", "搜索", "返回", "相册", "Increment test counter"}) assertFalse(label, PhoneActionPolicy.needsConfirmation("click", label));
        for (String action : new String[]{"launch", "input", "scroll_forward", "scroll_backward", "back"}) assertFalse(PhoneActionPolicy.needsConfirmation(action, ""));
    }
    @Test public void consequentialAndAmbiguousControlsStillRequireTheNativePrompt() {
        for (String label : new String[]{"发送", "删除项目", "确认支付", "授权", "发布", "Send message", "BUY NOW", "Confirm", "OK", "  "}) assertTrue(label, PhoneActionPolicy.needsConfirmation("click", label));
    }
    @Test public void onlyOwnOverlayIsMaskedAndFullDisplayMasksAreRejected() {
        assertEquals(new PhoneScreenPolicy.Box(600, 0, 800, 280), PhoneScreenPolicy.ownMask(new PhoneScreenPolicy.Box(600, -8, 810, 280), 800, 1000));
        assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.ownMask(new PhoneScreenPolicy.Box(0, 0, 800, 1000), 800, 1000));
        assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.ownMask(null, 800, 1000));
    }
    @Test public void imagePixelsMapBackToTheActualDisplayWithoutCropOffsets() {
        assertEquals(new PhoneScreenPolicy.Pixel(300, 750), PhoneScreenPolicy.toDisplay(200, 500, 720, 1280, 1080, 1920));
        assertEquals(new PhoneScreenPolicy.Pixel(1079, 1919), PhoneScreenPolicy.toDisplay(719, 1279, 720, 1280, 1080, 1920));
        assertEquals(new PhoneScreenPolicy.Pixel(0, 0), PhoneScreenPolicy.toDisplay(0, 0, 1080, 1920, 1080, 1920));
        assertEquals(new PhoneScreenPolicy.Pixel(1919, 1079), PhoneScreenPolicy.toDisplay(1279, 719, 1280, 720, 1920, 1080));
        for (int[] point : new int[][]{{-1, 0}, {0, -1}, {720, 0}, {0, 1280}, {Integer.MAX_VALUE, 0}})
            assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.toDisplay(point[0], point[1], 720, 1280, 1080, 1920));
    }
    @Test public void gesturesCannotCrossTheOverlayEvenWithEndpointsOutsideIt() {
        PhoneScreenPolicy.Box mask = new PhoneScreenPolicy.Box(40, 40, 80, 80);
        assertTrue(PhoneScreenPolicy.crosses(mask, new PhoneScreenPolicy.Pixel(0, 60), new PhoneScreenPolicy.Pixel(100, 60)));
        assertTrue(PhoneScreenPolicy.crosses(mask, new PhoneScreenPolicy.Pixel(60, 100), new PhoneScreenPolicy.Pixel(60, 0)));
        assertTrue(PhoneScreenPolicy.crosses(mask, new PhoneScreenPolicy.Pixel(60, 60), new PhoneScreenPolicy.Pixel(60, 60)));
        assertFalse(PhoneScreenPolicy.crosses(mask, new PhoneScreenPolicy.Pixel(0, 20), new PhoneScreenPolicy.Pixel(100, 20)));
        assertFalse(PhoneScreenPolicy.crosses(mask, new PhoneScreenPolicy.Pixel(0, 0), new PhoneScreenPolicy.Pixel(0, 0)));
    }
    @Test public void gesturesAreBoundedAndOldScreenshotsExpire() {
        PhoneScreenPolicy.requireFresh(1000, 61000);
        assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.requireFresh(1000, 61001));
        assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.requireFresh(1000, 999));
        assertEquals(800, PhoneScreenPolicy.duration("long_press", 800));
        assertEquals(400, PhoneScreenPolicy.duration("swipe", 400));
        assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.duration("long_press", 100));
        assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.duration("swipe", 2001));
        assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.duration("arbitrary", 500));
    }
    @Test public void ordinaryScrollingIsAutomaticButRiskyOrUnclearGesturesNeedConfirmation() {
        assertFalse(PhoneActionPolicy.needsTouchConfirmation("", "", true));
        assertFalse(PhoneActionPolicy.needsTouchConfirmation("Gesture pad", "Gesture pad", false));
        assertTrue(PhoneActionPolicy.needsTouchConfirmation("", "", false));
        assertTrue(PhoneActionPolicy.needsTouchConfirmation("Delete", "", true));
        assertTrue(PhoneActionPolicy.needsTouchConfirmation("Title", "Send", false));
    }
    @Test public void ordinaryCurrentTargetsDoNotInheritADynamicLabelRestriction() {
        for (String label : new String[]{"Search", "搜索推荐 B", "输入框", "相册", "Gesture pad"}) {
            for (boolean confirmed : new boolean[]{false, true}) {
                for (boolean sameTargets : new boolean[]{false, true}) {
                    assertFalse(label, PhoneActionPolicy.needsConfirmationBeforeDispatch(
                        label, "普通选项", false, confirmed, sameTargets));
                }
            }
        }
    }
    @Test public void aNewlyRiskyCurrentTargetRequiresConfirmation() {
        for (String risk : new String[]{"发送", "删除项目", "确认支付", "授权", "发布", "Send message", "BUY NOW"}) {
            assertTrue(risk, PhoneActionPolicy.needsConfirmationBeforeDispatch(risk, "Title", false, false, false));
            assertTrue(risk, PhoneActionPolicy.needsConfirmationBeforeDispatch("Title", risk, false, false, false));
        }
    }
    @Test public void onlyApprovalOfTheSameCurrentRiskyTargetsPermitsDispatch() {
        for (boolean confirmed : new boolean[]{false, true}) {
            for (boolean sameTargets : new boolean[]{false, true}) {
                assertEquals(!(confirmed && sameTargets), PhoneActionPolicy.needsConfirmationBeforeDispatch(
                    "Send", "Send", false, confirmed, sameTargets));
            }
        }
    }
    @Test public void changedRiskyOrUnknownTargetsCannotReuseAnEarlierApproval() {
        for (String label : new String[]{null, "", "  ", "Delete", "Send"}) {
            assertTrue(PhoneActionPolicy.needsConfirmationBeforeDispatch(label, label, false, true, false));
            assertTrue(PhoneActionPolicy.needsConfirmationBeforeDispatch(label, label, false, false, true));
            assertFalse(PhoneActionPolicy.needsConfirmationBeforeDispatch(label, label, false, true, true));
        }
    }
    @Test public void currentRoutineScrollKeepsExistingRiskRules() {
        assertFalse(PhoneActionPolicy.needsConfirmationBeforeDispatch(null, "", true, false, false));
        assertFalse(PhoneActionPolicy.needsConfirmationBeforeDispatch("Gallery", "New caption", true, false, false));
        assertTrue(PhoneActionPolicy.needsConfirmationBeforeDispatch("Delete", "", true, false, false));
        assertTrue(PhoneActionPolicy.needsConfirmationBeforeDispatch("", "Send", true, true, false));
        assertFalse(PhoneActionPolicy.needsConfirmationBeforeDispatch("", "Send", true, true, true));
        assertTrue("An unclear gesture that is no longer a routine scroll must be confirmed",
            PhoneActionPolicy.needsConfirmationBeforeDispatch("", "", false, false, false));
    }
    @Test public void preDispatchPolicyExactlyReusesCurrentRiskClassification() {
        String[] labels = {null, "", "Search", "New recommendation", "输入框", "Send", "删除"};
        for (String start : labels) for (String end : labels) for (boolean routine : new boolean[]{false, true}) {
            boolean needs = PhoneActionPolicy.needsTouchConfirmation(start, end, routine);
            assertEquals(needs, PhoneActionPolicy.needsConfirmationBeforeDispatch(start, end, routine, false, false));
            assertEquals(needs, PhoneActionPolicy.needsConfirmationBeforeDispatch(start, end, routine, false, true));
            assertEquals(needs, PhoneActionPolicy.needsConfirmationBeforeDispatch(start, end, routine, true, false));
            assertFalse(PhoneActionPolicy.needsConfirmationBeforeDispatch(start, end, routine, true, true));
        }
    }
    @Test public void emptyOffscreenAndUnboundedScreensAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.clip(new PhoneScreenPolicy.Box(900, 0, 1000, 100), 800, 1000));
        assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.clip(new PhoneScreenPolicy.Box(0, 0, 0, 10), 800, 1000));
        assertThrows(IllegalArgumentException.class, () -> PhoneScreenPolicy.clip(new PhoneScreenPolicy.Box(0, 0, 10, 10), Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
