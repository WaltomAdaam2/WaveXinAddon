package me.waltom.wavexin.modules.litematicaprinter;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoReplenish;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PrinterInventory {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private AutoReplenish autoReplenish;
    private boolean restoreAutoReplenish;
    private Lease persistentLease;
    private List<Item> workSet = List.of();
    private final List<HotbarMove> hotbarMoves = new ArrayList<>();

    void begin() {
        autoReplenish = Modules.get().get(AutoReplenish.class);
        if (autoReplenish != null && autoReplenish.isActive()) {
            autoReplenish.toggle();
            restoreAutoReplenish = true;
        }
    }

    void enforcePriority() {
        if (autoReplenish != null && autoReplenish.isActive()) autoReplenish.toggle();
    }

    int count(Item item, boolean allowInventoryPull) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < usableSlotLimit(allowInventoryPull); slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    int stackCount(Item item, boolean allowInventoryPull) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < usableSlotLimit(allowInventoryPull); slot++) {
            if (mc.player.getInventory().getStack(slot).isOf(item)) count++;
        }
        return count;
    }

    int emptySlots(boolean allowInventoryPull) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < usableSlotLimit(allowInventoryPull); slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) count++;
        }
        return count;
    }

    int occupiedSlotsOutside(Set<Item> required, boolean allowInventoryPull) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < usableSlotLimit(allowInventoryPull); slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && !required.contains(stack.getItem())) count++;
        }
        return count;
    }

    Lease acquire(Item item, boolean allowInventoryPull) {
        int slot = findSlot(item, allowInventoryPull);
        if (slot >= 9) slot = moveToHotbar(slot, item);
        return slot >= 0 ? acquire(slot) : null;
    }

    int findSlot(Item item, boolean allowInventoryPull) {
        if (mc.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isOf(item)) return slot;
        }
        if (!allowInventoryPull || findHotbarDestination() < 0) return -1;
        for (int slot = 9; slot < 36; slot++) {
            if (mc.player.getInventory().getStack(slot).isOf(item)) return slot;
        }
        return -1;
    }

    int availableForBuild(Item item, boolean allowInventoryPull) {
        if (mc.player == null) return 0;
        int hotbar = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isOf(item)) hotbar += stack.getCount();
        }
        if (!allowInventoryPull) return hotbar;
        if (hotbar == 0 && findHotbarDestination() < 0) return 0;
        return count(item, true);
    }

    void setWorkSet(List<Item> orderedItems) {
        Set<Item> unique = new LinkedHashSet<>(orderedItems);
        workSet = List.copyOf(unique);
    }

    List<HotbarMove> drainHotbarMoves() {
        List<HotbarMove> result = List.copyOf(hotbarMoves);
        hotbarMoves.clear();
        return result;
    }

    private int moveToHotbar(int sourceSlot, Item item) {
        int destination = findHotbarDestination();
        if (destination < 0) return -1;
        Item replaced = mc.player.getInventory().getStack(destination).getItem();
        InvUtils.quickSwap().fromId(destination).to(sourceSlot);
        if (!mc.player.getInventory().getStack(destination).isOf(item)) return -1;
        hotbarMoves.add(new HotbarMove(sourceSlot, destination, item, replaced));
        return destination;
    }

    private int findHotbarDestination() {
        if (mc.player == null) return -1;
        List<Item> hotbar = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            hotbar.add(stack.isEmpty() ? null : stack.getItem());
        }
        return chooseHotbarDestination(hotbar, Set.copyOf(workSet));
    }

    static <T> int chooseHotbarDestination(List<T> hotbar, Set<T> workSet) {
        for (int slot = 0; slot < hotbar.size(); slot++) {
            if (hotbar.get(slot) == null) return slot;
        }
        for (int slot = 0; slot < hotbar.size(); slot++) {
            if (!workSet.contains(hotbar.get(slot))) return slot;
        }
        return -1;
    }

    static boolean isUsableBuildSlot(int slot, boolean allowInventoryPull) {
        return slot >= 0 && slot < usableSlotLimit(allowInventoryPull);
    }

    private static int usableSlotLimit(boolean allowInventoryPull) {
        return allowInventoryPull ? 36 : 9;
    }

    Lease acquireFastestTool(BlockState state) {
        if (mc.player == null) return null;
        int bestSlot = -1;
        float bestSpeed = 1.0F;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSlot < 0 ? null : acquire(bestSlot);
    }

    boolean acquirePersistentTool(BlockState state) {
        releasePersistent();
        persistentLease = acquireFastestTool(state);
        return persistentLease != null;
    }

    void releasePersistent() {
        if (persistentLease == null) return;
        persistentLease.close();
        persistentLease = null;
    }

    void close() {
        releasePersistent();
        if (restoreAutoReplenish && autoReplenish != null && !autoReplenish.isActive()) autoReplenish.toggle();
        restoreAutoReplenish = false;
        autoReplenish = null;
        workSet = List.of();
        hotbarMoves.clear();
    }

    private Lease acquire(int itemSlot) {
        if (mc.player == null || itemSlot < 0 || itemSlot >= mc.player.getInventory().size()) return null;
        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        if (itemSlot == selectedSlot) return new Lease(Mode.NONE, selectedSlot, itemSlot);

        if (itemSlot < 9) {
            if (!InvUtils.swap(itemSlot, false)) return null;
            return new Lease(Mode.HOTBAR, selectedSlot, itemSlot);
        }

        InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
        return new Lease(Mode.QUICK_SWAP, selectedSlot, itemSlot);
    }

    final class Lease implements AutoCloseable {
        private final Mode mode;
        private final int selectedSlot;
        private final int itemSlot;
        private boolean closed;

        private Lease(Mode mode, int selectedSlot, int itemSlot) {
            this.mode = mode;
            this.selectedSlot = selectedSlot;
            this.itemSlot = itemSlot;
        }

        @Override
        public void close() {
            if (closed || mc.player == null) return;
            closed = true;
            if (mode == Mode.HOTBAR) InvUtils.swap(selectedSlot, false);
            else if (mode == Mode.QUICK_SWAP) InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
        }
    }

    private enum Mode {
        NONE,
        HOTBAR,
        QUICK_SWAP
    }

    record HotbarMove(int sourceSlot, int hotbarSlot, Item moved, Item replaced) {
    }
}
