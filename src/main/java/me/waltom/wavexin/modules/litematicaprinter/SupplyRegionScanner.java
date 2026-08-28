package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SupplyRegionScanner {
    private final ClientWorld world;
    private final CuboidCursor cursor;
    private final List<BlockPos> containers = new ArrayList<>();
    private final Set<BlockPos> seen = new HashSet<>();
    private BlockPos waitingForChunk;
    private long scanned;

    SupplyRegionScanner(ClientWorld world, BlockPos pos1, BlockPos pos2, long maximumVolume) {
        this.world = world;
        cursor = new CuboidCursor(
            pos1.getX(), pos1.getY(), pos1.getZ(),
            pos2.getX(), pos2.getY(), pos2.getZ()
        );
        if (cursor.volume() > maximumVolume) {
            throw new IllegalArgumentException("Supply region volume " + cursor.volume() + " exceeds limit " + maximumVolume);
        }
    }

    ScanResult scan(int budget) {
        if (waitingForChunk != null) {
            if (!isLoaded(waitingForChunk)) return new ScanResult(false, waitingForChunk);
            inspect(waitingForChunk);
            waitingForChunk = null;
            scanned++;
        }

        for (int i = 0; i < budget && cursor.hasNext(); i++) {
            CuboidCursor.Position next = cursor.next();
            BlockPos pos = new BlockPos(next.x(), next.y(), next.z());
            if (!isLoaded(pos)) {
                waitingForChunk = pos;
                return new ScanResult(false, pos);
            }
            inspect(pos);
            scanned++;
        }
        return new ScanResult(!cursor.hasNext() && waitingForChunk == null, null);
    }

    long scanned() {
        return scanned;
    }

    long volume() {
        return cursor.volume();
    }

    List<BlockPos> containers() {
        if (cursor.hasNext() || waitingForChunk != null) throw new IllegalStateException("Supply scan is incomplete");
        return List.copyOf(containers);
    }

    private boolean isLoaded(BlockPos pos) {
        return world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private void inspect(BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof Inventory)) return;
        BlockPos logical = logicalPosition(pos);
        if (seen.add(logical)) containers.add(logical);
    }

    private BlockPos logicalPosition(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || !state.contains(Properties.CHEST_TYPE)) return pos.toImmutable();
        ChestType type = state.get(Properties.CHEST_TYPE);
        if (type == ChestType.SINGLE || !state.contains(Properties.HORIZONTAL_FACING)) return pos.toImmutable();

        Direction facing = state.get(Properties.HORIZONTAL_FACING);
        Direction offset = type == ChestType.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
        BlockPos other = pos.offset(offset);
        return compare(pos, other) <= 0 ? pos.toImmutable() : other.toImmutable();
    }

    private static int compare(BlockPos first, BlockPos second) {
        int x = Integer.compare(first.getX(), second.getX());
        if (x != 0) return x;
        int y = Integer.compare(first.getY(), second.getY());
        return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
    }

    record ScanResult(boolean done, BlockPos chunkLoadTarget) {
    }
}
