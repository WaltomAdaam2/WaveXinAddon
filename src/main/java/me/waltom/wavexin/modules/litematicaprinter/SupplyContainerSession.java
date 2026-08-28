package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class SupplyContainerSession {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Map<BlockPos, Map<Item, Integer>> observed = new HashMap<>();
    private ScreenHandler playerHandler;
    private BlockPos openTarget;

    boolean open(BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null || pos == null) return false;
        playerHandler = mc.player.playerScreenHandler;
        openTarget = pos.toImmutable();
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        if (result.isAccepted()) mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        return result.isAccepted();
    }

    boolean isOpen() {
        return mc.player != null
            && playerHandler != null
            && mc.player.currentScreenHandler != playerHandler
            && mc.player.currentScreenHandler != mc.player.playerScreenHandler;
    }

    TakeResult take(Map<Item, Integer> missing) {
        if (!isOpen() || mc.interactionManager == null) return TakeResult.NOT_OPEN;
        ScreenHandler handler = mc.player.currentScreenHandler;
        Map<Item, Integer> contents = new LinkedHashMap<>();
        Slot matching = null;
        boolean matchingButFull = false;

        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            contents.merge(stack.getItem(), stack.getCount(), Integer::sum);
            if (matching == null && missing.getOrDefault(stack.getItem(), 0) > 0) {
                if (canAccept(stack)) matching = slot;
                else matchingButFull = true;
            }
        }
        if (openTarget != null) observed.put(openTarget, Map.copyOf(contents));

        if (matching == null) return matchingButFull ? TakeResult.INVENTORY_FULL : TakeResult.NO_MATCH;
        mc.interactionManager.clickSlot(handler.syncId, matching.id, 0, SlotActionType.QUICK_MOVE, mc.player);
        return TakeResult.MOVED;
    }

    boolean observedMaterial(BlockPos pos, Item item) {
        return observed.getOrDefault(pos, Map.of()).getOrDefault(item, 0) > 0;
    }

    void close() {
        if (mc.player != null && isOpen()) mc.player.closeHandledScreen();
        playerHandler = null;
        openTarget = null;
    }

    void reset() {
        close();
        observed.clear();
    }

    private boolean canAccept(ItemStack incoming) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack current = mc.player.getInventory().getStack(slot);
            if (current.isEmpty()) return true;
            if (ItemStack.areItemsAndComponentsEqual(current, incoming) && current.getCount() < current.getMaxCount()) return true;
        }
        return false;
    }

    enum TakeResult {
        MOVED,
        NO_MATCH,
        INVENTORY_FULL,
        NOT_OPEN
    }
}
