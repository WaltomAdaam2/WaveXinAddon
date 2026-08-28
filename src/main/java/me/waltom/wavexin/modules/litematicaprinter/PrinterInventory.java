package me.waltom.wavexin.modules.litematicaprinter;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoReplenish;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

final class PrinterInventory {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private AutoReplenish autoReplenish;
    private boolean restoreAutoReplenish;
    private Lease persistentLease;

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

    int count(Item item) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    Lease acquire(Item item) {
        int slot = findSlot(item);
        return slot >= 0 ? acquire(slot) : null;
    }

    int findSlot(Item item) {
        if (mc.player == null) return -1;
        int bestSlot = -1;
        int bestCount = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isOf(item) && stack.getCount() > bestCount) {
                bestSlot = slot;
                bestCount = stack.getCount();
            }
        }
        return bestSlot;
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
}
