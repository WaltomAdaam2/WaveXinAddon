package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.block.enums.ChestType;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

public final class CuboidCursorBehaviorTest {
    private CuboidCursorBehaviorTest() {
    }

    public static void main(String[] args) {
        CuboidCursor cursor = new CuboidCursor(2, 5, 1, 1, 5, 0);
        List<CuboidCursor.Position> positions = new ArrayList<>();
        while (cursor.hasNext()) positions.add(cursor.next());

        check(cursor.volume() == 4, "reversed coordinates must form a four-block cuboid");
        check(positions.equals(List.of(
            new CuboidCursor.Position(1, 5, 0),
            new CuboidCursor.Position(2, 5, 0),
            new CuboidCursor.Position(1, 5, 1),
            new CuboidCursor.Position(2, 5, 1)
        )), "cursor order must be stable and inclusive");

        cursor.reset();
        check(cursor.hasNext(), "reset must make the cursor reusable");
        check(cursor.next().equals(new CuboidCursor.Position(1, 5, 0)), "reset must return to the minimum corner");

        verifyChestProvisionalState();
    }

    private static void verifyChestProvisionalState() {
        check(PrinterState.provisionalChest(ChestType.LEFT, ChestType.SINGLE, Direction.NORTH, Direction.NORTH),
            "first double-chest half may be provisional");
        check(PrinterState.provisionalChest(ChestType.RIGHT, ChestType.SINGLE, Direction.NORTH, Direction.NORTH),
            "either double-chest half may be provisional");
        check(!PrinterState.provisionalChest(ChestType.SINGLE, ChestType.LEFT, Direction.NORTH, Direction.NORTH),
            "an unwanted chest merge must not be provisional");
        check(!PrinterState.provisionalChest(ChestType.LEFT, ChestType.SINGLE, Direction.NORTH, Direction.SOUTH),
            "provisional chest facing must match");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
