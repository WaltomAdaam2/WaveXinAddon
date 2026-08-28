package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.block.BlockState;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

final class ProjectionScan {
    private final LitematicaProjection.Selection selection;
    private final World actualWorld;
    private final CuboidCursor cursor;
    private final List<Target> targets = new ArrayList<>();
    private final List<Skipped> skipped = new ArrayList<>();
    private long scanned;

    ProjectionScan(LitematicaProjection.Selection selection, World actualWorld, long maximumVolume) {
        this.selection = selection;
        this.actualWorld = actualWorld;
        cursor = new CuboidCursor(
            selection.min().getX(), selection.min().getY(), selection.min().getZ(),
            selection.max().getX(), selection.max().getY(), selection.max().getZ()
        );
        long volume = cursor.volume();
        if (volume > maximumVolume) {
            throw new IllegalArgumentException("Projection volume " + volume + " exceeds limit " + maximumVolume);
        }
    }

    void scan(int budget) {
        for (int i = 0; i < budget && cursor.hasNext(); i++) {
            CuboidCursor.Position next = cursor.next();
            BlockPos pos = new BlockPos(next.x(), next.y(), next.z());
            BlockState state = selection.targetState(pos);
            scanned++;

            if (state == null || actualWorld.getBlockState(pos).equals(state)) continue;
            if (state.isAir()) {
                targets.add(new Target(pos, state));
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                skipped.add(new Skipped(pos, state, SkipReason.FLUID));
            } else if (state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED)) {
                skipped.add(new Skipped(pos, state, SkipReason.WATERLOGGED));
            } else if (state.getBlock().asItem() == Items.AIR) {
                skipped.add(new Skipped(pos, state, SkipReason.NO_ITEM));
            } else {
                targets.add(new Target(pos, state));
            }
        }
    }

    boolean isDone() {
        return !cursor.hasNext();
    }

    long scanned() {
        return scanned;
    }

    long volume() {
        return cursor.volume();
    }

    List<Target> targets() {
        if (!isDone()) throw new IllegalStateException("Projection scan is incomplete");
        return List.copyOf(targets);
    }

    List<Skipped> skipped() {
        if (!isDone()) throw new IllegalStateException("Projection scan is incomplete");
        return List.copyOf(skipped);
    }

    record Target(BlockPos pos, BlockState state) {
        Target {
            pos = pos.toImmutable();
        }
    }

    record Skipped(BlockPos pos, BlockState state, SkipReason reason) {
        Skipped {
            pos = pos.toImmutable();
        }
    }

    enum SkipReason {
        FLUID,
        WATERLOGGED,
        NO_ITEM
    }
}
