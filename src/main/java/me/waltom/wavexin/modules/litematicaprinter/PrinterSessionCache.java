package me.waltom.wavexin.modules.litematicaprinter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinSettingsStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PrinterSessionCache {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path = FabricLoader.getInstance().getConfigDir()
        .resolve("wavexin").resolve("litematica-printer").resolve("session.json");
    private CacheDocument document = new CacheDocument();
    private int dirtyRecords;

    PrinterSessionCache() {
        clear();
    }

    void begin(String fingerprint) {
        if (fingerprint.equals(document.fingerprint)) return;
        document = readMatching(fingerprint);
        document.fingerprint = fingerprint;
        flush();
    }

    BlockPos lastPosition() {
        if (document.lastPosition == null) return null;
        return new BlockPos(document.lastPosition.x, document.lastPosition.y, document.lastPosition.z);
    }

    void recordCompleted(BlockPos pos, BlockState expected, BlockState actual) {
        if (document.fingerprint == null) return;
        recordObserved(pos, expected, actual);
        document.completed.put(key(pos), new CachedState(
            pos.getX(), pos.getY(), pos.getZ(), String.valueOf(expected), String.valueOf(actual), System.currentTimeMillis()
        ));
        document.lastPosition = new CachedPosition(pos.getX(), pos.getY(), pos.getZ());
        markDirty();
    }

    void recordObserved(List<ProjectionScan.Observation> observations) {
        if (document.fingerprint == null || observations.isEmpty()) return;
        for (ProjectionScan.Observation observation : observations) {
            recordObserved(observation.pos(), observation.expected(), observation.actual());
        }
    }

    void recordObserved(BlockPos pos, BlockState expected, BlockState actual) {
        if (document.fingerprint == null) return;
        document.observedTargets.put(key(pos), new CachedTarget(
            String.valueOf(expected),
            String.valueOf(actual),
            PrinterState.confirmed(expected, actual),
            System.currentTimeMillis()
        ));
        markDirty();
    }

    TargetKnowledge targetKnowledge(BlockPos pos, BlockState expected) {
        CachedTarget target = document.observedTargets.get(key(pos));
        if (target == null || !String.valueOf(expected).equals(target.expectedState)) return TargetKnowledge.UNKNOWN;
        return target.compatible ? TargetKnowledge.MATCHES : TargetKnowledge.MISMATCHES;
    }

    void recordVerifiedChunks(Set<Long> chunks) {
        if (document.fingerprint == null || chunks.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (long chunk : chunks) {
            document.verifiedChunks.put(ProjectionScan.chunkX(chunk) + "," + ProjectionScan.chunkZ(chunk), now);
        }
        markDirty();
    }

    void recordSupplyContainers(String regionFingerprint, List<BlockPos> containers) {
        recordSupplyContainers(regionFingerprint, containers, true);
    }

    void recordSupplyContainers(String regionFingerprint, List<BlockPos> containers, boolean scanComplete) {
        RestockCache previous = document.restock;
        if (!scanComplete && previous != null && regionFingerprint.equals(previous.regionFingerprint)) {
            for (BlockPos pos : containers) {
                previous.containers.putIfAbsent(key(pos), new CachedContainer(pos.getX(), pos.getY(), pos.getZ()));
            }
            markDirty();
            return;
        }
        RestockCache next = new RestockCache();
        next.regionFingerprint = regionFingerprint;
        next.scanComplete = scanComplete;
        for (BlockPos pos : containers) {
            String key = key(pos);
            CachedContainer cached = previous != null && regionFingerprint.equals(previous.regionFingerprint)
                ? previous.containers.get(key)
                : null;
            next.containers.put(key, cached == null ? new CachedContainer(pos.getX(), pos.getY(), pos.getZ()) : cached);
        }
        document.restock = next;
        markDirty();
    }

    int containerPriority(String regionFingerprint, BlockPos pos, List<Item> required) {
        RestockCache restock = document.restock;
        if (restock == null || !regionFingerprint.equals(restock.regionFingerprint)) return required.size();
        CachedContainer container = restock.containers.get(key(pos));
        if (container == null || !container.observed) return required.size();
        for (int i = 0; i < required.size(); i++) {
            if (container.items.getOrDefault(Registries.ITEM.getId(required.get(i)).toString(), 0) > 0) return i;
        }
        return required.size() + 1;
    }

    void recordContainer(String regionFingerprint, BlockPos pos, Map<Item, Integer> contents) {
        if (document.restock == null || !regionFingerprint.equals(document.restock.regionFingerprint)) {
            document.restock = new RestockCache();
            document.restock.regionFingerprint = regionFingerprint;
        }
        CachedContainer container = new CachedContainer(pos.getX(), pos.getY(), pos.getZ());
        container.observed = true;
        container.observedAt = System.currentTimeMillis();
        for (Map.Entry<Item, Integer> entry : contents.entrySet()) {
            if (entry.getValue() > 0) container.items.put(Registries.ITEM.getId(entry.getKey()).toString(), entry.getValue());
        }
        document.restock.containers.put(key(pos), container);
        markDirty();
    }

    SupplyKnowledge supplyKnowledge(String regionFingerprint, Map<Item, Integer> required) {
        RestockCache restock = document.restock;
        if (restock == null || !regionFingerprint.equals(restock.regionFingerprint)) return SupplyKnowledge.UNKNOWN;

        Map<String, Integer> available = new LinkedHashMap<>();
        boolean complete = restock.scanComplete;
        for (CachedContainer container : restock.containers.values()) {
            if (!container.observed) complete = false;
            for (Map.Entry<String, Integer> entry : container.items.entrySet()) {
                available.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }

        Map<String, Integer> requiredById = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            requiredById.put(Registries.ITEM.getId(entry.getKey()).toString(), entry.getValue());
        }
        return evaluateSupplyKnowledge(available, requiredById, complete);
    }

    static SupplyKnowledge evaluateSupplyKnowledge(
        Map<String, Integer> available,
        Map<String, Integer> required,
        boolean complete
    ) {
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            if (available.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return complete ? SupplyKnowledge.INSUFFICIENT : SupplyKnowledge.UNKNOWN;
            }
        }
        return SupplyKnowledge.SUFFICIENT;
    }

    void flush() {
        if (document.fingerprint == null) return;
        try {
            WaveXinSettingsStore.writeAtomically(path, GSON.toJson(document));
            dirtyRecords = 0;
        } catch (IOException e) {
            WaveXinAddon.LOG.warn("Could not save Litematica Printer session cache.", e);
        }
    }

    void clear() {
        document = new CacheDocument();
        dirtyRecords = 0;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            WaveXinAddon.LOG.warn("Could not clear Litematica Printer session cache.", e);
        }
    }

    void clearSupply() {
        document.restock = new RestockCache();
        markDirty();
        flush();
    }

    private CacheDocument readMatching(String fingerprint) {
        if (!Files.isRegularFile(path)) return new CacheDocument();
        try {
            CacheDocument cached = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), CacheDocument.class);
            if (cached != null && fingerprint.equals(cached.fingerprint)) return cached.normalize();
        } catch (IOException | RuntimeException e) {
            WaveXinAddon.LOG.warn("Could not read Litematica Printer session cache.", e);
        }
        return new CacheDocument();
    }

    private void markDirty() {
        dirtyRecords++;
    }

    private static String key(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static final class CacheDocument {
        int version = 2;
        String fingerprint;
        CachedPosition lastPosition;
        Map<String, CachedState> completed = new LinkedHashMap<>();
        Map<String, CachedTarget> observedTargets = new LinkedHashMap<>();
        Map<String, Long> verifiedChunks = new LinkedHashMap<>();
        RestockCache restock = new RestockCache();

        CacheDocument normalize() {
            if (completed == null) completed = new LinkedHashMap<>();
            if (observedTargets == null) observedTargets = new LinkedHashMap<>();
            if (verifiedChunks == null) verifiedChunks = new LinkedHashMap<>();
            if (restock == null) restock = new RestockCache();
            restock.normalize();
            return this;
        }
    }

    enum TargetKnowledge {
        MATCHES,
        MISMATCHES,
        UNKNOWN
    }

    enum SupplyKnowledge {
        SUFFICIENT,
        INSUFFICIENT,
        UNKNOWN
    }

    private static final class RestockCache {
        String regionFingerprint;
        boolean scanComplete;
        Map<String, CachedContainer> containers = new LinkedHashMap<>();

        void normalize() {
            if (containers == null) containers = new LinkedHashMap<>();
            for (CachedContainer container : containers.values()) container.normalize();
        }
    }

    private static final class CachedContainer {
        int x;
        int y;
        int z;
        boolean observed;
        long observedAt;
        Map<String, Integer> items = new LinkedHashMap<>();

        CachedContainer() {
        }

        CachedContainer(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void normalize() {
            if (items == null) items = new LinkedHashMap<>();
        }
    }

    private record CachedPosition(int x, int y, int z) {
    }

    private record CachedState(int x, int y, int z, String expectedState, String actualState, long verifiedAt) {
    }

    private record CachedTarget(String expectedState, String actualState, boolean compatible, long verifiedAt) {
    }
}
