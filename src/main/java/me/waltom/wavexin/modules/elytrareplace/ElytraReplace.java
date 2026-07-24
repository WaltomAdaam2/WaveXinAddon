package me.waltom.wavexin.modules.elytrareplace;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinModule;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class ElytraReplace extends WaveXinModule {
    private static final int ELYTRA_MAX_DAMAGE = new ItemStack(Items.ELYTRA).getMaxDamage();
    private static final int REPLACE_RETRY_DELAY_TICKS = 5;
    private static final int NO_REPLACEMENT_WARNING_DELAY_TICKS = 100;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> durabilityThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("Durability Threshold")
        .description("Replaces the equipped elytra when its remaining durability reaches this value")
        .defaultValue(2)
        .min(1)
        .max(ELYTRA_MAX_DAMAGE - 1)
        .sliderMin(1)
        .sliderMax(ELYTRA_MAX_DAMAGE - 1)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("Chat Feedback")
        .description("Shows elytra replacement and compatibility messages in chat")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyWhileFlying = sgGeneral.add(new BoolSetting.Builder()
        .name("Only While Flying")
        .description("Only replaces the equipped elytra while the player is gliding")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> inventoryTweaksCompatibility = sgGeneral.add(new BoolSetting.Builder()
        .name("InventoryTweaks Compatibility")
        .description("Temporarily disables Inventory Tweaks while replacing the elytra")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> compatibilityDelay = sgGeneral.add(new IntSetting.Builder()
        .name("Compatibility Delay")
        .description("Ticks to wait before restoring Inventory Tweaks after a replacement")
        .defaultValue(10)
        .min(1)
        .max(60)
        .sliderMin(1)
        .sliderMax(60)
        .visible(inventoryTweaksCompatibility::get)
        .build()
    );

    private boolean inventoryTweaksWasActive;
    private int inventoryTweaksRestoreCountdown;
    private int replaceRetryCountdown;
    private int noReplacementWarningCountdown;

    public ElytraReplace() {
        super(
            WaveXinAddon.CATEGORY,
            "elytra-replace",
            "Automatically replaces a nearly broken equipped elytra with a usable one from the inventory."
        );
    }

    @Override
    public void onActivate() {
        inventoryTweaksWasActive = false;
        inventoryTweaksRestoreCountdown = 0;
        replaceRetryCountdown = 0;
        noReplacementWarningCountdown = 0;
    }

    @Override
    public void onDeactivate() {
        restoreInventoryTweaks();
        inventoryTweaksRestoreCountdown = 0;
        replaceRetryCountdown = 0;
        noReplacementWarningCountdown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        updateCountdowns();

        if (mc.player == null || mc.world == null || replaceRetryCountdown > 0) return;

        ItemStack equipped = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!equipped.isOf(Items.ELYTRA)) return;
        if (onlyWhileFlying.get() && !mc.player.isGliding()) return;

        int remainingDurability = remainingDurability(equipped);
        if (remainingDurability > durabilityThreshold.get()) return;

        int[] replacementDurability = {0};
        FindItemResult replacement = InvUtils.find(stack -> {
            if (!stack.isOf(Items.ELYTRA)) return false;

            int remaining = remainingDurability(stack);
            if (remaining <= durabilityThreshold.get()) return false;

            replacementDurability[0] = remaining;
            return true;
        });

        if (!replacement.found()) {
            warnNoReplacement();
            return;
        }

        temporarilyDisableInventoryTweaks();
        InvUtils.move().from(replacement.slot()).toArmor(2);
        replaceRetryCountdown = REPLACE_RETRY_DELAY_TICKS;
        noReplacementWarningCountdown = 0;

        if (chatFeedback.get()) {
            infoKey(
                "message.wavexin.elytra_replace.replaced",
                "Replaced elytra (remaining durability: %d -> %d).",
                remainingDurability,
                replacementDurability[0]
            );
        }

        if (inventoryTweaksWasActive) {
            inventoryTweaksRestoreCountdown = Math.max(
                inventoryTweaksRestoreCountdown,
                compatibilityDelay.get()
            );
        }
    }

    private void updateCountdowns() {
        if (replaceRetryCountdown > 0) replaceRetryCountdown--;
        if (noReplacementWarningCountdown > 0) noReplacementWarningCountdown--;

        if (inventoryTweaksRestoreCountdown > 0) {
            inventoryTweaksRestoreCountdown--;
            if (inventoryTweaksRestoreCountdown == 0) restoreInventoryTweaks();
        }
    }

    private void warnNoReplacement() {
        if (!chatFeedback.get() || noReplacementWarningCountdown > 0) return;

        warningKey(
            "warning.wavexin.elytra_replace.no_replacement",
            "No replacement elytra was found with durability above %d.",
            durabilityThreshold.get()
        );
        noReplacementWarningCountdown = NO_REPLACEMENT_WARNING_DELAY_TICKS;
    }

    private void temporarilyDisableInventoryTweaks() {
        if (!inventoryTweaksCompatibility.get() || inventoryTweaksWasActive) return;

        InventoryTweaks inventoryTweaks = Modules.get().get(InventoryTweaks.class);
        if (inventoryTweaks == null || !inventoryTweaks.isActive()) return;

        inventoryTweaksWasActive = true;
        inventoryTweaks.toggle();

        if (chatFeedback.get()) {
            infoKey(
                "message.wavexin.elytra_replace.inventory_tweaks_disabled",
                "Temporarily disabled Inventory Tweaks."
            );
        }
    }

    private void restoreInventoryTweaks() {
        if (!inventoryTweaksWasActive) return;

        InventoryTweaks inventoryTweaks = Modules.get().get(InventoryTweaks.class);
        if (inventoryTweaks != null && !inventoryTweaks.isActive()) {
            inventoryTweaks.toggle();

            if (chatFeedback.get()) {
                infoKey(
                    "message.wavexin.elytra_replace.inventory_tweaks_restored",
                    "Restored Inventory Tweaks."
                );
            }
        }

        inventoryTweaksWasActive = false;
    }

    private static int remainingDurability(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamage();
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;

        ItemStack equipped = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!equipped.isOf(Items.ELYTRA)) return null;
        return Integer.toString(remainingDurability(equipped));
    }
}
