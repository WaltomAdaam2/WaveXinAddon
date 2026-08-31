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

    static boolean confirmed(BlockState expected, BlockState actual) {
        if (!isDoubleChest(expected)) return compatibleDuringBuild(expected, actual);
        return actual != null
            && expected.getBlock() == actual.getBlock()
            && actual.contains(Properties.CHEST_TYPE)
            && actual.contains(Properties.HORIZONTAL_FACING)
            && confirmedChestProperties(
                expected.get(Properties.CHEST_TYPE),
                actual.get(Properties.CHEST_TYPE),
                expected.get(Properties.HORIZONTAL_FACING),
                actual.get(Properties.HORIZONTAL_FACING)
            )
            && expected.equals(actual);
    }

    static boolean isDoubleChest(BlockState state) {
        return state != null
            && state.getBlock() instanceof ChestBlock
            && state.contains(Properties.CHEST_TYPE)
            && state.get(Properties.CHEST_TYPE) != ChestType.SINGLE;
    }

    static boolean requiresRealSupport(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            String name = property.getName();
            if (name.equals("facing")
                || name.equals("axis")
                || name.equals("half")
                || name.equals("shape")
                || name.equals("type")
                || name.equals("face")
                || name.equals("hinge")
                || name.equals("rotation")
                || name.equals("attachment")
                || name.equals("vertical_direction")) return true;
        }
        return false;
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

    static boolean confirmedChestProperties(
        ChestType expectedType,
        ChestType actualType,
        Direction expectedFacing,
        Direction actualFacing
    ) {
        return expectedType != ChestType.SINGLE
            && expectedType == actualType
            && expectedFacing == actualFacing;
    }

    static Direction chestConnection(Direction facing, ChestType type) {
        return type == ChestType.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
    }

    static Direction chestConnection(BlockState state) {
        return chestConnection(state.get(Properties.HORIZONTAL_FACING), state.get(Properties.CHEST_TYPE));
    }

    static boolean chestPair(
        ChestType firstType,
        ChestType secondType,
        Direction facing,
        Direction connection
    ) {
        return firstType != ChestType.SINGLE
            && secondType != ChestType.SINGLE
            && firstType != secondType
            && chestConnection(facing, firstType) == connection
            && chestConnection(facing, secondType) == connection.getOpposite();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean sameValue(BlockState expected, BlockState actual, Property property) {
        return expected.get(property).equals(actual.get(property));
    }
}
