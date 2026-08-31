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
    private Transfer transfer;
    private Item lastMovedItem;

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

    boolean isTransferring() {
        return transfer != null;
    }

    TransferResult take(Map<Item, Integer> missing, boolean allowInventoryPull) {
        if (!isOpen() || mc.interactionManager == null) return TransferResult.NOT_OPEN;
        if (transfer != null) return advance(allowInventoryPull);
        ScreenHandler handler = mc.player.currentScreenHandler;
        Map<Item, Integer> contents = new LinkedHashMap<>();
        Slot source = null;
        boolean matchingButFull = false;

        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            contents.merge(stack.getItem(), stack.getCount(), Integer::sum);
            if (source == null && missing.getOrDefault(stack.getItem(), 0) > 0) {
                if (findPlayerDestination(handler, stack, allowInventoryPull) != null) source = slot;
                else matchingButFull = true;
            }
        }
        if (openTarget != null) observed.put(openTarget, Map.copyOf(contents));

        if (source == null) return matchingButFull ? TransferResult.INVENTORY_FULL : TransferResult.NO_MATCH;
        int amount = plannedTransferAmount(
            missing.getOrDefault(source.getStack().getItem(), 0), source.getStack().getCount()
        );
        transfer = new Transfer(TransferMode.TAKE, source.id, amount);
        return advance(allowInventoryPull);
    }

    TransferResult takeWholeStack(Map<Item, Integer> missing, boolean allowInventoryPull) {
        if (!isOpen() || mc.interactionManager == null) return TransferResult.NOT_OPEN;
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) return TransferResult.CURSOR_BLOCKED;

        ScreenHandler handler = mc.player.currentScreenHandler;
        observe(handler);
        boolean matchingButFull = false;
        for (Item required : missing.keySet()) {
            for (Slot slot : handler.slots) {
                if (slot.inventory == mc.player.getInventory()) continue;
                ItemStack stack = slot.getStack();
                if (!stack.isOf(required)) continue;
                if (playerCapacity(handler, stack, allowInventoryPull) < stack.getCount()) {
                    matchingButFull = true;
                    continue;
                }
                lastMovedItem = stack.getItem();
                mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                return TransferResult.MOVED;
            }
        }
        return matchingButFull ? TransferResult.INVENTORY_FULL : TransferResult.NO_MATCH;
    }

    Map<Item, Integer> observeOpenContainer() {
        if (!isOpen()) return Map.of();
        return observe(mc.player.currentScreenHandler);
    }

    Map<Item, Integer> observedContents(BlockPos pos) {
        return observed.getOrDefault(pos, Map.of());
    }

    Item consumeLastMovedItem() {
        Item result = lastMovedItem;
        lastMovedItem = null;
        return result;
    }

    boolean hasObserved(BlockPos pos) {
        return observed.containsKey(pos);
    }

    TransferResult returnExcess(Map<Item, Integer> excess, boolean allowInventoryPull) {
        if (!isOpen() || mc.interactionManager == null) return TransferResult.NOT_OPEN;
        if (transfer != null) return advance(allowInventoryPull);
        ScreenHandler handler = mc.player.currentScreenHandler;
        Slot source = null;
        boolean matchingButFull = false;

        for (Slot slot : handler.slots) {
            if (!isUsablePlayerSlot(slot, allowInventoryPull)) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || excess.getOrDefault(stack.getItem(), 0) <= 0) continue;
            if (findContainerDestination(handler, stack) != null) {
                source = slot;
                break;
            }
            matchingButFull = true;
        }

        if (source == null) return matchingButFull ? TransferResult.CONTAINER_FULL : TransferResult.NO_MATCH;
        int amount = plannedTransferAmount(
            excess.getOrDefault(source.getStack().getItem(), 0), source.getStack().getCount()
        );
        transfer = new Transfer(TransferMode.RETURN, source.id, amount);
        return advance(allowInventoryPull);
    }

    boolean observedMaterial(BlockPos pos, Item item) {
        return observed.getOrDefault(pos, Map.of()).getOrDefault(item, 0) > 0;
    }

    void close() {
        returnCursorToSource();
        if (mc.player != null && isOpen()) mc.player.closeHandledScreen();
        playerHandler = null;
        openTarget = null;
        transfer = null;
        lastMovedItem = null;
    }

    void reset() {
        close();
        observed.clear();
    }

    private TransferResult advance(boolean allowInventoryPull) {
        if (!isOpen() || mc.interactionManager == null || transfer == null) return TransferResult.NOT_OPEN;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (transfer.phase == TransferPhase.PICK_SOURCE) {
            if (!handler.getCursorStack().isEmpty()) {
                transfer = null;
                return TransferResult.CURSOR_BLOCKED;
            }
            click(handler, transfer.sourceSlot, 0);
            transfer.phase = TransferPhase.DISTRIBUTE;
            return TransferResult.PROGRESSED;
        }

        ItemStack cursor = handler.getCursorStack();
        if (transfer.phase == TransferPhase.DISTRIBUTE) {
            if (cursor.isEmpty()) return finishTransfer();
            if (transfer.remaining <= 0) {
                transfer.phase = TransferPhase.RETURN_REMAINDER;
                return TransferResult.PROGRESSED;
            }

            Slot destination = transfer.mode == TransferMode.TAKE
                ? findPlayerDestination(handler, cursor, allowInventoryPull)
                : findContainerDestination(handler, cursor);
            if (destination == null) {
                transfer.phase = TransferPhase.RETURN_REMAINDER;
                return TransferResult.PROGRESSED;
            }

            int capacity = capacity(destination, cursor);
            if (capacity <= 0) {
                transfer.phase = TransferPhase.RETURN_REMAINDER;
                return TransferResult.PROGRESSED;
            }
            int moved = Math.min(transfer.remaining, Math.min(cursor.getCount(), capacity));
            if (moved == cursor.getCount()) {
                click(handler, destination.id, 0);
                transfer.remaining -= moved;
                transfer.moved += moved;
            } else {
                click(handler, destination.id, 1);
                transfer.remaining--;
                transfer.moved++;
            }
            return TransferResult.PROGRESSED;
        }

        if (transfer.phase == TransferPhase.RETURN_REMAINDER) {
            if (cursor.isEmpty()) return finishTransfer();
            click(handler, transfer.sourceSlot, 0);
            transfer.phase = TransferPhase.COMPLETE;
            return TransferResult.PROGRESSED;
        }

        if (!handler.getCursorStack().isEmpty()) return TransferResult.CURSOR_BLOCKED;
        return finishTransfer();
    }

    private Map<Item, Integer> observe(ScreenHandler handler) {
        Map<Item, Integer> contents = new LinkedHashMap<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty()) contents.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        Map<Item, Integer> snapshot = Map.copyOf(contents);
        if (openTarget != null) observed.put(openTarget, snapshot);
        return snapshot;
    }

    private int playerCapacity(ScreenHandler handler, ItemStack stack, boolean allowInventoryPull) {
        int capacity = 0;
        for (Slot slot : handler.slots) {
            if (!isUsablePlayerSlot(slot, allowInventoryPull) || !slot.canInsert(stack)) continue;
            capacity += capacity(slot, stack);
        }
        return capacity;
    }

    private TransferResult finishTransfer() {
        boolean moved = transfer != null && transfer.moved > 0;
        transfer = null;
        return moved ? TransferResult.MOVED : TransferResult.NO_MATCH;
    }

    private void returnCursorToSource() {
        if (!isOpen() || mc.interactionManager == null || transfer == null) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!handler.getCursorStack().isEmpty()) click(handler, transfer.sourceSlot, 0);
    }

    private void click(ScreenHandler handler, int slot, int button) {
        mc.interactionManager.clickSlot(handler.syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    private Slot findPlayerDestination(ScreenHandler handler, ItemStack stack, boolean allowInventoryPull) {
        Slot empty = null;
        for (Slot slot : handler.slots) {
            if (!isUsablePlayerSlot(slot, allowInventoryPull) || !slot.canInsert(stack)) continue;
            if (ItemStack.areItemsAndComponentsEqual(slot.getStack(), stack) && capacity(slot, stack) > 0) return slot;
            if (empty == null && slot.getStack().isEmpty()) empty = slot;
        }
        return empty;
    }

    private Slot findContainerDestination(ScreenHandler handler, ItemStack stack) {
        Slot empty = null;
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory() || !slot.canInsert(stack)) continue;
            if (ItemStack.areItemsAndComponentsEqual(slot.getStack(), stack) && capacity(slot, stack) > 0) return slot;
            if (empty == null && slot.getStack().isEmpty()) empty = slot;
        }
        return empty;
    }

    private boolean isUsablePlayerSlot(Slot slot, boolean allowInventoryPull) {
        return slot.inventory == mc.player.getInventory()
            && PrinterInventory.isUsableBuildSlot(slot.getIndex(), allowInventoryPull);
    }

    private static int capacity(Slot slot, ItemStack stack) {
        if (slot.getStack().isEmpty()) return Math.min(slot.getMaxItemCount(stack), stack.getMaxCount());
        if (!ItemStack.areItemsAndComponentsEqual(slot.getStack(), stack)) return 0;
        return Math.max(0, Math.min(slot.getMaxItemCount(stack), stack.getMaxCount()) - slot.getStack().getCount());
    }

    static int plannedTransferAmount(int demand, int sourceCount) {
        return Math.max(0, Math.min(demand, sourceCount));
    }

    enum TransferResult {
        PROGRESSED,
        MOVED,
        NO_MATCH,
        INVENTORY_FULL,
        CONTAINER_FULL,
        CURSOR_BLOCKED,
        NOT_OPEN
    }

    private enum TransferMode {
        TAKE,
        RETURN
    }

    private enum TransferPhase {
        PICK_SOURCE,
        DISTRIBUTE,
        RETURN_REMAINDER,
        COMPLETE
    }

    private static final class Transfer {
        private final TransferMode mode;
        private final int sourceSlot;
        private int remaining;
        private int moved;
        private TransferPhase phase = TransferPhase.PICK_SOURCE;

        private Transfer(TransferMode mode, int sourceSlot, int remaining) {
            this.mode = mode;
            this.sourceSlot = sourceSlot;
            this.remaining = remaining;
        }
    }
}
