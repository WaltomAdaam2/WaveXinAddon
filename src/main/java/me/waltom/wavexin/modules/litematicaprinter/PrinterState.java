package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;

import java.util.Set;

final class PrinterState {
    private static final Set<String> DYNAMIC_PROPERTIES = Set.of(
        "north", "east", "south", "west", "up", "down", "shape", "chest_type", "open", "powered"
    );

    private PrinterState() {
    }

    static boolean exact(BlockState expected, BlockState actual) {
        return expected.equals(actual);
    }

    static boolean compatibleDuringBuild(BlockState expected, BlockState actual) {
        if (expected.equals(actual)) return true;
        if (expected.getBlock() != actual.getBlock()) return false;

        for (Property<?> property : expected.getProperties()) {
            if (!actual.contains(property) || sameValue(expected, actual, property)) continue;
            if (!DYNAMIC_PROPERTIES.contains(property.getName())) return false;
        }
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean sameValue(BlockState expected, BlockState actual, Property property) {
        return expected.get(property).equals(actual.get(property));
    }
}
