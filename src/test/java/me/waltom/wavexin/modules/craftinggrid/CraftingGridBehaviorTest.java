package me.waltom.wavexin.modules.craftinggrid;

import java.util.Map;

public final class CraftingGridBehaviorTest {
    public static void main(String[] args) {
        var kit = CraftingKit.parse("# example\n500:minecraft:stone\n501:minecraft:dirt\n104:1\n0:minecraft:diamond\n499:minecraft:air\n501:minecraft:obsidian\ninvalid\n");
        check(kit.equals(Map.of(1, "minecraft:stone", 2, "minecraft:obsidian", 4, "1")), "kit aliases, duplicates and inventory slots");
        for (int slot = 1; slot <= 4; slot++) {
            check(CraftingKit.gridSlot(499 + slot) == slot && CraftingKit.gridSlot(100 + slot) == slot, "slot mapping");
        }
        check(!CraftingKit.validName("../settings") && !CraftingKit.validName("C:\\outside") && CraftingKit.validName("default"), "kit path confinement");
        check(CraftingKit.canRefill(true, true, false, 25, 25), "ready refill");
        check(!CraftingKit.canRefill(false, true, false, 25, 25), "container open");
        check(!CraftingKit.canRefill(true, false, false, 25, 25), "occupied cursor");
        check(!CraftingKit.canRefill(true, true, true, 25, 25), "nearby player");
        check(!CraftingKit.canRefill(true, true, false, 24, 25), "delay");
        check(CraftingKit.donorSufficient(60, 64, 4), "exact donor");
        check(!CraftingKit.donorSufficient(60, 64, 3) && !CraftingKit.donorSufficient(64, 64, 64), "insufficient donor or full slot");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
