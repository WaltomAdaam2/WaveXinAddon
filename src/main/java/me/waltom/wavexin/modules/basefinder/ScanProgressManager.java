package me.waltom.wavexin.modules.basefinder;

import me.waltom.wavexin.core.WaveXinSettingsStore;
import me.waltom.wavexin.core.WaveXinDataPaths;
import me.waltom.wavexin.WaveXinAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.math.ChunkPos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public class ScanProgressManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String LEGACY_PROGRESS_FILE = "mapscan_progress.dat";

    public static class ScanProgress {
        public int startX;
        public int startZ;
        public int totalSegments;
        public int currentDir;
        public int stepsInCurrentLength;
        public int currentStepLength;
        public int chunkStep;

        public ScanProgress() {
        }

        public ScanProgress(int startX, int startZ, int totalSegments, int currentDir, int stepsInCurrentLength, int currentStepLength) {
            this(startX, startZ, totalSegments, currentDir, stepsInCurrentLength, currentStepLength, 6);
        }

        public ScanProgress(int startX, int startZ, int totalSegments, int currentDir, int stepsInCurrentLength, int currentStepLength, int chunkStep) {
            this.startX = startX;
            this.startZ = startZ;
            this.totalSegments = totalSegments;
            this.currentDir = currentDir;
            this.stepsInCurrentLength = stepsInCurrentLength;
            this.currentStepLength = currentStepLength;
            this.chunkStep = chunkStep;
        }

        static ScanProgress fromNbt(NbtCompound nbt) {
            return new ScanProgress(
                nbt.getInt("StartX").orElse(0),
                nbt.getInt("StartZ").orElse(0),
                nbt.getInt("TotalSegments").orElse(0),
                nbt.getInt("CurrentDir").orElse(0),
                nbt.getInt("StepsInCurrentLength").orElse(0),
                nbt.getInt("CurrentStepLength").orElse(1),
                nbt.contains("ChunkStep") ? nbt.getInt("ChunkStep").orElse(6) : 6
            );
        }
    }

    public static class NormalScanProgress {
        public int originX;
        public int originZ;
        public int playerX;
        public int playerZ;
        public int ring;
        public String route;
        public String server;
        public String dimension;

        public NormalScanProgress() {
        }

        public NormalScanProgress(int originX, int originZ, int playerX, int playerZ, int ring, String route) {
            this(originX, originZ, playerX, playerZ, ring, route, null, null);
        }

        public NormalScanProgress(int originX, int originZ, int playerX, int playerZ, int ring, String route, String server, String dimension) {
            this.originX = originX;
            this.originZ = originZ;
            this.playerX = playerX;
            this.playerZ = playerZ;
            this.ring = ring;
            this.route = route;
            this.server = server;
            this.dimension = dimension;
        }
    }

    public static void saveProgress(ScanProgress progress) {
        ProgressDocument document = readDocument();
        document.spiral = progress;
        writeDocument(document);
    }

    public static ScanProgress loadProgress() {
        ProgressDocument document = readDocument();
        if (document.spiral != null) return document.spiral;

        ScanProgress legacyProgress = loadLegacySpiralProgress();
        if (legacyProgress == null) return null;

        document.spiral = legacyProgress;
        writeDocument(document);
        WaveXinAddon.LOG.info("Migrated legacy Spiral Scan progress to {}.", WaveXinDataPaths.SCAN_PROGRESS_PATH);
        return legacyProgress;
    }

    public static void saveNormalProgress(NormalScanProgress progress) {
        ProgressDocument document = readDocument();
        document.normal = progress;
        writeDocument(document);
    }

    public static NormalScanProgress loadNormalProgress() {
        return readDocument().normal;
    }
    public static void clearNormalProgress() {
        ProgressDocument document = readDocument();
        if (document.normal == null) return;
        document.normal = null;
        writeDocument(document);
    }

    public static void clearProgress() {
        try {
            Files.deleteIfExists(WaveXinDataPaths.SCAN_PROGRESS_PATH);
        } catch (IOException e) {
            WaveXinAddon.LOG.error("Could not clear scan progress.", e);
        }
    }

    private static ProgressDocument readDocument() {
        if (!Files.exists(WaveXinDataPaths.SCAN_PROGRESS_PATH)) return new ProgressDocument();

        try {
            ProgressDocument document = GSON.fromJson(Files.readString(WaveXinDataPaths.SCAN_PROGRESS_PATH, StandardCharsets.UTF_8), ProgressDocument.class);
            return document == null ? new ProgressDocument() : document;
        } catch (IOException | JsonSyntaxException e) {
            backupCorruptProgress();
            WaveXinAddon.LOG.error("Could not load scan progress from {}.", WaveXinDataPaths.SCAN_PROGRESS_PATH, e);
            return new ProgressDocument();
        }
    }

    private static void writeDocument(ProgressDocument document) {
        try {
            WaveXinSettingsStore.writeAtomically(WaveXinDataPaths.SCAN_PROGRESS_PATH, GSON.toJson(document));
        } catch (IOException e) {
            WaveXinAddon.LOG.error("Could not save scan progress to {}.", WaveXinDataPaths.SCAN_PROGRESS_PATH, e);
        }
    }

    private static ScanProgress loadLegacySpiralProgress() {
        Path legacyPath = FabricLoader.getInstance().getConfigDir().resolve("wavexin").resolve(LEGACY_PROGRESS_FILE);
        if (!Files.exists(legacyPath)) return null;

        try {
            NbtCompound nbt = NbtIo.read(legacyPath);
            if (nbt != null && nbt.contains("ScanProgress")) {
                return nbt.getCompound("ScanProgress").map(ScanProgress::fromNbt).orElse(null);
            }
        } catch (IOException e) {
            WaveXinAddon.LOG.error("Could not read legacy Spiral Scan progress from {}.", legacyPath, e);
        }

        return null;
    }

    private static void backupCorruptProgress() {
        try {
            if (!Files.exists(WaveXinDataPaths.SCAN_PROGRESS_PATH)) return;
            Path backupPath = WaveXinDataPaths.SCAN_PROGRESS_PATH.resolveSibling(
                "scan-progress-corrupt-" + Instant.now().toEpochMilli() + ".json"
            );
            Files.move(WaveXinDataPaths.SCAN_PROGRESS_PATH, backupPath, StandardCopyOption.REPLACE_EXISTING);
            WaveXinAddon.LOG.warn("Backed up invalid scan progress to {}.", backupPath);
        } catch (IOException backupError) {
            WaveXinAddon.LOG.error("Could not back up invalid scan progress.", backupError);
        }
    }

    private static class ProgressDocument {
        int version = 1;
        ScanProgress spiral;
        NormalScanProgress normal;
    }

    record ChunkCoordinates(int x, int z) {
    }

    public static ChunkPos calculateTargetChunkPos(ScanProgress progress, int chunkStep) {
        ChunkCoordinates target = calculateTargetChunkCoordinates(progress, chunkStep);
        return target == null ? null : new ChunkPos(target.x, target.z);
    }

    static ChunkCoordinates calculateTargetChunkCoordinates(ScanProgress progress, int chunkStep) {
        if (progress == null) return null;

        int safeChunkStep = Math.max(1, chunkStep);
        ChunkCoordinates completed = calculateCompletedChunkCoordinates(progress.startX, progress.startZ, progress.totalSegments, safeChunkStep);
        MapScanDirection[] directions = MapScanDirection.values();
        int directionIndex = Math.floorMod(progress.currentDir, directions.length);
        MapScanDirection currentDirection = directions[directionIndex];
        long distance = (long) Math.max(1, progress.currentStepLength) * safeChunkStep;
        return checkedChunkCoordinates(
            (long) completed.x + currentDirection.dx * distance,
            (long) completed.z + currentDirection.dz * distance
        );
    }

    public static ChunkPos calculateCompletedChunkPos(int startX, int startZ, int completedSegments, int chunkStep) {
        ChunkCoordinates completed = calculateCompletedChunkCoordinates(startX, startZ, completedSegments, chunkStep);
        return new ChunkPos(completed.x, completed.z);
    }

    static ChunkCoordinates calculateCompletedChunkCoordinates(int startX, int startZ, int completedSegments, int chunkStep) {
        int safeChunkStep = Math.max(1, chunkStep);
        if (completedSegments <= 0) return new ChunkCoordinates(startX, startZ);

        long lastSegment = (long) completedSegments - 1;
        long ring = lastSegment / 4 + 1;
        int phase = (int) (lastSegment % 4);
        long relativeX;
        long relativeZ;
        switch (phase) {
            case 0 -> {
                relativeX = ring;
                relativeZ = ring - 1;
            }
            case 1 -> {
                relativeX = ring;
                relativeZ = -ring;
            }
            case 2 -> {
                relativeX = -ring;
                relativeZ = -ring;
            }
            default -> {
                relativeX = -ring;
                relativeZ = ring;
            }
        }

        return checkedChunkCoordinates(
            (long) startX + relativeX * safeChunkStep,
            (long) startZ + relativeZ * safeChunkStep
        );
    }

    private static ChunkCoordinates checkedChunkCoordinates(long x, long z) {
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Calculated chunk position exceeds integer range: (" + x + ", " + z + ")");
        }
        return new ChunkCoordinates((int) x, (int) z);
    }

    /**
     * 计算下一个目标区块坐标（前进一个区块段）
     */
    public static ChunkPos calculateNextTargetChunkPos(ScanProgress progress, int chunkStep) {
        if (progress == null) return null;
        
        // 模拟执行一次 turnToNextDirection
        ScanProgress tempProgress = new ScanProgress(
            progress.startX, progress.startZ,
            progress.totalSegments, progress.currentDir,
            progress.stepsInCurrentLength, progress.currentStepLength,
            progress.chunkStep  // 保留原始的 chunkStep
        );
        
        // 更新方向
        tempProgress.currentDir = (tempProgress.currentDir + 1) % 4;
        tempProgress.totalSegments++;
        tempProgress.stepsInCurrentLength++;
        
        if (tempProgress.stepsInCurrentLength >= 2) {
            tempProgress.currentStepLength++;
            tempProgress.stepsInCurrentLength = 0;
        }
        
        // 计算目标坐标
        return calculateTargetChunkPos(tempProgress, chunkStep);
    }
    
    /**
     * 根据玩家当前位置实时计算扫描进度
     * 从指定的起始点开始模拟螺旋路径，找到距离玩家最近的拐点
     * 
     * @param playerChunkX 玩家当前区块 X 坐标
     * @param playerChunkZ 玩家当前区块 Z 坐标
     * @param startX 扫描起始区块 X
     * @param startZ 扫描起始区块 Z
     * @param chunkStep 区块步长（未使用，保留兼容性）
     * @return 计算出的进度，返回距离玩家最近的螺旋路径状态
     */
    public static ScanProgress calculateProgressFromPosition(int playerChunkX, int playerChunkZ,
                                                             int startX, int startZ, int chunkStep) {
        int safeChunkStep = Math.max(1, chunkStep);
        long relativePlayerX = (long) playerChunkX - startX;
        long relativePlayerZ = (long) playerChunkZ - startZ;

        long bestSegment = -1;
        double bestDistanceSquared = Double.POSITIVE_INFINITY;

        for (int phase = 0; phase < 4; phase++) {
            double estimatedRing = switch (phase) {
                case 0 -> (relativePlayerX + relativePlayerZ + safeChunkStep) / (2.0 * safeChunkStep);
                case 1 -> (relativePlayerX - relativePlayerZ) / (2.0 * safeChunkStep);
                case 2 -> -(relativePlayerX + relativePlayerZ) / (2.0 * safeChunkStep);
                default -> (-relativePlayerX + relativePlayerZ) / (2.0 * safeChunkStep);
            };

            long roundedRing = Math.max(1L, Math.round(estimatedRing));
            for (long ring = Math.max(1L, roundedRing - 2); ring <= roundedRing + 2; ring++) {
                long relativeCornerX;
                long relativeCornerZ;
                switch (phase) {
                    case 0 -> {
                        relativeCornerX = ring * safeChunkStep;
                        relativeCornerZ = (ring - 1) * safeChunkStep;
                    }
                    case 1 -> {
                        relativeCornerX = ring * safeChunkStep;
                        relativeCornerZ = -ring * safeChunkStep;
                    }
                    case 2 -> {
                        relativeCornerX = -ring * safeChunkStep;
                        relativeCornerZ = -ring * safeChunkStep;
                    }
                    default -> {
                        relativeCornerX = -ring * safeChunkStep;
                        relativeCornerZ = ring * safeChunkStep;
                    }
                }

                long deltaX = relativePlayerX - relativeCornerX;
                long deltaZ = relativePlayerZ - relativeCornerZ;
                double distanceSquared = (double) deltaX * deltaX + (double) deltaZ * deltaZ;
                long segment = 4L * (ring - 1) + phase;
                if (segment >= Integer.MAX_VALUE) continue;

                if (distanceSquared < bestDistanceSquared
                    || (distanceSquared == bestDistanceSquared && (bestSegment < 0 || segment < bestSegment))) {
                    bestDistanceSquared = distanceSquared;
                    bestSegment = segment;
                }
            }
        }

        if (bestSegment < 0) return null;

        int completedSegments = (int) bestSegment + 1;
        return new ScanProgress(
            startX,
            startZ,
            completedSegments,
            Math.floorMod(completedSegments, MapScanDirection.values().length),
            completedSegments & 1,
            completedSegments / 2 + 1,
            safeChunkStep
        );
    }
}
