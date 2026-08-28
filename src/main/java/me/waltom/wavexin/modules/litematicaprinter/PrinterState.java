package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.state.property.Property;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

import java.util.Set;

final class PrinterState {
    private static final Set<String> DYNAMIC_PROPERTIES = Set.of(
        "north", "east", "south", "west", "up", "down", "shape", "open", "powered"
    );

    private PrinterState() {
    }

    static boolean exact(BlockState expected, BlockState actual) {
        return expected.equals(actual);
    }

    static boolean compatibleDuringBuild(BlockState expected, BlockState actual) {
        if (expected.equals(actual)) return true;
        if (expected.getBlock() != actual.getBlock()) return false;
        if (expected.getBlock() instanceof ChestBlock && provisionalChest(expected, actual)) return true;

        for (Property<?> property : expected.getProperties()) {
            if (!actual.contains(property) || sameValue(expected, actual, property)) continue;
            if (!DYNAMIC_PROPERTIES.contains(property.getName())) return false;
        }
        return true;
    }

    private static boolean provisionalChest(BlockState expected, BlockState actual) {
        if (!expected.contains(Properties.CHEST_TYPE)
            || !actual.contains(Properties.CHEST_TYPE)
            || !expected.contains(Properties.HORIZONTAL_FACING)
            || !actual.contains(Properties.HORIZONTAL_FACING)) {
            return false;
        }
        return provisionalChest(
            expected.get(Properties.CHEST_TYPE),
            actual.get(Properties.CHEST_TYPE),
            expected.get(Properties.HORIZONTAL_FACING),
            actual.get(Properties.HORIZONTAL_FACING)
        );
    }

    static boolean provisionalChest(
        ChestType expectedType,
        ChestType actualType,
        Direction expectedFacing,
        Direction actualFacing
    ) {
        return expectedType != ChestType.SINGLE
            && actualType == ChestType.SINGLE
            && expectedFacing == actualFacing;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean sameValue(BlockState expected, BlockState actual, Property property) {
        return expected.get(property).equals(actual.get(property));
    }
}
