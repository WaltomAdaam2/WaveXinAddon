package me.waltom.wavexin.modules.litematicaprinter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinSettingsStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class PrinterSessionCache {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir()
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
        document.completed.put(key(pos), new CachedState(
            pos.getX(), pos.getY(), pos.getZ(), String.valueOf(expected), String.valueOf(actual), System.currentTimeMillis()
        ));
        document.lastPosition = new CachedPosition(pos.getX(), pos.getY(), pos.getZ());
        markDirty();
    }

    void recordVerifiedChunks(Set<Long> chunks) {
        if (document.fingerprint == null || chunks.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (long chunk : chunks) {
            document.verifiedChunks.put(ProjectionScan.chunkX(chunk) + "," + ProjectionScan.chunkZ(chunk), now);
        }
        markDirty();
    }

    void flush() {
        if (document.fingerprint == null) return;
        try {
            WaveXinSettingsStore.writeAtomically(PATH, GSON.toJson(document));
            dirtyRecords = 0;
        } catch (IOException e) {
            WaveXinAddon.LOG.warn("Could not save Litematica Printer session cache.", e);
        }
    }

    void clear() {
        document = new CacheDocument();
        dirtyRecords = 0;
        try {
            Files.deleteIfExists(PATH);
        } catch (IOException e) {
            WaveXinAddon.LOG.warn("Could not clear Litematica Printer session cache.", e);
        }
    }

    private CacheDocument readMatching(String fingerprint) {
        if (!Files.isRegularFile(PATH)) return new CacheDocument();
        try {
            CacheDocument cached = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), CacheDocument.class);
            if (cached != null && fingerprint.equals(cached.fingerprint)) return cached.normalize();
        } catch (IOException | RuntimeException e) {
            WaveXinAddon.LOG.warn("Could not read Litematica Printer session cache.", e);
        }
        return new CacheDocument();
    }

    private void markDirty() {
        if (++dirtyRecords >= 32) flush();
    }

    private static String key(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static final class CacheDocument {
        int version = 1;
        String fingerprint;
        CachedPosition lastPosition;
        Map<String, CachedState> completed = new LinkedHashMap<>();
        Map<String, Long> verifiedChunks = new LinkedHashMap<>();

        CacheDocument normalize() {
            if (completed == null) completed = new LinkedHashMap<>();
            if (verifiedChunks == null) verifiedChunks = new LinkedHashMap<>();
            return this;
        }
    }

    private record CachedPosition(int x, int y, int z) {
    }

    private record CachedState(int x, int y, int z, String expectedState, String actualState, long verifiedAt) {
    }
}
