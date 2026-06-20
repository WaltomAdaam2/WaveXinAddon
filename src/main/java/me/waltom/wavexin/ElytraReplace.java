package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class ElytraReplace extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private static final int ELYTRA_MAX_DAMAGE = new ItemStack(Items.ELYTRA).getMaxDamage();

    
    private final Setting<Integer> replaceDurability = sgGeneral.add(new IntSetting.Builder()
        .name("Durability Threshold")
        .description("Elytra durability threshold for replacement")
        .defaultValue(2)
        .range(1, ELYTRA_MAX_DAMAGE - 1)
        .sliderRange(1, ELYTRA_MAX_DAMAGE - 1)
        .build()
    );

    
    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("Chat Feedback")
        .description("Sends chat feedback when replacing elytra")
        .defaultValue(true)
        .build()
    );

    
    private final Setting<Boolean> onlyWhenFlying = sgGeneral.add(new BoolSetting.Builder()
        .name("Only While Flying")
        .description("Only replaces elytra while gliding")
        .defaultValue(false)
        .build()
    );



    
    private final Setting<Boolean> temporaryDisableInventoryTweaks = sgGeneral.add(new BoolSetting.Builder()
        .name("InventoryTweaks Compatibility")
        .description("Temporarily disables InventoryTweaks while replacing elytra")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> reEnableDelay = sgGeneral.add(new IntSetting.Builder()
        .name("Compatibility Delay")
        .description("Delay before re-enabling InventoryTweaks after elytra replacement, in ticks")
        .defaultValue(10)
        .range(1, 60)
        .sliderMax(60)
        .visible(temporaryDisableInventoryTweaks::get)
        .build()
    );

    
    private boolean inventoryTweaksWasActive = false;
    private int reEnableCountdown = 0;

    public ElytraReplace() {
        super(WaveXinAddon.CATEGORY, "elytra-replace", "Automatically replaces damaged elytra.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        
        if (mc.player == null || mc.world == null) return;

        
        if (reEnableCountdown > 0) {
            reEnableCountdown--;
            if (reEnableCountdown == 0 && inventoryTweaksWasActive) {
                reEnableInventoryTweaks();
            }
        }

        
        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);

        
        if (chestStack.getItem() == Items.ELYTRA) {
            
            int remainingDurability = chestStack.getMaxDamage() - chestStack.getDamage();

            
            checkAndReplaceElytra(chestStack, remainingDurability);
        }
    }

    




    private void checkAndReplaceElytra(ItemStack chestStack, int remainingDurability) {
        
        if (onlyWhenFlying.get() && !mc.player.isGliding()) return;

        
        if (remainingDurability > replaceDurability.get()) return;

        
        FindItemResult elytra = InvUtils.find(stack -> {
            if (stack.getItem() != Items.ELYTRA) return false;
            int stackDurability = stack.getMaxDamage() - stack.getDamage();
            return stackDurability > replaceDurability.get();
        });

        
        if (!elytra.found()) {
            if (chatFeedback.get()) {
                warning("No replacement elytra found with durability > %d", replaceDurability.get());
            }
            return;
        }

        
        if (temporaryDisableInventoryTweaks.get()) {
            temporaryDisableInventoryTweaks();
        }

        
        InvUtils.move().from(elytra.slot()).toArmor(2);

        
        if (chatFeedback.get()) {
            info("Replaced elytra (durability: %d -> %d)",
                remainingDurability,
                mc.player.getEquippedStack(EquipmentSlot.CHEST).getMaxDamage() -
                    mc.player.getEquippedStack(EquipmentSlot.CHEST).getDamage());
        }

        
        if (temporaryDisableInventoryTweaks.get() && inventoryTweaksWasActive) {
            reEnableCountdown = reEnableDelay.get();
        }
    }

    


    private void temporaryDisableInventoryTweaks() {
        InventoryTweaks inventoryTweaks = Modules.get().get(InventoryTweaks.class);
        if (inventoryTweaks != null && inventoryTweaks.isActive()) {
            inventoryTweaksWasActive = true;
            inventoryTweaks.toggle();
            if (chatFeedback.get()) {
                info("Temporarily disabled InventoryTweaks");
            }
        } else {
            inventoryTweaksWasActive = false;
        }
    }

    


    private void reEnableInventoryTweaks() {
        InventoryTweaks inventoryTweaks = Modules.get().get(InventoryTweaks.class);
        if (inventoryTweaks != null && !inventoryTweaks.isActive()) {
            inventoryTweaks.toggle();
            if (chatFeedback.get()) {
                info("Re-enabled InventoryTweaks");
            }
        }
        inventoryTweaksWasActive = false;
    }



    @Override
    public void onDeactivate() {
        
        if (inventoryTweaksWasActive) {
            reEnableInventoryTweaks();
        }
        reEnableCountdown = 0;
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;

        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestStack.getItem() != Items.ELYTRA) return "No Elytra";

        int remainingDurability = chestStack.getMaxDamage() - chestStack.getDamage();
        return String.valueOf(remainingDurability);
    }


}
