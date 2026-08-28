package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.block.enums.ChestType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        verifySupplyVolume();
        verifyLayeredRowOrder();
        verifyUnsupportedTargetsAreDeferred();
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

    private static void verifySupplyVolume() {
        check(LitematicaPrinter.supplyVolume(new BlockPos(1, 2, 3), new BlockPos(1, 2, 3)) == 1,
            "a one-block supply region must be rejected");
        check(LitematicaPrinter.supplyVolume(new BlockPos(1, 2, 3), new BlockPos(1, 2, 4)) == 2,
            "1x1x2 must be accepted");
        check(LitematicaPrinter.supplyVolume(new BlockPos(1, 2, 3), new BlockPos(2, 2, 3)) == 2,
            "2x1x1 must be accepted");
        check(LitematicaPrinter.supplyVolume(new BlockPos(1, 2, 3), new BlockPos(1, 3, 3)) == 2,
            "1x2x1 must be accepted");
    }

    private static void verifyLayeredRowOrder() {
        List<ProjectionScan.Target> targets = new ArrayList<>();
        Set<BlockPos> supports = new HashSet<>();
        for (int z = 0; z <= 1; z++) {
            for (int x = 0; x <= 2; x++) {
                targets.add(target(x, 1, z));
                supports.add(new BlockPos(x, 0, z));
            }
        }
        targets.add(target(0, 2, 0));

        List<BlockPos> ordered = PrinterBuildOrder.order(
            targets,
            new BlockPos(0, 1, 0),
            supports::contains,
            true,
            1,
            PrinterBuildOrder.LayerOrder.BottomToTop,
            PrinterBuildOrder.RowAxis.Automatic
        ).stream().map(ProjectionScan.Target::pos).toList();

        check(ordered.equals(List.of(
            new BlockPos(0, 1, 0), new BlockPos(1, 1, 0), new BlockPos(2, 1, 0),
            new BlockPos(2, 1, 1), new BlockPos(1, 1, 1), new BlockPos(0, 1, 1),
            new BlockPos(0, 2, 0)
        )), "automatic X rows must build bottom-up and enter the next row from the nearest endpoint");
    }

    private static void verifyUnsupportedTargetsAreDeferred() {
        ProjectionScan.Target supported = target(0, 1, 0);
        ProjectionScan.Target chained = target(1, 1, 0);
        ProjectionScan.Target floating = target(10, 10, 10);
        List<ProjectionScan.Target> ordered = PrinterBuildOrder.order(
            List.of(floating, chained, supported),
            BlockPos.ORIGIN,
            Set.of(new BlockPos(0, 0, 0))::contains,
            false,
            1,
            PrinterBuildOrder.LayerOrder.BottomToTop,
            PrinterBuildOrder.RowAxis.X
        );
        check(ordered.equals(List.of(supported, chained, floating)),
            "supported and newly adjacent targets must precede floating targets with stable ordering");
    }

    private static ProjectionScan.Target target(int x, int y, int z) {
        return new ProjectionScan.Target(new BlockPos(x, y, z), null, true);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
