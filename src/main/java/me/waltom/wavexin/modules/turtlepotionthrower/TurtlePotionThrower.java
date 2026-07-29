package me.waltom.wavexin.modules.turtlepotionthrower;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

public class TurtlePotionThrower extends WaveXinModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> quickSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("Quick Swap")
        .description("Allows inventory turtle potions to be thrown by temporarily swapping them into the selected hotbar slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("Notify")
        .description("Shows a warning when no throwable turtle potion can be used.")
        .defaultValue(true)
        .build()
    );

    public TurtlePotionThrower() {
        super(WaveXinAddon.CATEGORY, "turtle-potion-thrower", "Throws a splash turtle potion from your inventory when bound.");
    }

    @Override
    public void onActivate() {
        try {
            throwTurtlePotion();
        } finally {
            if (isActive()) toggle();
        }
    }

    @Override
    public void sendToggledMsg() {
        // One-shot bind action; failure feedback is handled by Notify.
    }

    private void throwTurtlePotion() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null) {
            warnFailure(TurtlePotionThrowerPlan.unavailable());
            return;
        }

        TurtlePotionThrowerPlan.Action preferredHand = TurtlePotionThrowerPlan.preferredHandAction(
            isThrowableTurtlePotion(mc.player.getStackInHand(Hand.MAIN_HAND)),
            isThrowableTurtlePotion(mc.player.getStackInHand(Hand.OFF_HAND))
        );
        if (preferredHand == TurtlePotionThrowerPlan.Action.USE_MAIN_HAND) {
            usePotion(Hand.MAIN_HAND);
            return;
        }
        if (preferredHand == TurtlePotionThrowerPlan.Action.USE_OFFHAND) {
            usePotion(Hand.OFF_HAND);
            return;
        }

        FindItemResult result = InvUtils.find(TurtlePotionThrower::isThrowableTurtlePotion);
        TurtlePotionThrowerPlan.Plan plan = TurtlePotionThrowerPlan.choose(
            result.found(),
            false,
            false,
            result.isHotbar(),
            mc.player.getInventory().getSelectedSlot(),
            result.slot(),
            quickSwap.get()
        );

        if (plan.warns()) {
            warnFailure(plan);
            return;
        }

        switch (plan.action()) {
            case USE_MAIN_HAND -> usePotion(Hand.MAIN_HAND);
            case USE_OFFHAND -> usePotion(Hand.OFF_HAND);
            case HOTBAR_SWAP -> throwFromHotbarSlot(plan.selectedSlot(), plan.itemSlot());
            case QUICK_SWAP -> throwWithQuickSwap(plan.selectedSlot(), plan.itemSlot());
            case WARNING -> warnFailure(plan);
        }
    }

    private void throwWithQuickSwap(int selectedSlot, int itemSlot) {
        Object player = mc.player;
        Object world = mc.world;
        boolean swapCompleted = false;
        try {
            try {
                InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
                swapCompleted = true;
            } catch (RuntimeException e) {
                warnFailure("warning.wavexin.turtle_potion_thrower.swap_failed", "Failed to swap the turtle potion into the selected hotbar slot.", e);
                return;
            }

            usePotion(Hand.MAIN_HAND);
        } finally {
            boolean selectedNowContainsPotion = samePlayerWorld(player, world)
                && TurtlePotionThrowerPlan.isValidHotbarSlot(selectedSlot)
                && isThrowableTurtlePotion(mc.player.getInventory().getStack(selectedSlot));
            if (TurtlePotionThrowerPlan.shouldAttemptQuickSwapRestore(swapCompleted, selectedNowContainsPotion)) {
                restoreQuickSwap(selectedSlot, itemSlot, player, world);
            }
        }
    }

    private void restoreQuickSwap(int selectedSlot, int itemSlot, Object player, Object world) {
        if (!samePlayerWorld(player, world)) {
            logRestoreSkipped();
            return;
        }

        try {
            InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
        } catch (RuntimeException e) {
            warnFailure("warning.wavexin.turtle_potion_thrower.restore_failed", "Failed to restore the previous slot after throwing turtle potion.", e);
        }
    }

    private void throwFromHotbarSlot(int selectedSlot, int itemSlot) {
        Object player = mc.player;
        Object world = mc.world;
        boolean swapCompleted = false;
        try {
            try {
                if (!InvUtils.swap(itemSlot, false)) {
                    warnFailure("warning.wavexin.turtle_potion_thrower.swap_failed", "Failed to swap to the turtle potion slot.");
                    return;
                }
                swapCompleted = true;
            } catch (RuntimeException e) {
                warnFailure("warning.wavexin.turtle_potion_thrower.swap_failed", "Failed to swap to the turtle potion slot.", e);
                return;
            }

            usePotion(Hand.MAIN_HAND);
        } finally {
            boolean selectedSlotChanged = samePlayerWorld(player, world)
                && mc.player.getInventory().getSelectedSlot() != selectedSlot;
            if (TurtlePotionThrowerPlan.shouldAttemptHotbarRestore(swapCompleted, selectedSlotChanged)) {
                restoreSelectedSlot(selectedSlot, player, world);
            }
        }
    }

    private void restoreSelectedSlot(int selectedSlot, Object player, Object world) {
        if (!TurtlePotionThrowerPlan.isValidHotbarSlot(selectedSlot)) return;
        if (!samePlayerWorld(player, world)) {
            logRestoreSkipped();
            return;
        }

        try {
            if (!InvUtils.swap(selectedSlot, false)) {
                warnFailure("warning.wavexin.turtle_potion_thrower.restore_failed", "Failed to restore the previous hotbar slot after throwing turtle potion.");
            }
        } catch (RuntimeException e) {
            warnFailure("warning.wavexin.turtle_potion_thrower.restore_failed", "Failed to restore the previous hotbar slot after throwing turtle potion.", e);
        }
    }

    private boolean samePlayerWorld(Object player, Object world) {
        return mc.player == player && mc.world == world;
    }

    private void logRestoreSkipped() {
        WaveXinAddon.LOG.warn("[WaveXinDebug] module={} message=Skipped turtle potion slot restore because player or world changed.", getClass().getSimpleName());
    }

    private boolean usePotion(Hand hand) {
        try {
            ActionResult result = mc.interactionManager.interactItem(mc.player, hand);
            if (result.isAccepted()) return true;

            warnFailure("warning.wavexin.turtle_potion_thrower.throw_failed", "Turtle potion throw was rejected.");
            return false;
        } catch (RuntimeException e) {
            warnFailure("warning.wavexin.turtle_potion_thrower.throw_failed", "Turtle potion throw failed unexpectedly.", e);
            return false;
        }
    }

    static boolean isThrowableTurtlePotion(ItemStack stack) {
        if (stack == null || !stack.isOf(Items.SPLASH_POTION)) return false;

        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        return contents != null
            && (contents.matches(Potions.TURTLE_MASTER)
                || contents.matches(Potions.LONG_TURTLE_MASTER)
                || contents.matches(Potions.STRONG_TURTLE_MASTER));
    }

    private void warnFailure(TurtlePotionThrowerPlan.Plan plan) {
        warnFailure(plan.warningKey(), plan.fallback());
    }

    private void warnFailure(String key, String fallback) {
        String message = WaveXinI18n.tr(key, fallback);
        WaveXinAddon.LOG.warn("[WaveXinDebug] module={} message={}", getClass().getSimpleName(), message);
        if (TurtlePotionThrowerPlan.shouldChatNotify(notify.get())) ChatUtils.warning(message);
    }

    private void warnFailure(String key, String fallback, RuntimeException cause) {
        String message = WaveXinI18n.tr(key, fallback);
        WaveXinAddon.LOG.warn("[WaveXinDebug] module={} message={}", getClass().getSimpleName(), message, cause);
        if (TurtlePotionThrowerPlan.shouldChatNotify(notify.get())) ChatUtils.warning(message);
    }
}
