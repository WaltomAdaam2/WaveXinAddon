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

        public NormalScanProgress() {
        }

        public NormalScanProgress(int originX, int originZ, int playerX, int playerZ, int ring, String route) {
            this.originX = originX;
            this.originZ = originZ;
            this.playerX = playerX;
            this.playerZ = playerZ;
            this.ring = ring;
            this.route = route;
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
    public static ChunkPos calculateTargetChunkPos(ScanProgress progress, int chunkStep) {
        if (progress == null) return null;
        
        // 计算从起点开始到当前段的累积偏移
        int currentX = progress.startX;
        int currentZ = progress.startZ;
        
        MapScanDirection[] directions = MapScanDirection.values();
        int tempDirIdx = 0; // 从 EAST 开始
        int tempStepLen = 1;
        int tempStepsInLen = 0;
        
        // 累加所有已完成的段
        for (int i = 0; i < progress.totalSegments; i++) {
            // 步长序列：1, 1, 2, 2, 3, 3... （需要乘以 chunkStep）
            int dist = tempStepLen * chunkStep;
            MapScanDirection dir = directions[tempDirIdx];
            currentX += dir.dx * dist;
            currentZ += dir.dz * dist;
            
            tempStepsInLen++;
            if (tempStepsInLen >= 2) {
                tempStepLen++;
                tempStepsInLen = 0;
            }
            
            tempDirIdx = (tempDirIdx + 1) % 4;
        }
        
        // 当前方向的目标 = 已完成的累积位置 + 当前段应移动的距离
        MapScanDirection currentDir = directions[progress.currentDir];
        int currentDist = progress.currentStepLength * chunkStep;  // 需要乘以 chunkStep
        int targetX = currentX + currentDir.dx * currentDist;
        int targetZ = currentZ + currentDir.dz * currentDist;
        
        return new ChunkPos(targetX, targetZ);
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
        MapScanDirection[] directions = MapScanDirection.values();
        
        int currentX = startX;
        int currentZ = startZ;
        int tempStepLen = 1;
        int tempStepsInLen = 0;
        int tempDirIdx = 0;
        
        // 记录距离玩家最近的拐点信息
        int closestSegment = 0;
        int closestDirIdx = 0;
        int closestStepLen = 1;
        int closestStepsInLen = 0;
        double minDistance = Double.MAX_VALUE;
        
        // 模拟螺旋路径，查找距离玩家最近的拐点
        int maxSearchSegments = 10000;
        
        for (int segment = 0; segment < maxSearchSegments; segment++) {
            MapScanDirection dir = directions[tempDirIdx];
            int dist = tempStepLen * chunkStep;  // 需要乘以 chunkStep
            
            // 当前段的终点（即拐点）
            int cornerX = currentX + dir.dx * dist;
            int cornerZ = currentZ + dir.dz * dist;
            
            // 计算玩家到该拐点的距离
            double distance = Math.sqrt(
                Math.pow(playerChunkX - cornerX, 2) + 
                Math.pow(playerChunkZ - cornerZ, 2)
            );
            
            // 如果这个拐点更近，更新记录
            if (distance < minDistance) {
                minDistance = distance;
                closestSegment = segment;
                closestDirIdx = tempDirIdx;
                closestStepLen = tempStepLen;
                closestStepsInLen = tempStepsInLen;
            }
            
            // 如果距离开始变大，说明已经过了最近的拐点，可以提前结束
            // 但为了保险起见，继续搜索一小段距离
            if (distance > minDistance && segment > closestSegment + 50) {
                break;
            }
            
            // 移动到下一个拐点
            currentX = cornerX;
            currentZ = cornerZ;
            
            tempStepsInLen++;
            if (tempStepsInLen >= 2) {
                tempStepLen++;
                tempStepsInLen = 0;
            }
            tempDirIdx = (tempDirIdx + 1) % 4;
        }
        
        int nextTotalSegments = closestSegment + 1;
        int nextDirIdx = (closestDirIdx + 1) % directions.length;
        int nextStepsInLen = closestStepsInLen + 1;
        int nextStepLen = closestStepLen;
        if (nextStepsInLen >= 2) {
            nextStepLen++;
            nextStepsInLen = 0;
        }

        // 返回距离玩家最近的拐点之后的下一段状态
        return new ScanProgress(
            startX, startZ,
            nextTotalSegments,   // totalSegments
            nextDirIdx,          // currentDir
            nextStepsInLen,
            nextStepLen,         // currentStepLength
            chunkStep            // 必须传入 chunkStep，否则会使用默认值6导致计算错误
        );
    }
}
