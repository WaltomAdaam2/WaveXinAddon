package me.waltom.wavexin.modules.turtlepotionthrower;

final class TurtlePotionThrowerPlan {
    private TurtlePotionThrowerPlan() {
    }

    enum Action {
        USE_MAIN_HAND,
        USE_OFFHAND,
        HOTBAR_SWAP,
        QUICK_SWAP,
        WARNING
    }

    record Plan(Action action, int selectedSlot, int itemSlot, String warningKey, String fallback) {
        boolean warns() {
            return action == Action.WARNING;
        }
    }

    static Plan unavailable() {
        return warning("warning.wavexin.turtle_potion_thrower.unavailable", "Cannot throw turtle potion right now.");
    }

    static Plan choose(boolean found, boolean mainHand, boolean offhand, boolean hotbar, int selectedSlot, int itemSlot, boolean quickSwap) {
        if (!found) return warning("warning.wavexin.turtle_potion_thrower.no_potion", "No throwable turtle potion was found.");
        if (mainHand) return new Plan(Action.USE_MAIN_HAND, selectedSlot, itemSlot, null, null);
        if (offhand) return new Plan(Action.USE_OFFHAND, selectedSlot, itemSlot, null, null);

        if (quickSwap) {
            if (!isValidHotbarSlot(selectedSlot) || itemSlot < 0) return swapFailed();
            return new Plan(Action.QUICK_SWAP, selectedSlot, itemSlot, null, null);
        }

        if (!hotbar) {
            return warning("warning.wavexin.turtle_potion_thrower.hotbar_required", "No turtle potion was found in your hotbar. Enable Quick Swap to use inventory potions.");
        }
        if (!isValidHotbarSlot(selectedSlot) || !isValidHotbarSlot(itemSlot)) return swapFailed();
        return new Plan(Action.HOTBAR_SWAP, selectedSlot, itemSlot, null, null);
    }

    static boolean isValidHotbarSlot(int slot) {
        return slot >= 0 && slot <= 8;
    }

    static boolean shouldChatNotify(boolean notify) {
        return notify;
    }

    private static Plan swapFailed() {
        return warning("warning.wavexin.turtle_potion_thrower.swap_failed", "Failed to swap to the turtle potion slot.");
    }

    private static Plan warning(String key, String fallback) {
        return new Plan(Action.WARNING, -1, -1, key, fallback);
    }
}
