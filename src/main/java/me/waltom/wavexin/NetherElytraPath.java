package me.waltom.wavexin;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Items;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class NetherElytraPath extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int LANDING_TIMEOUT_TICKS = 20 * 60;
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private Progress currentProgress; 
    private ReplenishProgress replenishProgress; 
    private int timer = 0; 
    private int landingTicks = 0;
    private final BlockPos.Mutable bp = new BlockPos.Mutable(); 
    boolean shouldLandForElytra = false;
    boolean shouldLandForFirework = false;
    private boolean savedBaritoneSettings;
    private boolean previousElytraTermsAccepted;
    private long previousElytraNetherSeed;
    private boolean previousElytraPredictTerrain;
    private boolean previousElytraAutoJump;
    private int previousElytraMinFireworksBeforeLanding;
    private int previousElytraMinimumDurability;

    
    private final Setting<Integer> targetX = sgGeneral.add(new IntSetting.Builder()
            .name("Target X")
            .description("Target X")
            .defaultValue(0)
            .range(-30000000, 30000000)
            .sliderRange(-30000000, 30000000)
            .build());

    private final Setting<Integer> targetZ = sgGeneral.add(new IntSetting.Builder()
            .name("Target Z")
            .description("Target Z")
            .defaultValue(0)
            .range(-30000000, 30000000)
            .sliderRange(-30000000, 30000000)
            .build());

    public NetherElytraPath() {
        super(WaveXinAddon.CATEGORY, "nether-elytra-path", "Automatic Nether elytra path flight helper");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            ChatUtils.error("Enter a world before enabling NetherElytraPath.");
            toggle();
            return;
        }

        currentProgress = Progress.Pathing;
        resetReplenishState();
        shouldLandForElytra = false;
        shouldLandForFirework = false;

        
        Settings settings = BaritoneAPI.getSettings();
        saveBaritoneSettings(settings);
        settings.elytraTermsAccepted.value = true;
        settings.elytraNetherSeed.value = 3763250021837776656L;
        settings.elytraPredictTerrain.value = true;
        settings.elytraAutoJump.value = true;

        
        settings.elytraMinFireworksBeforeLanding.value = -1;
        settings.elytraMinimumDurability.value = -1;

        
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager()
                .execute("goal " + targetX.get() + " ~ " + targetZ.get());
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("elytra");
    }

    @Override
    public void onDeactivate() {
        
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
        restoreBaritoneSettings();
        resetReplenishState();
        currentProgress = null;
        shouldLandForElytra = false;
        shouldLandForFirework = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null)
            return;

        if (currentProgress == null) {
            ChatUtils.error("NetherElytraPath state was invalid and has been disabled to avoid a crash.");
            toggle();
            return;
        }

        switch (currentProgress) {
            case Pathing -> handlePathing();
            case Landing -> handleLanding();
            case Replenishing -> handleReplenishing();
            case IDLE -> {}
        }
    }

    private void handlePathing() {
        
        int elytraCount = InvUtils.find(Items.ELYTRA).count();
        if (elytraCount == 1) {
            FindItemResult elytraResult = InvUtils.find(Items.ELYTRA);
            if (elytraResult.found()) {
                ItemStack elytraStack = mc.player.getInventory().getStack(elytraResult.slot());
                int durability = elytraStack.getMaxDamage() - elytraStack.getDamage();
                if (durability < 10) {
                    shouldLandForElytra = true;
                    ChatUtils.info("Elytra durability is low; preparing to land...");
                }
            }
        }

        
        int fireworkCount = InvUtils.find(Items.FIREWORK_ROCKET).count();
        if (fireworkCount <= 5) {
            shouldLandForFirework = true;
            ChatUtils.info("Firework count is low; preparing to land...");
        }

        
        if (shouldLandForElytra || shouldLandForFirework) {
            int currentX = mc.player.getBlockX();
            int currentZ = mc.player.getBlockZ();

            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager()
                    .execute("goal " + currentX + " ~ " + currentZ);

            landingTicks = 0;
            currentProgress = Progress.Landing;
        }

    }

    private void handleLanding() {
        if (++landingTicks > LANDING_TIMEOUT_TICKS) {
            ChatUtils.warning("NetherElytraPath landing timed out; disabling safely.");
            disableWithWarning();
            return;
        }

        
        boolean isPathing = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();
        boolean isOnGround = mc.player.isOnGround();
        boolean isGliding = mc.player.isGliding();

        if (!isPathing && isOnGround && !isGliding) {
            currentProgress = Progress.Replenishing;
            replenishProgress = ReplenishProgress.TAKE_SHULKER;
            timer = 0;
            landingTicks = 0;
            ChatUtils.info("Landing complete; starting refill process...");
        }
    }

    private void handleReplenishing() {
        if (replenishProgress == null) {
            replenishProgress = ReplenishProgress.TAKE_SHULKER;
        }

        
        if (timer > 0) {
            timer--;
            return;
        }

        switch (replenishProgress) {
            case TAKE_SHULKER -> {
                if (findShulkerWithFireworks()) {
                    replenishProgress = ReplenishProgress.PLACE_ON_GROUND;
                    timer = 10;
                    ChatUtils.info("Found a shulker box with fireworks and moved it to the hotbar...");
                } else {
                    ChatUtils.error("No shulker box containing fireworks was found.");
                    disableWithWarning();
                }
            }
            case PLACE_ON_GROUND -> {
                if (placeShulkerOnGround()) {
                    replenishProgress = ReplenishProgress.OPEN_SHULKER;
                    timer = 10;
                    ChatUtils.info("Placed shulker box; continuing...");
                } else {
                    ChatUtils.error("Could not place the shulker box.");
                    disableWithWarning();
                }
            }
            case OPEN_SHULKER -> {
                if (openShulkerBox()) {
                    replenishProgress = ReplenishProgress.TAKE_FIREWORKS;
                    timer = 10;
                    ChatUtils.info("Opened shulker box; continuing...");
                } else {
                    ChatUtils.error("Could not open the shulker box.");
                    disableWithWarning();
                }
            }
            case TAKE_FIREWORKS -> {
                if (takeFireworks()) {
                    cleanupPlacedShulker();
                    resumePathing();
                    timer = 10;
                    ChatUtils.info("Fireworks refilled; resuming target path...");
                } else {
                    ChatUtils.error("Could not take fireworks.");
                    disableWithWarning();
                }
            }
        }
    }

    

    private void resetReplenishState() {
        replenishProgress = ReplenishProgress.TAKE_SHULKER;
        timer = 0;
        landingTicks = 0;
    }

    private void saveBaritoneSettings(Settings settings) {
        if (savedBaritoneSettings) return;

        previousElytraTermsAccepted = settings.elytraTermsAccepted.value;
        previousElytraNetherSeed = settings.elytraNetherSeed.value;
        previousElytraPredictTerrain = settings.elytraPredictTerrain.value;
        previousElytraAutoJump = settings.elytraAutoJump.value;
        previousElytraMinFireworksBeforeLanding = settings.elytraMinFireworksBeforeLanding.value;
        previousElytraMinimumDurability = settings.elytraMinimumDurability.value;
        savedBaritoneSettings = true;
    }

    private void restoreBaritoneSettings() {
        if (!savedBaritoneSettings) return;

        Settings settings = BaritoneAPI.getSettings();
        settings.elytraTermsAccepted.value = previousElytraTermsAccepted;
        settings.elytraNetherSeed.value = previousElytraNetherSeed;
        settings.elytraPredictTerrain.value = previousElytraPredictTerrain;
        settings.elytraAutoJump.value = previousElytraAutoJump;
        settings.elytraMinFireworksBeforeLanding.value = previousElytraMinFireworksBeforeLanding;
        settings.elytraMinimumDurability.value = previousElytraMinimumDurability;
        savedBaritoneSettings = false;
    }

    private void disableWithWarning() {
        ChatUtils.warning("NetherElytraPath disabled. Check refill items and nearby blocks.");
        toggle();
    }

    private boolean findShulkerWithFireworks() {
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);

            
            if (!(stack.getItem() instanceof net.minecraft.item.BlockItem blockItem)) {
                continue;
            }

            
            if (!(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
                continue;
            }

            
            if (isShulkerFullOfFireworks(stack)) {
                InvUtils.move().from(i).toHotbar(mc.player.getInventory().getSelectedSlot());
                return true;
            }
        }
        return false;
    }

    private boolean isShulkerFullOfFireworks(ItemStack shulkerStack) {
        ContainerComponent container = shulkerStack.get(DataComponentTypes.CONTAINER);
        if (container == null) {
            return false;
        }

        boolean hasAnyItem = false;
        for (ItemStack stack : container.iterateNonEmpty()) {
            hasAnyItem = true;
            if (!stack.isOf(Items.FIREWORK_ROCKET)) {
                return false;
            }
        }

        return hasAnyItem;
    }

    private boolean placeShulkerOnGround() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return false;
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int[][] offsets = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };

        for (int yOffset = 0; yOffset >= -1; yOffset--) {
            for (int[] offset : offsets) {
                BlockPos candidate = playerPos.add(offset[0], yOffset, offset[1]);
                if (!BlockUtils.canPlace(candidate, true)) continue;

                if (place(candidate)) {
                    bp.set(candidate);
                    return true;
                }
            }
        }

        return false;
    }

    private boolean place(BlockPos bp) {
        
        FindItemResult shulkerItem = InvUtils.findInHotbar(itemStack -> {
            if (itemStack.getItem() instanceof BlockItem blockItem) {
                return blockItem.getBlock() instanceof ShulkerBoxBlock;
            }
            return false;
        });

        
        if (!shulkerItem.found()) {
            ChatUtils.error("No shulker box found.");
            return false;
        }

        
        if (BlockUtils.place(bp, shulkerItem, true, 50, true, true)) {
            ChatUtils.info("Shulker box placed.");
            return true;
        }
        return false;
    }

    private boolean openShulkerBox() {
        
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return false;
        }

        
        if (bp == null) {
            ChatUtils.error("Shulker box position is invalid.");
            return false;
        }

        
        BlockState blockState = mc.world.getBlockState(bp);
        if (!(blockState.getBlock() instanceof ShulkerBoxBlock)) {
            ChatUtils.error("The selected position is not a shulker box.");
            return false;
        }

        
        Rotations.rotate(Rotations.getYaw(bp), Rotations.getPitch(bp), () -> {
            
            BlockHitResult hitResult = new BlockHitResult(
                    Vec3d.ofCenter(bp), 
                    Direction.UP, 
                    bp, 
                    false 
            );

            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        });

        return true;
    }

    private boolean takeFireworks() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return false;
        }

        int before = InvUtils.find(Items.FIREWORK_ROCKET).count();
        if (before > 5) {
            if (mc.currentScreen instanceof HandledScreen<?>) {
                mc.player.closeHandledScreen();
            }
            return true;
        }

        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            return false;
        }

        var handler = screen.getScreenHandler();
        int playerInventoryStart = Math.max(0, handler.slots.size() - 36);
        if (playerInventoryStart <= 0 || playerInventoryStart > handler.slots.size()) {
            return false;
        }

        boolean movedAny = false;

        for (int i = 0; i < playerInventoryStart; i++) {
            var slot = handler.slots.get(i);
            if (slot.hasStack() && slot.getStack().isOf(Items.FIREWORK_ROCKET)) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                movedAny = true;
            }
        }

        if (movedAny) {
            mc.player.closeHandledScreen();
        }

        int after = InvUtils.find(Items.FIREWORK_ROCKET).count();
        return movedAny || after > before || after > 5;
    }

    private void cleanupPlacedShulker() {
        if (mc.player == null || mc.world == null) return;

        BlockPos placedPos = bp.toImmutable();
        if (!(mc.world.getBlockState(placedPos).getBlock() instanceof ShulkerBoxBlock)) return;

        if (!BlockUtils.breakBlock(placedPos, true)) {
            ChatUtils.warning("Placed shulker cleanup was attempted but not completed; pick it up manually if it remains.");
        }
    }

    private void resumePathing() {
        currentProgress = Progress.Pathing;
        resetReplenishState();
        shouldLandForElytra = false;
        shouldLandForFirework = false;

        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager()
                .execute("goal " + targetX.get() + " ~ " + targetZ.get());
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("elytra");
    }

    

    private enum Progress {
        Pathing, 
        Landing, 
        Replenishing, 
        IDLE, 
    }

    private enum ReplenishProgress {
        TAKE_SHULKER, 
        PLACE_ON_GROUND, 
        OPEN_SHULKER, 
        TAKE_FIREWORKS, 
    }
}
