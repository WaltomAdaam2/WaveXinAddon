package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

final class ProjectionRequirementScan {
    private final LitematicaProjection.Selection selection;
    private final LitematicaProjection.LayerFilter filter;
    private final ClientWorld world;
    private final PrinterSessionCache cache;
    private final CuboidCursor cursor;
    private final Map<Item, Integer> required = new LinkedHashMap<>();
    private final Map<Item, Double> nearestDistanceSquared = new LinkedHashMap<>();
    private final Map<Item, Integer> firstOrder = new LinkedHashMap<>();
    private final BlockPos origin;
    private long remaining;
    private long unknown;

    ProjectionRequirementScan(
        LitematicaProjection.Selection selection,
        LitematicaProjection.LayerFilter filter,
        ClientWorld world,
        PrinterSessionCache cache,
        BlockPos origin
    ) {
        this.selection = selection;
        this.filter = filter;
        this.world = world;
        this.cache = cache;
        this.origin = origin.toImmutable();
        cursor = new CuboidCursor(
            selection.min().getX(), selection.min().getY(), selection.min().getZ(),
            selection.max().getX(), selection.max().getY(), selection.max().getZ()
        );
    }

    void scan(int budget) {
        for (int i = 0; i < budget && cursor.hasNext(); i++) {
            CuboidCursor.Position next = cursor.next();
            BlockPos pos = new BlockPos(next.x(), next.y(), next.z());
            if (!filter.includes(pos)) continue;

            BlockState expected = selection.targetState(pos);
            if (!isSupportedTarget(expected)) continue;
            if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                BlockState actual = world.getBlockState(pos);
                cache.recordObserved(pos, expected, actual);
                if (!PrinterState.confirmed(expected, actual)) addRemaining(expected, pos);
                continue;
            }

            switch (cache.targetKnowledge(pos, expected)) {
                case MATCHES -> {
                }
                case MISMATCHES -> addRemaining(expected, pos);
                case UNKNOWN -> unknown++;
            }
        }
    }

    private void addRemaining(BlockState expected, BlockPos pos) {
        remaining++;
        if (expected == null || expected.isAir()) return;
        Item item = expected.getBlock().asItem();
        if (item instanceof BlockItem) {
            required.merge(item, 1, Integer::sum);
            nearestDistanceSquared.merge(item, origin.getSquaredDistance(pos), Math::min);
            firstOrder.putIfAbsent(item, firstOrder.size());
        }
    }

    private static boolean isSupportedTarget(BlockState expected) {
        return expected != null
            && !expected.isAir()
            && expected.getFluidState().isEmpty()
            && (!expected.contains(Properties.WATERLOGGED) || !expected.get(Properties.WATERLOGGED))
            && expected.getBlock().asItem() != Items.AIR;
    }

    boolean isDone() {
        return !cursor.hasNext();
    }

    Result result() {
        if (!isDone()) throw new IllegalStateException("Projection requirement scan is incomplete");
        return new Result(remaining, unknown, required, nearestDistanceSquared, firstOrder);
    }

    record Result(
        long remaining,
        long unknown,
        Map<Item, Integer> required,
        Map<Item, Double> nearestDistanceSquared,
        Map<Item, Integer> firstOrder
    ) {
        Result {
            required = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(required));
            nearestDistanceSquared = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nearestDistanceSquared));
            firstOrder = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(firstOrder));
        }
    }
}
