package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ProjectionScan {
    private final LitematicaProjection.Selection selection;
    private final ClientWorld actualWorld;
    private final CuboidCursor projectionCursor;
    private final Map<BlockPos, Target> targets = new LinkedHashMap<>();
    private final Map<BlockPos, Skipped> skipped = new LinkedHashMap<>();
    private final List<Target> discoveredTargets = new ArrayList<>();
    private final List<Skipped> discoveredSkipped = new ArrayList<>();
    private final List<Observation> discoveredObservations = new ArrayList<>();
    private final Set<Long> unknownChunks = new LinkedHashSet<>();
    private final Set<Long> verifiedChunks = new LinkedHashSet<>();
    private final Set<Long> newlyVerifiedChunks = new LinkedHashSet<>();
    private final MessageDigest digest;
    private CuboidCursor chunkCursor;
    private long activeChunk;
    private long scanned;
    private String fingerprint;

    ProjectionScan(LitematicaProjection.Selection selection, net.minecraft.world.World actualWorld, long maximumVolume) {
        this.selection = selection;
        this.actualWorld = (ClientWorld) actualWorld;
        projectionCursor = new CuboidCursor(
            selection.min().getX(), selection.min().getY(), selection.min().getZ(),
            selection.max().getX(), selection.max().getY(), selection.max().getZ()
        );
        long volume = projectionCursor.volume();
        if (volume > maximumVolume) {
            throw new IllegalArgumentException("Projection volume " + volume + " exceeds limit " + maximumVolume);
        }

        digest = newDigest();
        updateDigest(selection.name());
        updateDigest(selection.litematicaVersion());
        updateDigest(selection.min().toShortString());
        updateDigest(selection.max().toShortString());
        for (int chunkZ = selection.min().getZ() >> 4; chunkZ <= selection.max().getZ() >> 4; chunkZ++) {
            for (int chunkX = selection.min().getX() >> 4; chunkX <= selection.max().getX() >> 4; chunkX++) {
                unknownChunks.add(chunkKey(chunkX, chunkZ));
            }
        }
    }

    void scan(int budget) {
        int remaining = budget;
        int projectionBudget = chunkCursor == null ? remaining : Math.max(1, remaining / 2);
        while (projectionBudget-- > 0 && remaining > 0 && projectionCursor.hasNext()) {
            remaining--;
            CuboidCursor.Position next = projectionCursor.next();
            BlockPos pos = new BlockPos(next.x(), next.y(), next.z());
            BlockState state = selection.targetState(pos);
            updateDigest(pos.asLong() + "=" + String.valueOf(state));
            scanned++;
        }
        if (!projectionCursor.hasNext() && fingerprint == null) {
            fingerprint = HexFormat.of().formatHex(digest.digest());
        }

        while (remaining > 0 && chunkCursor != null && chunkCursor.hasNext()) {
            remaining--;
            CuboidCursor.Position next = chunkCursor.next();
            BlockPos pos = new BlockPos(next.x(), next.y(), next.z());
            if (!isLoaded(pos)) {
                chunkCursor = null;
                return;
            }
            classify(pos, selection.targetState(pos));
            scanned++;
        }
        if (chunkCursor != null && !chunkCursor.hasNext()) {
            unknownChunks.remove(activeChunk);
            verifiedChunks.add(activeChunk);
            newlyVerifiedChunks.add(activeChunk);
            chunkCursor = null;
        }
    }

    boolean prepareNextChunkRescan() {
        if (chunkCursor != null || unknownChunks.isEmpty()) return false;
        for (long key : unknownChunks) {
            int chunkX = chunkX(key);
            int chunkZ = chunkZ(key);
            if (!actualWorld.isChunkLoaded(chunkX, chunkZ)) continue;

            activeChunk = key;
            chunkCursor = new CuboidCursor(
                Math.max(selection.min().getX(), chunkX << 4), selection.min().getY(), Math.max(selection.min().getZ(), chunkZ << 4),
                Math.min(selection.max().getX(), (chunkX << 4) + 15), selection.max().getY(), Math.min(selection.max().getZ(), (chunkZ << 4) + 15)
            );
            return true;
        }
        return false;
    }

    void requestChunkRescan(int chunkX, int chunkZ) {
        if (chunkX < (selection.min().getX() >> 4) || chunkX > (selection.max().getX() >> 4)
            || chunkZ < (selection.min().getZ() >> 4) || chunkZ > (selection.max().getZ() >> 4)) return;
        unknownChunks.add(chunkKey(chunkX, chunkZ));
    }

    BlockPos nextUnknownChunkTarget(int y) {
        if (projectionCursor.hasNext() || unknownChunks.isEmpty()) return null;
        long key = unknownChunks.iterator().next();
        int chunkX = chunkX(key);
        int chunkZ = chunkZ(key);
        int x = Math.clamp((chunkX << 4) + 8, selection.min().getX(), selection.max().getX());
        int z = Math.clamp((chunkZ << 4) + 8, selection.min().getZ(), selection.max().getZ());
        return new BlockPos(x, y, z);
    }

    private void classify(BlockPos pos, BlockState state) {
        targets.remove(pos);
        skipped.remove(pos);
        BlockState actual = actualWorld.getBlockState(pos);
        if (state != null && (!state.isAir() || !actual.isAir())) {
            discoveredObservations.add(new Observation(pos, state, actual));
        }
        if (state == null || actual.equals(state)) return;
        if (state.isAir()) {
            Target target = new Target(pos, state);
            targets.put(pos.toImmutable(), target);
            discoveredTargets.add(target);
        } else if (!state.getFluidState().isEmpty()) {
            Skipped value = new Skipped(pos, state, SkipReason.FLUID);
            skipped.put(pos.toImmutable(), value);
            discoveredSkipped.add(value);
        } else if (state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED)) {
            Skipped value = new Skipped(pos, state, SkipReason.WATERLOGGED);
            skipped.put(pos.toImmutable(), value);
            discoveredSkipped.add(value);
        } else if (state.getBlock().asItem() == Items.AIR) {
            Skipped value = new Skipped(pos, state, SkipReason.NO_ITEM);
            skipped.put(pos.toImmutable(), value);
            discoveredSkipped.add(value);
        } else {
            Target target = new Target(pos, state);
            targets.put(pos.toImmutable(), target);
            discoveredTargets.add(target);
        }
    }

    boolean isFingerprintReady() {
        return !projectionCursor.hasNext() && fingerprint != null;
    }

    List<Target> drainDiscoveredTargets() {
        List<Target> result = List.copyOf(discoveredTargets);
        discoveredTargets.clear();
        return result;
    }

    List<Skipped> drainDiscoveredSkipped() {
        List<Skipped> result = List.copyOf(discoveredSkipped);
        discoveredSkipped.clear();
        return result;
    }

    List<Observation> drainDiscoveredObservations() {
        List<Observation> result = List.copyOf(discoveredObservations);
        discoveredObservations.clear();
        return result;
    }

    Set<Long> drainNewlyVerifiedChunks() {
        Set<Long> result = Set.copyOf(newlyVerifiedChunks);
        newlyVerifiedChunks.clear();
        return result;
    }

    boolean isDone() {
        return !projectionCursor.hasNext() && chunkCursor == null && unknownChunks.isEmpty();
    }

    long scanned() {
        return scanned;
    }

    long volume() {
        return projectionCursor.volume();
    }

    String fingerprint() {
        if (fingerprint == null) throw new IllegalStateException("Projection fingerprint is incomplete");
        return fingerprint;
    }

    Set<Long> verifiedChunks() {
        return Set.copyOf(verifiedChunks);
    }

    List<Target> targets() {
        if (!isDone()) throw new IllegalStateException("Projection scan is incomplete");
        return List.copyOf(targets.values());
    }

    List<Skipped> skipped() {
        if (!isDone()) throw new IllegalStateException("Projection scan is incomplete");
        return List.copyOf(skipped.values());
    }

    private boolean isLoaded(BlockPos pos) {
        return actualWorld.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private void updateDigest(String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    static int chunkZ(long key) {
        return (int) key;
    }

    record Target(BlockPos pos, BlockState state, boolean supportRequired) {
        Target(BlockPos pos, BlockState state) {
            this(pos, state, state != null && !state.isAir());
        }

        Target {
            pos = pos.toImmutable();
        }
    }

    record Skipped(BlockPos pos, BlockState state, SkipReason reason) {
        Skipped {
            pos = pos.toImmutable();
        }
    }

    record Observation(BlockPos pos, BlockState expected, BlockState actual) {
        Observation {
            pos = pos.toImmutable();
        }
    }

    enum SkipReason {
        FLUID,
        WATERLOGGED,
        NO_ITEM
    }
}
