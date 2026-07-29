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
            warnFailure("warning.wavexin.turtle_potion_thrower.unavailable", "Cannot throw turtle potion right now.");
            return;
        }

        FindItemResult result = InvUtils.find(TurtlePotionThrower::isThrowableTurtlePotion);
        if (!result.found()) {
            warnFailure("warning.wavexin.turtle_potion_thrower.no_potion", "No throwable turtle potion was found.");
            return;
        }

        if (result.isMainHand()) {
            usePotion(Hand.MAIN_HAND);
            return;
        }
        if (result.isOffhand()) {
            usePotion(Hand.OFF_HAND);
            return;
        }

        if (!result.isHotbar() && !quickSwap.get()) {
            warnFailure("warning.wavexin.turtle_potion_thrower.hotbar_required", "No turtle potion was found in your hotbar. Enable Quick Swap to use inventory potions.");
            return;
        }

        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        int itemSlot = result.slot();

        if (quickSwap.get()) {
            InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
            try {
                usePotion(Hand.MAIN_HAND);
            } finally {
                InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
            }
            return;
        }

        if (!InvUtils.swap(itemSlot, true)) {
            warnFailure("warning.wavexin.turtle_potion_thrower.swap_failed", "Failed to swap to the turtle potion slot.");
            return;
        }

        try {
            usePotion(Hand.MAIN_HAND);
        } finally {
            if (!InvUtils.swapBack()) {
                warnFailure("warning.wavexin.turtle_potion_thrower.restore_failed", "Failed to restore the previous hotbar slot after throwing turtle potion.");
            }
        }
    }

    private boolean usePotion(Hand hand) {
        ActionResult result = mc.interactionManager.interactItem(mc.player, hand);
        if (result.isAccepted()) return true;

        warnFailure("warning.wavexin.turtle_potion_thrower.throw_failed", "Turtle potion throw was rejected.");
        return false;
    }

    static boolean isThrowableTurtlePotion(ItemStack stack) {
        if (stack == null || !stack.isOf(Items.SPLASH_POTION)) return false;

        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        return contents != null
            && (contents.matches(Potions.TURTLE_MASTER)
                || contents.matches(Potions.LONG_TURTLE_MASTER)
                || contents.matches(Potions.STRONG_TURTLE_MASTER));
    }

    private void warnFailure(String key, String fallback) {
        String message = WaveXinI18n.tr(key, fallback);
        WaveXinAddon.LOG.warn("[WaveXinDebug] module={} message={}", getClass().getSimpleName(), message);
        if (notify.get()) ChatUtils.warning(message);
    }
}
