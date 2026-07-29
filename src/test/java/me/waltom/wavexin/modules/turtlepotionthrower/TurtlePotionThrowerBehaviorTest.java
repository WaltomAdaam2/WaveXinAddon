package me.waltom.wavexin.modules.turtlepotionthrower;

public final class TurtlePotionThrowerBehaviorTest {
    private TurtlePotionThrowerBehaviorTest() {
    }

    public static void main(String[] args) {
        assertPlan(TurtlePotionThrowerPlan.Action.WARNING, TurtlePotionThrowerPlan.unavailable(), "unavailable state warns");
        assertWarningKey("warning.wavexin.turtle_potion_thrower.unavailable", TurtlePotionThrowerPlan.unavailable(), "unavailable warning key");

        assertPlan(TurtlePotionThrowerPlan.Action.WARNING, TurtlePotionThrowerPlan.choose(false, false, false, false, 0, -1, true), "missing potion warns");
        assertWarningKey("warning.wavexin.turtle_potion_thrower.no_potion", TurtlePotionThrowerPlan.choose(false, false, false, false, 0, -1, true), "missing potion key");

        assertEquals(TurtlePotionThrowerPlan.Action.USE_MAIN_HAND, TurtlePotionThrowerPlan.preferredHandAction(true, false), "main hand is preferred directly");
        assertEquals(TurtlePotionThrowerPlan.Action.USE_MAIN_HAND, TurtlePotionThrowerPlan.preferredHandAction(true, true), "main hand wins when both hands contain potions");
        assertEquals(TurtlePotionThrowerPlan.Action.USE_OFFHAND, TurtlePotionThrowerPlan.preferredHandAction(false, true), "offhand is used when main hand has no potion");
        assertNull(TurtlePotionThrowerPlan.preferredHandAction(false, false), "no hand potion falls through to inventory search");

        assertPlan(TurtlePotionThrowerPlan.Action.USE_MAIN_HAND, TurtlePotionThrowerPlan.choose(true, true, false, true, -1, -1, false), "main hand uses directly");
        assertPlan(TurtlePotionThrowerPlan.Action.USE_OFFHAND, TurtlePotionThrowerPlan.choose(true, false, true, false, -1, 45, false), "offhand uses directly");

        TurtlePotionThrowerPlan.Plan hotbar = TurtlePotionThrowerPlan.choose(true, false, false, true, 2, 5, false);
        assertPlan(TurtlePotionThrowerPlan.Action.HOTBAR_SWAP, hotbar, "hotbar potion uses local swap");
        assertEquals(2, hotbar.selectedSlot(), "hotbar selected slot captured locally");
        assertEquals(5, hotbar.itemSlot(), "hotbar item slot captured locally");

        TurtlePotionThrowerPlan.Plan quick = TurtlePotionThrowerPlan.choose(true, false, false, false, 2, 20, true);
        assertPlan(TurtlePotionThrowerPlan.Action.QUICK_SWAP, quick, "inventory potion quick swaps");
        assertEquals(2, quick.selectedSlot(), "quick swap selected slot captured locally");
        assertEquals(20, quick.itemSlot(), "quick swap item slot captured locally");

        TurtlePotionThrowerPlan.Plan hotbarRequired = TurtlePotionThrowerPlan.choose(true, false, false, false, 2, 20, false);
        assertPlan(TurtlePotionThrowerPlan.Action.WARNING, hotbarRequired, "inventory potion without quick swap warns");
        assertWarningKey("warning.wavexin.turtle_potion_thrower.hotbar_required", hotbarRequired, "hotbar required key");

        assertWarningKey("warning.wavexin.turtle_potion_thrower.swap_failed", TurtlePotionThrowerPlan.choose(true, false, false, true, -1, 5, false), "invalid selected slot key");
        assertWarningKey("warning.wavexin.turtle_potion_thrower.swap_failed", TurtlePotionThrowerPlan.choose(true, false, false, true, 2, 9, false), "invalid hotbar item slot key");
        assertWarningKey("warning.wavexin.turtle_potion_thrower.swap_failed", TurtlePotionThrowerPlan.choose(true, false, false, false, 9, 20, true), "invalid quick selected slot key");

        assertTrue(TurtlePotionThrowerPlan.isValidHotbarSlot(0), "slot 0 valid");
        assertTrue(TurtlePotionThrowerPlan.isValidHotbarSlot(8), "slot 8 valid");
        assertFalse(TurtlePotionThrowerPlan.isValidHotbarSlot(-1), "slot -1 invalid");
        assertFalse(TurtlePotionThrowerPlan.isValidHotbarSlot(9), "slot 9 invalid");
        assertTrue(TurtlePotionThrowerPlan.shouldAttemptQuickSwapRestore(true, false), "completed quick swap must restore");
        assertTrue(TurtlePotionThrowerPlan.shouldAttemptQuickSwapRestore(false, true), "partially completed quick swap must restore");
        assertFalse(TurtlePotionThrowerPlan.shouldAttemptQuickSwapRestore(false, false), "failed quick swap without inventory change must not invert slots");
        assertTrue(TurtlePotionThrowerPlan.shouldAttemptHotbarRestore(true, false), "completed hotbar swap must restore");
        assertTrue(TurtlePotionThrowerPlan.shouldAttemptHotbarRestore(false, true), "partially completed hotbar selection must restore");
        assertFalse(TurtlePotionThrowerPlan.shouldAttemptHotbarRestore(false, false), "failed hotbar swap without selection change needs no restore");
        assertTrue(TurtlePotionThrowerPlan.shouldChatNotify(true), "notify true shows chat");
        assertFalse(TurtlePotionThrowerPlan.shouldChatNotify(false), "notify false suppresses chat only");
    }

    private static void assertPlan(TurtlePotionThrowerPlan.Action expected, TurtlePotionThrowerPlan.Plan plan, String label) {
        assertEquals(expected, plan.action(), label);
    }

    private static void assertWarningKey(String expected, TurtlePotionThrowerPlan.Plan plan, String label) {
        if (!plan.warns()) throw new AssertionError(label + " should warn");
        assertEquals(expected, plan.warningKey(), label);
    }

    private static void assertTrue(boolean actual, String label) {
        if (!actual) throw new AssertionError(label + " should be true");
    }

    private static void assertFalse(boolean actual, String label) {
        if (actual) throw new AssertionError(label + " should be false");
    }

    private static void assertNull(Object actual, String label) {
        if (actual != null) throw new AssertionError(label + " should be null but got [" + actual + "]");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected [" + expected + "] but got [" + actual + "]");
    }
}
