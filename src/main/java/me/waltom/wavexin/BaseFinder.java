package me.waltom.wavexin;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.RainbowColors;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.nbt.NbtCompound;

import java.util.HashSet;
import java.util.Set;

public class BaseFinder extends WaveXinModule {
    public enum ScanMethod { SPIRAL("Spiral Scan"), NORMAL("Normal Scan"); private final String title; ScanMethod(String title) { this.title = title; } @Override public String toString() { return title; } }
    private static final Path CONTAINER_RECORD_PATH = WaveXinDataPaths.CONTAINER_DIRECTORY.resolve("container-records.txt");
    private static final Path LEGACY_CONTAINER_RECORD_PATH = MeteorClient.FOLDER.toPath().resolve("base-finder-xin").resolve("container-records.txt");
    private static final DateTimeFormatter RECORD_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public enum SpiralStartMode {
        CURRENT("Current Position"),
        RESUME("Resume Saved Progress"),
        CALCULATE("Calculate from Origin");

        private final String title;

        SpiralStartMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum XaeroWaypointColor {
        RANDOM("Random", -1, 170, 170, 170),
        RED("Red", 0, 255, 85, 85),
        ORANGE("Orange", 1, 255, 170, 0),
        YELLOW("Yellow", 2, 255, 255, 85),
        LIME("Lime", 3, 85, 255, 85),
        GREEN("Green", 4, 0, 170, 0),
        CYAN("Cyan", 5, 0, 170, 170),
        LIGHT_BLUE("Light Blue", 6, 85, 255, 255),
        BLUE("Blue", 7, 85, 85, 255),
        PURPLE("Purple", 8, 170, 0, 170),
        MAGENTA("Magenta", 9, 255, 85, 255),
        PINK("Pink", 10, 255, 85, 170),
        WHITE("White", 11, 255, 255, 255),
        LIGHT_GRAY("Light Gray", 12, 170, 170, 170),
        GRAY("Gray", 13, 85, 85, 85),
        BROWN("Brown", 14, 170, 85, 0),
        BLACK("Black", 15, 0, 0, 0);

        private final String title;
        private final int colorId;
        private final Color displayColor;

        XaeroWaypointColor(String title, int colorId, int red, int green, int blue) {
            this.title = title;
            this.colorId = colorId;
            this.displayColor = new Color(red, green, blue);
        }

        public Color displayColor() {
            return this == RANDOM ? RainbowColors.GLOBAL : displayColor;
        }

        @Override
        public String toString() {
            return title;
        }
    }
    private final SettingGroup sgScanMode = settings.createGroup("Scan Mode");
    private final SettingGroup sgNormalScan = settings.createGroup("Normal Scan");
    private final SettingGroup sgSpiralScan = settings.createGroup("Spiral Scan");
    private final SettingGroup sgSpiralRender = settings.createGroup("Spiral Render");
    private final SettingGroup sgContainerRecording = settings.createGroup("Container Recording");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgRestart = settings.createGroup("Restart");
    private ChunkPos originChunk;
    private ChunkPos targetChunk;
    private ChunkPos resumeCheckpointChunk;
    private int currentCircle;
    private SweepRoute currentPath;
    private int turnDelayTimer;
    private float targetYaw;
    private float normalViewYaw;
    private boolean normalViewRestorePending;

    private boolean forcingForward;

    private final Set<ChunkPos> visitedChunks = new HashSet<>();

    private MapScanDirection spiralDirection = MapScanDirection.EAST;
    private int spiralStepsInCurrentLength;
    private int spiralStepLength = 1;
    private int spiralSegments;
    private ChunkPos spiralStartChunk;
    private ChunkPos spiralTargetChunk;
    private float spiralTargetYaw;
    private boolean spiralRotating;
    private ChunkPos savedNormalScanChunk;
    private int savedNormalScanCircle = -1;
    private SweepRoute savedNormalScanPath;
    private boolean spiralNeedsInitialRotation;
    private ScanMethod activeScanMethod;
    private boolean scanStartPending;
    private int lastCompletedNormalRing = -1;
    private final Set<Long> recordedContainerChunks = new HashSet<>();
    private final List<BlockPos> createdWaypointPositions = new ArrayList<>();
    private int nextWaypointNumber = 1;

    private final Setting<ScanMethod> scanMethod = sgScanMode.add(new EnumSetting.Builder<ScanMethod>()
        .name("Scan Method")
        .description("Selects the scan route and its settings.")
        .defaultValue(ScanMethod.NORMAL)
        .build()
    );

    private final Setting<Integer> spiralChunkStep = sgSpiralScan.add(new IntSetting.Builder()
        .name("Chunk Step")
        .description("Chunks travelled on each spiral segment.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 32)
        .visible(this::isSpiralScan)
        .build()
    );

    private final Setting<Integer> spiralMaximumSegments = sgSpiralScan.add(new IntSetting.Builder()
        .name("Maximum Segments")
        .description("Stops after this many spiral segments. Set to 0 for no limit.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10000)
        .visible(this::isSpiralScan)
        .build()
    );

    private final Setting<SpiralStartMode> spiralStartMode = sgSpiralScan.add(new EnumSetting.Builder<SpiralStartMode>()
        .name("Start Mode")
        .description("Starts at the current chunk, resumes saved progress, or calculates a route from spawn coordinates.")
        .defaultValue(SpiralStartMode.CURRENT)
        .visible(this::isSpiralScan)
        .build()
    );

    private final Setting<Boolean> spiralDebug = sgSpiralScan.add(new BoolSetting.Builder()
        .name("Debug Messages")
        .description("Shows spiral route progress messages.")
        .defaultValue(true)
        .visible(this::isSpiralScan)
        .build()
    );

    private final Setting<Boolean> spiralLockView = sgSpiralScan.add(new BoolSetting.Builder()
        .name("Lock View")
        .description("Locks the view to the current spiral direction.")
        .defaultValue(true)
        .visible(this::isSpiralScan)
        .build()
    );

    private final Setting<Boolean> spiralAutoWalk = sgSpiralScan.add(new BoolSetting.Builder()
        .name("Auto Walk")
        .description("Automatically holds forward while Spiral Scan is active.")
        .defaultValue(true)
        .visible(this::isSpiralScan)
        .build()
    );

    private final Setting<Boolean> spiralSprint = sgSpiralScan.add(new BoolSetting.Builder()
        .name("Sprint")
        .description("Sprints while Spiral Scan auto walk is active.")
        .defaultValue(true)
        .visible(() -> isSpiralScan() && spiralAutoWalk.get())
        .build()
    );

    private final Setting<Boolean> spiralPauseOnScreen = sgSpiralScan.add(new BoolSetting.Builder()
        .name("Pause On Screen")
        .description("Releases movement controls while a screen is open.")
        .defaultValue(true)
        .visible(this::isSpiralScan)
        .build()
    );

    private final Setting<Boolean> spiralRenderRoute = sgSpiralRender.add(new BoolSetting.Builder()
        .name("Render Route")
        .description("Renders the current Spiral Scan route.")
        .defaultValue(true)
        .visible(this::isSpiralScan)
        .build()
    );

    private final Setting<Integer> spiralRenderRange = sgSpiralRender.add(new IntSetting.Builder()
        .name("Render Range")
        .description("How many route markers to render toward the target.")
        .defaultValue(64)
        .min(16)
        .sliderRange(16, 256)
        .visible(() -> isSpiralScan() && spiralRenderRoute.get())
        .build()
    );

    private final Setting<Double> spiralRenderHeight = sgSpiralRender.add(new DoubleSetting.Builder()
        .name("Render Height")
        .description("Render height offset from the player's block Y.")
        .defaultValue(0.02)
        .visible(() -> isSpiralScan() && spiralRenderRoute.get())
        .build()
    );

    private final Setting<ShapeMode> spiralShapeMode = sgSpiralRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("Shape Mode")
        .description("Rendered route shape mode.")
        .defaultValue(ShapeMode.Both)
        .visible(() -> isSpiralScan() && spiralRenderRoute.get())
        .build()
    );

    private final Setting<SettingColor> spiralRouteSideColor = sgSpiralRender.add(new ColorSetting.Builder()
        .name("Route Side Color")
        .description("Route marker side color.")
        .defaultValue(new SettingColor(0, 180, 255, 35))
        .visible(() -> isSpiralScan() && spiralRenderRoute.get())
        .build()
    );

    private final Setting<SettingColor> spiralRouteLineColor = sgSpiralRender.add(new ColorSetting.Builder()
        .name("Route Line Color")
        .description("Route marker line color.")
        .defaultValue(new SettingColor(0, 220, 255, 180))
        .visible(() -> isSpiralScan() && spiralRenderRoute.get())
        .build()
    );

    // 设置玩家当前世界的加载的区块范围
    public final Setting<Integer> chunkLoadRadius = sgNormalScan.add(new IntSetting.Builder()
            .name("Chunk Load Radius")
            .description("Radius of chunks that must be loaded before continuing.")
            .defaultValue(5)
            .min(2)
            .max(30)
            .sliderMin(2)
            .sliderMax(30)
            .visible(this::isNormalScan)
            .build());

    // 设置搜索圈数
    public final Setting<Integer> circleLimit = sgNormalScan.add(new IntSetting.Builder()
            .name("Maximum Scan Rings")
            .description("Stops after this many outward scan rings.")
            .defaultValue(50)
            .min(1)
            .sliderMin(1)
            .sliderMax(1500)
            .visible(this::isNormalScan)
            .build());

    // Move Speed设置
    private final Setting<Double> moveSpeed = sgNormalScan.add(new DoubleSetting.Builder()
            .name("Move Speed")
            .description("Enables sprinting above 1.0; vanilla movement speed is otherwise unchanged.")
            .defaultValue(3.0)
            .min(0.01)
            .max(3.0)
            .sliderMin(0.01)
            .sliderMax(3.0)
            .visible(this::isNormalScan)
            .build());

    // Turn Delaytick
    private final Setting<Boolean> lockView = sgNormalScan.add(new BoolSetting.Builder()
            .name("Lock View")
            .description("Turns the camera toward the current scan target.")
            .defaultValue(true)
            .visible(this::isNormalScan)
            .build());
    private final Setting<Integer> turnDelay = sgNormalScan.add(new IntSetting.Builder()
            .name("Turn Delay")
            .description("Ticks to wait after reaching a scan target.")
            .defaultValue(40)
            .min(1)
            .max(100)
            .sliderMin(1)
            .sliderMax(100)
            .visible(this::isNormalScan)
            .build());

    // 修复卡顿
    private final Setting<Boolean> waitChunkLoad = sgNormalScan.add(new BoolSetting.Builder()
            .name("Wait for Chunk Loading")
            .description("Stops movement until the required chunks have loaded.")
            .defaultValue(true)
            .visible(this::isNormalScan)
            .build());

    // 是否Resume Previous Scan
    private final Setting<Integer> containerThreshold = sgContainerRecording.add(new IntSetting.Builder()
        .name("Container Threshold")
        .description("Records the current chunk when it contains at least this many selected containers.")
        .defaultValue(10)
        .min(2)
        .max(200)
        .sliderRange(2, 200)
        .build()
    );

    private final Setting<List<BlockEntityType<?>>> containerBlocks = sgContainerRecording.add(new StorageBlockListSetting.Builder()
        .name("Container Blocks")
        .description("Container block entity types to count, matching Meteor Storage ESP defaults.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    private final Setting<Boolean> xaeroWaypoints = sgContainerRecording.add(new BoolSetting.Builder()
        .name("Xaero Waypoints")
        .description("Creates a Xaero waypoint when a container chunk is recorded. Requires Xaero's Minimap at runtime.")
        .defaultValue(false)
        .build()
    );

    private final Setting<XaeroWaypointColor> xaeroWaypointColor = sgContainerRecording.add(new XaeroWaypointColorSetting.Builder()
        .name("Waypoint Color").description("Xaero waypoint color, or a random supported color for each waypoint.").defaultValue(XaeroWaypointColor.RANDOM).visible(xaeroWaypoints::get).build()
    );
    private final Setting<Integer> waypointLimitRadius = sgContainerRecording.add(new IntSetting.Builder()
        .name("Area Radius").description("Chunk radius used to group nearby waypoints into one base area.").defaultValue(8).range(1, 64).sliderRange(1, 32).visible(xaeroWaypoints::get).build()
    );
    private final Setting<Integer> maximumWaypointsPerArea = sgContainerRecording.add(new IntSetting.Builder()
        .name("Waypoints per Area").description("Maximum waypoints created within one base area during the current scan.").defaultValue(3).range(1, 100).sliderRange(1, 20).visible(xaeroWaypoints::get).build()
    );
private final Setting<String> xaeroWaypointPrefix = sgContainerRecording.add(new StringSetting.Builder()
        .name("Waypoint Prefix")
        .description("Text before the waypoint name.")
        .defaultValue("Base ")
        .visible(xaeroWaypoints::get)
        .build()
    );

    private final Setting<String> xaeroWaypointSuffix = sgContainerRecording.add(new StringSetting.Builder()
        .name("Waypoint Suffix")
        .description("Text after the waypoint name.")
        .defaultValue("")
        .visible(xaeroWaypoints::get)
        .build()
    );


    private final Setting<Boolean> lastBegin = sgRestart.add(new BoolSetting.Builder()
            .name("Resume Previous Scan")
            .description("Resumes from the saved scan progress.")
            .defaultValue(false)
            .visible(this::isNormalScan)
            .build());

    // 上次的圈数
    private final Setting<Integer> lastCircle = sgRestart.add(new IntSetting.Builder()
            .name("Previous Ring")
            .description("Saved ring number for resuming.")
            .visible(() -> isNormalScan() && lastBegin.get())
            .defaultValue(0)
            .min(0)
            .max(1000)
            .sliderMin(0)
            .sliderMax(100)
            .build());

    // Previous Chunk X
    private final Setting<Integer> lastChunkX = sgRestart.add(new IntSetting.Builder()
            .name("Previous Chunk X")
            .description("Saved chunk X position for resuming.")
            .visible(() -> isNormalScan() && lastBegin.get())
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    // Previous Chunk Z
    private final Setting<Integer> lastChunkZ = sgRestart.add(new IntSetting.Builder()
            .name("Previous Chunk Z")
            .description("Saved chunk Z position for resuming.")
            .visible(() -> isNormalScan() && lastBegin.get())
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    // 上次到哪个方向了
    private final Setting<SweepRoute> lastPath = sgRestart.add(new EnumSetting.Builder<SweepRoute>()
            .name("Previous Route")
            .description("Saved route point for resuming.")
            .visible(() -> isNormalScan() && lastBegin.get())
            .defaultValue(SweepRoute.NEXT_CIRCLE)
            .build());

    // Origin Chunk X
    private final Setting<Integer> lastOriginX = sgRestart.add(new IntSetting.Builder()
            .name("Origin Chunk X")
            .description("Saved origin chunk X for resuming.")
            .visible(() -> isNormalScan() && lastBegin.get())
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    // Origin Chunk Z
    private final Setting<Integer> lastOriginZ = sgRestart.add(new IntSetting.Builder()
            .name("Origin Chunk Z")
            .description("Saved origin chunk Z for resuming.")
            .visible(() -> isNormalScan() && lastBegin.get())
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    // Render Distance设置
    public final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
            .name("Render Distance")
            .description("Maximum route render distance in chunks.")
            .defaultValue(32)
            .min(6)
            .max(128)
            .sliderMin(6)
            .sliderMax(128)
            .visible(this::isNormalScan)
            .build());

    // Render Height设置
    public final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
            .name("Render Height")
            .description("Route marker render height.")
            .defaultValue(0)
            .min(-64)
            .max(320)
            .sliderMin(-64)
            .sliderMax(320)
            .visible(this::isNormalScan)
            .build());

    // 形状模式设置
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("Shape Mode")
            .description("Route rendering shape mode.")
            .defaultValue(ShapeMode.Both)
            .visible(this::isNormalScan)
            .build());

    // Preload Rings设置
    private final Setting<Integer> preloadCircles = sgRender.add(new IntSetting.Builder()
            .name("Preload Rings")
            .description("Number of scan rings to prepare ahead of time.")
            .defaultValue(3)
            .min(1)
            .max(10)
            .sliderMin(1)
            .sliderMax(10)
            .visible(this::isNormalScan)
            .build());

    // 目标区块颜色设置
    private final Setting<SettingColor> targetChunksSideColor = sgRender.add(new ColorSetting.Builder()
            .name("Target Chunk Side Color")
            .description("Target chunk fill color.")
            .defaultValue(new SettingColor(255, 0, 0, 95))
            .visible(() -> isNormalScan() && (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
            .build());

    private final Setting<SettingColor> targetChunksLineColor = sgRender.add(new ColorSetting.Builder()
            .name("Target Chunk Line Color")
            .description("Target chunk outline color.")
            .defaultValue(new SettingColor(255, 0, 0, 205))
            .visible(() -> isNormalScan() && (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both))
            .build());

    // 已访问区块颜色设置
    private final Setting<SettingColor> visitedChunksSideColor = sgRender.add(new ColorSetting.Builder()
            .name("Visited Chunk Side Color")
            .description("Visited chunk fill color.")
            .defaultValue(new SettingColor(0, 255, 0, 40))
            .visible(() -> isNormalScan() && (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
            .build());

    private final Setting<SettingColor> visitedChunksLineColor = sgRender.add(new ColorSetting.Builder()
            .name("Visited Chunk Line Color")
            .description("Visited chunk outline color.")
            .defaultValue(new SettingColor(0, 255, 0, 80))
            .visible(() -> isNormalScan() && (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both))
            .build());

    // 当前路径区块颜色设置
    private final Setting<SettingColor> currentPathSideColor = sgRender.add(new ColorSetting.Builder()
            .name("Current Path Side Color")
            .description("Current path chunk fill color.")
            .defaultValue(new SettingColor(255, 255, 0, 60))
            .visible(() -> isNormalScan() && (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
            .build());

    private final Setting<SettingColor> currentPathLineColor = sgRender.add(new ColorSetting.Builder()
            .name("Current Path Line Color")
            .description("Current path chunk outline color.")
            .defaultValue(new SettingColor(255, 255, 0, 100))
            .visible(() -> isNormalScan() && (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both))
            .build());

    public BaseFinder() {
        super(WaveXinAddon.CATEGORY, "base-finder", "Outward map scanner with chunk-loading pauses.");
    }

    private boolean isNormalScan() {
        return scanMethod.get() == ScanMethod.NORMAL;
    }

    private boolean isSpiralScan() {
        return scanMethod.get() == ScanMethod.SPIRAL;
    }

    @Override
    public void onActivate() {
        migrateLegacyContainerRecords();
        activeScanMethod = scanMethod.get();
        setScanForwardKey(false);
        scanStartPending = true;
        if (mc.player != null && mc.world != null) initializeActiveScan();
    }

    private void initializeActiveScan() {
        if (!scanStartPending || mc.player == null || mc.world == null) return;

        scanStartPending = false;
        recordedContainerChunks.clear();
        createdWaypointPositions.clear();
        nextWaypointNumber = 1;
        validateXaeroWaypointSetting();
        warnIfUnsafeScanHeight();
        if (activeScanMethod == ScanMethod.SPIRAL) {
            startSpiralScan();
            return;
        }

        turnDelayTimer = 0;
        normalViewRestorePending = false;
        lastCompletedNormalRing = -1;
        resumeCheckpointChunk = null;
        if (lastBegin.get()) {
            restoreNormalScanProgress();
        } else {
            currentCircle = 0;
            currentPath = SweepRoute.NEXT_CIRCLE;
            originChunk = mc.player.getChunkPos();
        }

        visitedChunks.clear();
        targetChunk = null;
        savedNormalScanChunk = null;
        savedNormalScanCircle = -1;
        savedNormalScanPath = null;
        if (lastBegin.get()) {
            info("Resumed normal scan at ring %d, route %s.", currentCircle, currentPath);
        } else {
            info("Normal scan started at origin chunk (%d, %d).", originChunk.x, originChunk.z);
        }
    }
    private void restoreNormalScanProgress() {
        ScanProgressManager.NormalScanProgress progress = ScanProgressManager.loadNormalProgress();
        if (progress == null) {
            progress = new ScanProgressManager.NormalScanProgress(
                lastOriginX.get(),
                lastOriginZ.get(),
                lastChunkX.get(),
                lastChunkZ.get(),
                lastCircle.get(),
                lastPath.get().name()
            );
            ScanProgressManager.saveNormalProgress(progress);
        }

        currentCircle = Math.max(0, progress.ring);
        originChunk = new ChunkPos(progress.originX, progress.originZ);
        resumeCheckpointChunk = new ChunkPos(progress.playerX, progress.playerZ);
        try {
            currentPath = SweepRoute.valueOf(progress.route);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            currentPath = SweepRoute.NEXT_CIRCLE;
        }
    }
    private boolean isChunkOnPreparedNormalRing(int chunkX, int chunkZ) {
        if (originChunk == null) return false;

        int minCircle = Math.max(0, currentCircle);
        int maxCircle = getNormalRenderMaxCircle();
        if (minCircle > maxCircle) return false;

        int dx = chunkX - originChunk.x;
        int dz = chunkZ - originChunk.z;
        int maxAbs = Math.max(Math.abs(dx), Math.abs(dz));
        if (maxAbs == 0) return minCircle == 0;

        int step = Math.max(1, chunkLoadRadius.get() * 2);
        if (maxAbs % step != 0) return false;

        int circle = maxAbs / step;
        return circle >= minCircle && circle <= maxCircle;
    }

    private int getNormalRenderMaxCircle() {
        return Math.min(circleLimit.get(), currentCircle + Math.max(0, preloadCircles.get()));
    }

    private boolean isChunkOnCurrentNormalPath(int chunkX, int chunkZ) {
        if (originChunk == null || currentPath == null || currentPath == SweepRoute.NEXT_CIRCLE) return false;

        int radius = Math.max(0, chunkLoadRadius.get() * currentCircle * 2);
        return switch (currentPath) {
            case CENTER_TO_LEFT -> isChunkOnSegment(chunkX, chunkZ, originChunk.x, originChunk.z, originChunk.x - radius, originChunk.z);
            case CENTER_LEFT_TO_UP_LEFT -> isChunkOnSegment(chunkX, chunkZ, originChunk.x - radius, originChunk.z, originChunk.x - radius, originChunk.z - radius);
            case UP_LEFT_TO_UP_RIGHT -> isChunkOnSegment(chunkX, chunkZ, originChunk.x - radius, originChunk.z - radius, originChunk.x + radius, originChunk.z - radius);
            case UP_RIGHT_TO_DOWN_RIGHT -> isChunkOnSegment(chunkX, chunkZ, originChunk.x + radius, originChunk.z - radius, originChunk.x + radius, originChunk.z + radius);
            case DOWN_RIGHT_TO_DOWN_LEFT -> isChunkOnSegment(chunkX, chunkZ, originChunk.x + radius, originChunk.z + radius, originChunk.x - radius, originChunk.z + radius);
            case DOWN_LEFT_TO_LEFT -> isChunkOnSegment(chunkX, chunkZ, originChunk.x - radius, originChunk.z + radius, originChunk.x - radius, originChunk.z);
            case NEXT_CIRCLE -> false;
        };
    }

    private boolean isChunkOnSegment(int chunkX, int chunkZ, int fromX, int fromZ, int toX, int toZ) {
        if (fromX == toX) return chunkX == fromX && isBetween(chunkZ, fromZ, toZ);
        if (fromZ == toZ) return chunkZ == fromZ && isBetween(chunkX, fromX, toX);
        return false;
    }

    private boolean isBetween(int value, int a, int b) {
        return value >= Math.min(a, b) && value <= Math.max(a, b);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (scanStartPending) initializeActiveScan();
        if (activeScanMethod == ScanMethod.SPIRAL) return;

        if (activeScanMethod == null) {
            setScanForwardKey(false);
            return;
        }
        if (mc.player == null || mc.world == null) {
            setScanForwardKey(false);
            return;
        }

        if (resumeCheckpointChunk != null) {
            if (moveToResumeCheckpoint()) {
                info("Reached saved Normal Scan checkpoint at chunk (%d, %d).", resumeCheckpointChunk.x, resumeCheckpointChunk.z);
                resumeCheckpointChunk = null;
            }
            return;
        }


        ChunkPos playerChunk = mc.player.getChunkPos();
        visitedChunks.add(playerChunk);
        recordContainerChunkIfNeeded(playerChunk);

        saveNormalScanProgress(false);

        if (currentCircle > circleLimit.get()) {
            setScanForwardKey(false);
            info("Scan complete.");
            toggle();
            return;
        }

        if (currentPath == SweepRoute.NEXT_CIRCLE) {
            if (currentCircle > 0 && currentCircle != lastCompletedNormalRing) {
                info("Completed normal scan ring %d.", currentCircle);
                lastCompletedNormalRing = currentCircle;
            }

            advanceSweepRoute();
            currentCircle++;

        }

        switch (currentPath) {
            case CENTER_TO_LEFT, DOWN_LEFT_TO_LEFT ->
                targetChunk = new ChunkPos(originChunk.x - chunkLoadRadius.get() * currentCircle * 2, originChunk.z);
            case CENTER_LEFT_TO_UP_LEFT ->
                targetChunk = new ChunkPos(originChunk.x - chunkLoadRadius.get() * currentCircle * 2,
                    originChunk.z - chunkLoadRadius.get() * currentCircle * 2);
            case UP_LEFT_TO_UP_RIGHT ->
                targetChunk = new ChunkPos(originChunk.x + chunkLoadRadius.get() * currentCircle * 2,
                    originChunk.z - chunkLoadRadius.get() * currentCircle * 2);
            case UP_RIGHT_TO_DOWN_RIGHT ->
                targetChunk = new ChunkPos(originChunk.x + chunkLoadRadius.get() * currentCircle * 2,
                    originChunk.z + chunkLoadRadius.get() * currentCircle * 2);
            case DOWN_RIGHT_TO_DOWN_LEFT ->
                targetChunk = new ChunkPos(originChunk.x - chunkLoadRadius.get() * currentCircle * 2,
                    originChunk.z + chunkLoadRadius.get() * currentCircle * 2);
        }

        if (mc.player.getChunkPos().equals(targetChunk)) {
            setScanForwardKey(false);
            if (turnDelayTimer == 0) {
                turnDelayTimer = turnDelay.get();
                return;
            }

            if (turnDelayTimer == 1) {
                advanceSweepRoute();
            }

            turnDelayTimer--;
            return;
        }

        if (targetChunk == null || turnDelayTimer > 0) {
            setScanForwardKey(false);
            return;
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d targetPos = new Vec3d(targetChunk.getStartX() + 8, mc.player.getY(), targetChunk.getStartZ() + 8);

        double deltaX = targetPos.x - playerPos.x;
        double deltaZ = targetPos.z - playerPos.z;

        double distance2D = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        if (distance2D < 1.0) {
            setScanForwardKey(false);
            return;
        }

        targetYaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));

        if (waitChunkLoad.get()) {
            if (!areNeighborChunksLoaded(targetYaw)) {
                setScanForwardKey(false);
                return;
            }

            int chunkX = (int) (mc.player.getX() / 16);
            int chunkZ = (int) (mc.player.getZ() / 16);
            if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                setScanForwardKey(false);
                return;
            }
        }

        if (moveSpeed.get() > 1.0) {
            mc.player.setSprinting(true);
        }

        applyNormalMovementYaw(targetYaw);
        setScanForwardKey(true);
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (activeScanMethod == ScanMethod.NORMAL) restoreNormalViewYaw();
    }

    @Override
    public void onDeactivate() {
        setScanForwardKey(false);
        restoreNormalViewYaw();
        scanStartPending = false;
        ScanMethod stoppedScanMethod = activeScanMethod;
        activeScanMethod = null;

        if (stoppedScanMethod == ScanMethod.SPIRAL) {
            saveSpiralProgress();
            clearSpiralState();
            return;
        }

        boolean scanCompleted = currentCircle > circleLimit.get();
        boolean returningToResumeCheckpoint = resumeCheckpointChunk != null;
        if (!returningToResumeCheckpoint) saveNormalScanProgress(true);

        if (!scanCompleted) {
            if (returningToResumeCheckpoint) {
                info("Normal scan stopped while returning to its saved checkpoint. Saved progress was preserved.");
            } else if (lastBegin.get() && mc.player != null) {
                ChunkPos playerChunk = mc.player.getChunkPos();
                info("Normal scan stopped. Saved checkpoint: (%d, %d), ring %d, route %s.", playerChunk.x, playerChunk.z, currentCircle, currentPath);
            } else {
                info("Normal scan stopped.");
            }
        }
        // 清空区块数据
        visitedChunks.clear();

        // 还原变量
        originChunk = null;
        targetChunk = null;
        resumeCheckpointChunk = null;
        currentPath = null;
        lastCompletedNormalRing = -1;
        turnDelayTimer = 0;
        activeScanMethod = null;
    }

    private void saveNormalScanProgress(boolean force) {
        if (!lastBegin.get() || originChunk == null || currentPath == null || mc.player == null) return;

        ChunkPos playerChunk = mc.player.getChunkPos();
        if (!force && playerChunk.equals(savedNormalScanChunk)
            && currentCircle == savedNormalScanCircle
            && currentPath == savedNormalScanPath) {
            return;
        }

        ScanProgressManager.saveNormalProgress(new ScanProgressManager.NormalScanProgress(
            originChunk.x,
            originChunk.z,
            playerChunk.x,
            playerChunk.z,
            currentCircle,
            currentPath.name()
        ));

        savedNormalScanChunk = playerChunk;
        savedNormalScanCircle = currentCircle;
        savedNormalScanPath = currentPath;
    }


    private void warnIfUnsafeScanHeight() {
        if (mc.player == null || mc.world == null || isSafeScanHeight()) return;
        ChatUtils.error("Recommended to use above each dimension height limit: Nether (Y > 128), Overworld (Y > 320), End (Y > 256)");
    }

    private boolean isSafeScanHeight() {
        String dimensionName = mc.world.getRegistryKey().getValue().toString();
        return switch (dimensionName) {
            case "minecraft:the_nether" -> mc.player.getY() > 128;
            case "minecraft:overworld" -> mc.player.getY() > 320;
            case "minecraft:the_end" -> mc.player.getY() > 256;
            default -> false;
        };
    }

    private boolean moveToResumeCheckpoint() {
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d checkpointCenter = new Vec3d(
            resumeCheckpointChunk.getStartX() + 8,
            mc.player.getY(),
            resumeCheckpointChunk.getStartZ() + 8
        );
        double deltaX = checkpointCenter.x - playerPos.x;
        double deltaZ = checkpointCenter.z - playerPos.z;
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        if (distance < 1.0) {
            setScanForwardKey(false);
            return true;
        }

        targetYaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        if (waitChunkLoad.get()) {
            if (!areNeighborChunksLoaded(targetYaw)) {
                setScanForwardKey(false);
                return false;
            }

            int chunkX = (int) (mc.player.getX() / 16);
            int chunkZ = (int) (mc.player.getZ() / 16);
            if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                setScanForwardKey(false);
                return false;
            }
        }

        if (moveSpeed.get() > 1.0) mc.player.setSprinting(true);
        applyNormalMovementYaw(targetYaw);
        setScanForwardKey(true);
        return false;
    }
    private void applyNormalMovementYaw(float yaw) {
        if (lockView.get()) {
            restoreNormalViewYaw();
            mc.player.setYaw(yaw);
            return;
        }

        normalViewYaw = mc.player.getYaw();
        mc.player.setYaw(yaw);
        normalViewRestorePending = true;
    }

    private void restoreNormalViewYaw() {
        if (!normalViewRestorePending) return;
        if (mc.player != null) mc.player.setYaw(normalViewYaw);
        normalViewRestorePending = false;
        lastCompletedNormalRing = -1;
    }

    private void setScanForwardKey(boolean pressed) {
        if (mc.options == null) return;

        if (pressed) {
            mc.options.forwardKey.setPressed(true);
            forcingForward = true;
        } else if (forcingForward) {
            mc.options.forwardKey.setPressed(false);
            forcingForward = false;
        }
    }

    // 检查当前区块左右两边区块是否已完全加载
    private boolean areNeighborChunksLoaded(float yaw) {
        if (mc.options == null) return true;

        ChunkPos currentChunk = mc.player.getChunkPos();
        boolean movingAlongZ = Math.abs(Math.cos(Math.toRadians(yaw))) >= Math.abs(Math.sin(Math.toRadians(yaw)));
        int loadedRadius = Math.min(chunkLoadRadius.get(), Math.max(0, mc.options.getViewDistance().getValue() - 1));

        for (int offset = -loadedRadius; offset <= loadedRadius; offset++) {
            int chunkX = movingAlongZ ? currentChunk.x + offset : currentChunk.x;
            int chunkZ = movingAlongZ ? currentChunk.z : currentChunk.z + offset;
            if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) return false;
        }

        return true;
    }

    private void migrateLegacyContainerRecords() {
        if (Files.exists(CONTAINER_RECORD_PATH) || !Files.exists(LEGACY_CONTAINER_RECORD_PATH)) return;

        try {
            Files.createDirectories(CONTAINER_RECORD_PATH.getParent());
            Files.copy(LEGACY_CONTAINER_RECORD_PATH, CONTAINER_RECORD_PATH);
            WaveXinAddon.LOG.info("Migrated container records to {}.", CONTAINER_RECORD_PATH);
        } catch (IOException e) {
            WaveXinAddon.LOG.error("Could not migrate container records to {}.", CONTAINER_RECORD_PATH, e);
        }
    }
    private void recordContainerChunkIfNeeded(ChunkPos chunkPos) {
        long key = chunkPos.toLong();
        if (recordedContainerChunks.contains(key)) return;

        WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false);
        if (chunk == null) return;

        int count = 0;
        BlockPos firstPos = null;

        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!containerBlocks.get().contains(blockEntity.getType())) continue;

            count++;
            if (firstPos == null) firstPos = blockEntity.getPos();
        }

        if (count < containerThreshold.get()) return;

        recordedContainerChunks.add(key);
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos recordPos = firstPos != null ? firstPos : playerPos;
        appendContainerRecord(chunkPos, recordPos, playerPos, count);
        createXaeroWaypointIfEnabled(recordPos);
        announceBaseDiscovery(chunkPos, recordPos, count);
    }

    private boolean isScanAreaLoaded() {
        ChunkPos center = mc.player.getChunkPos();
        int radius = mc.options.getViewDistance().getValue();

        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                if (!mc.world.getChunkManager().isChunkLoaded(center.x + offsetX, center.z + offsetZ)) return false;
            }
        }

        return true;
    }

    private void announceBaseDiscovery(ChunkPos chunkPos, BlockPos recordPos, int count) {
        ChatUtils.warning("(highlight)(bold)Base found! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default) | Containers: (highlight)%d(default)",
            chunkPos.x, chunkPos.z, recordPos.getX(), recordPos.getY(), recordPos.getZ(), count);
    }

    private boolean hasReachedWaypointLimit(BlockPos candidate) {
        int radiusBlocks = waypointLimitRadius.get() * 16;
        int nearby = 0;
        for (BlockPos existing : createdWaypointPositions) {
            if (Math.abs(existing.getX() - candidate.getX()) > radiusBlocks || Math.abs(existing.getZ() - candidate.getZ()) > radiusBlocks) continue;
            if (++nearby >= maximumWaypointsPerArea.get()) {
                info("Skipped Xaero waypoint near (%d, %d): area limit of %d reached.", candidate.getX(), candidate.getZ(), maximumWaypointsPerArea.get());
                return true;
            }
        }
        return false;
    }

    private int getXaeroWaypointColorId() {
        XaeroWaypointColor color = xaeroWaypointColor.get();
        return color == XaeroWaypointColor.RANDOM ? ThreadLocalRandom.current().nextInt(16) : color.colorId;
    }
    private void appendContainerRecord(ChunkPos chunkPos, BlockPos recordPos, BlockPos playerPos, int count) {
        String line = "%s | chunk=(%d,%d) | first-container=(%d,%d,%d) | player=(%d,%d,%d) | count=%d%n".formatted(
            LocalDateTime.now().format(RECORD_TIME_FORMAT),
            chunkPos.x,
            chunkPos.z,
            recordPos.getX(),
            recordPos.getY(),
            recordPos.getZ(),
            playerPos.getX(),
            playerPos.getY(),
            playerPos.getZ(),
            count
        );

        try {
            Files.createDirectories(CONTAINER_RECORD_PATH.getParent());
            Files.writeString(CONTAINER_RECORD_PATH, line, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            error("Failed to save container chunk record: %s", e.getMessage());
        }
    }

    private void createXaeroWaypointIfEnabled(BlockPos pos) {
        if (!validateXaeroWaypointSetting()) return;

        try {
            if (hasReachedWaypointLimit(pos)) return;

            String name = xaeroWaypointPrefix.get() + nextWaypointNumber + xaeroWaypointSuffix.get();
            String initials = makeWaypointInitials(name);

            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Object currentSession = sessionClass.getMethod("getCurrentSession").invoke(null);
            if (currentSession == null) {
                warning("Xaero's Minimap session is not ready. Container record saved without a waypoint.");
                return;
            }

            Object processor = currentSession.getClass().getMethod("getMinimapProcessor").invoke(currentSession);
            Object minimapSession = processor.getClass().getMethod("getSession").invoke(processor);
            Object worldManager = minimapSession.getClass().getMethod("getWorldManager").invoke(minimapSession);
            Object currentWorld = worldManager.getClass().getMethod("getCurrentWorld").invoke(worldManager);
            if (currentWorld == null) {
                warning("Xaero current waypoint world is not ready. Container record saved without a waypoint.");
                return;
            }

            Object waypointSet = currentWorld.getClass().getMethod("getCurrentWaypointSet").invoke(currentWorld);
            if (waypointSet == null) {
                warning("Xaero current waypoint set is not ready. Container record saved without a waypoint.");
                return;
            }

            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Constructor<?> constructor = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
            Object waypoint = constructor.newInstance(pos.getX(), pos.getY(), pos.getZ(), name, initials, getXaeroWaypointColorId());
            Method addMethod = waypointSet.getClass().getMethod("add", waypointClass);
            addMethod.invoke(waypointSet, waypoint);

            Object waypointSession = minimapSession.getClass().getMethod("getWaypointSession").invoke(minimapSession);
            waypointSession.getClass().getMethod("setSetChangedTime", long.class).invoke(waypointSession, System.currentTimeMillis());
            createdWaypointPositions.add(pos.toImmutable());
            nextWaypointNumber++;
            info("Created Xaero waypoint: " + name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warning("Failed to create Xaero waypoint: %s", e.getMessage());
        }
    }

    private boolean isXaeroAvailable() {
        try {
            Class.forName("xaero.common.XaeroMinimapSession");
            Class.forName("xaero.common.minimap.waypoints.Waypoint");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private boolean validateXaeroWaypointSetting() {
        if (!xaeroWaypoints.get()) return false;
        if (isXaeroAvailable()) return true;

        xaeroWaypoints.set(false);
        warning("Xaero's Minimap was not detected. Xaero Waypoints has been disabled, but container recording will continue.");
        return false;
    }

    private String makeWaypointInitials(String name) {
        if (name == null || name.isBlank()) return "B";

        StringBuilder initials = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && initials.length() < 2) initials.append(Character.toUpperCase(part.charAt(0)));
        }

        return initials.isEmpty() ? "B" : initials.toString();
    }


    private void startSpiralScan() {
        if (mc.player == null) return;

        switch (spiralStartMode.get()) {
            case CURRENT -> startNewSpiralScan(mc.player.getChunkPos());
            case RESUME -> resumeSpiralScan();
            case CALCULATE -> calculateSpiralScanFromOrigin();
        }
    }

    private void startNewSpiralScan(ChunkPos startChunk) {
        spiralStartChunk = startChunk;
        spiralDirection = MapScanDirection.EAST;
        spiralStepsInCurrentLength = 0;
        spiralStepLength = 1;
        spiralSegments = 0;
        spiralRotating = false;
        spiralNeedsInitialRotation = false;
        updateSpiralTarget();
        applySpiralRotation(spiralDirection.yaw);
        saveSpiralProgress();

        if (spiralDebug.get()) {
            info("Spiral scan started at chunk (%d, %d).", startChunk.x, startChunk.z);
            info("Next spiral target: (%d, %d).", spiralTargetChunk.x, spiralTargetChunk.z);
        }
    }

    private void resumeSpiralScan() {
        ScanProgressManager.ScanProgress progress = ScanProgressManager.loadProgress();
        if (progress == null) {
            if (spiralDebug.get()) info("No saved spiral progress was found. Starting from the current chunk.");
            startNewSpiralScan(mc.player.getChunkPos());
            return;
        }

        if (progress.chunkStep != spiralChunkStep.get()) {
            info("Saved spiral chunk step is %d. Using it to resume this scan.", progress.chunkStep);
            spiralChunkStep.set(progress.chunkStep);
        }

        applySpiralProgress(progress);
        calibrateSpiralDirection();

        if (spiralDebug.get()) {
            info("Resumed spiral scan from chunk (%d, %d) after %d segments.", spiralStartChunk.x, spiralStartChunk.z, spiralSegments);
            info("Next spiral target: (%d, %d).", spiralTargetChunk.x, spiralTargetChunk.z);
        }
    }

    private void calculateSpiralScanFromOrigin() {
        ChunkPos playerChunk = mc.player.getChunkPos();
        ScanProgressManager.ScanProgress progress = ScanProgressManager.calculateProgressFromPosition(
            playerChunk.x, playerChunk.z, 0, 0, spiralChunkStep.get()
        );

        if (progress == null) {
            startNewSpiralScan(new ChunkPos(0, 0));
            return;
        }

        ChunkPos corner = getSpiralCorner(progress.totalSegments);
        int differenceX = Math.abs(Math.abs(playerChunk.x) - Math.abs(corner.x));
        int differenceZ = Math.abs(Math.abs(playerChunk.z) - Math.abs(corner.z));
        if (differenceX > 2 || differenceZ > 2) {
            warning("Current position is too far from the calculated spiral route.");
            info("Recommended chunk: (%d, %d).", corner.x, corner.z);
            info("Recommended block position: (%d, %d).", corner.x * 16, corner.z * 16);
            toggle();
            return;
        }

        applySpiralProgress(progress);
        calibrateSpiralDirection();

        if (spiralDebug.get()) {
            info("Calculated spiral progress after %d segments.", spiralSegments);
            info("Next spiral target: (%d, %d).", spiralTargetChunk.x, spiralTargetChunk.z);
        }
    }

    private void applySpiralProgress(ScanProgressManager.ScanProgress progress) {
        MapScanDirection[] directions = MapScanDirection.values();
        if (progress.currentDir < 0 || progress.currentDir >= directions.length) {
            progress.currentDir = MapScanDirection.EAST.ordinal();
        }

        spiralStartChunk = new ChunkPos(progress.startX, progress.startZ);
        spiralDirection = directions[progress.currentDir];
        spiralStepsInCurrentLength = progress.stepsInCurrentLength;
        spiralStepLength = Math.max(1, progress.currentStepLength);
        spiralSegments = Math.max(0, progress.totalSegments);
        spiralRotating = false;
        spiralNeedsInitialRotation = false;
        updateSpiralTarget();
    }

    private void calibrateSpiralDirection() {
        if (spiralDirection.isFacingDirection(mc.player.getYaw())) {
            if (spiralLockView.get()) applySpiralRotation(spiralDirection.yaw);
            return;
        }

        spiralNeedsInitialRotation = true;
        spiralTargetYaw = spiralDirection.yaw;
        spiralRotating = true;
        if (spiralDebug.get()) info("Calibrating view toward %s.", spiralDirection);
    }

    private void runSpiralScan() {
        if (mc.player == null || mc.world == null || spiralTargetChunk == null) {
            setScanForwardKey(false);
            return;
        }

        setScanForwardKey(false);
        if (spiralPauseOnScreen.get() && mc.currentScreen != null) {
            return;
        }

        ChunkPos playerChunk = mc.player.getChunkPos();
        recordContainerChunkIfNeeded(playerChunk);

        if (spiralMaximumSegments.get() > 0 && spiralSegments >= spiralMaximumSegments.get()) {
            info("Maximum segments reached. Spiral scan complete.");
            toggle();
            return;
        }

        if (spiralNeedsInitialRotation && spiralRotating) {
            smoothSpiralRotation();
            if (spiralRotating) return;
        }

        if (spiralRotating) {
            smoothSpiralRotation();
            if (spiralRotating) return;
        }

        if (spiralLockView.get() && !spiralRotating && !spiralNeedsInitialRotation) {
            applySpiralRotation(spiralDirection.yaw);
        }

        handleSpiralAutoWalk();


        if (!hasReachedSpiralTarget(playerChunk)) return;
        boolean smoothRotation = advanceSpiralDirection();
        updateSpiralTarget();
        saveSpiralProgress();
        if (smoothRotation) {
            spiralTargetYaw = spiralDirection.yaw;
            spiralRotating = true;
        }

        if (spiralDebug.get()) {
            info("Spiral direction: %s. Next target: (%d, %d).", spiralDirection, spiralTargetChunk.x, spiralTargetChunk.z);
        }
    }

    private void handleSpiralAutoWalk() {
        if (!spiralAutoWalk.get()) {
            setScanForwardKey(false);
            return;
        }

        setScanForwardKey(true);
        if (spiralSprint.get()) mc.player.setSprinting(true);
    }

    private boolean hasReachedSpiralTarget(ChunkPos currentChunk) {
        return switch (spiralDirection) {
            case EAST -> currentChunk.x >= spiralTargetChunk.x;
            case WEST -> currentChunk.x <= spiralTargetChunk.x;
            case NORTH -> currentChunk.z <= spiralTargetChunk.z;
            case SOUTH -> currentChunk.z >= spiralTargetChunk.z;
        };
    }

    private boolean advanceSpiralDirection() {
        spiralDirection = spiralDirection.getNext();
        spiralStepsInCurrentLength++;
        spiralSegments++;

        if (spiralStepsInCurrentLength >= 2) {
            spiralStepLength++;
            spiralStepsInCurrentLength = 0;
        }

        if (spiralLockView.get()) {
            applySpiralRotation(spiralDirection.yaw);
            return false;
        }
        return true;
    }

    private void updateSpiralTarget() {
        if (spiralStartChunk == null) return;

        ScanProgressManager.ScanProgress progress = new ScanProgressManager.ScanProgress(
            spiralStartChunk.x,
            spiralStartChunk.z,
            spiralSegments,
            spiralDirection.ordinal(),
            spiralStepsInCurrentLength,
            spiralStepLength,
            spiralChunkStep.get()
        );
        spiralTargetChunk = ScanProgressManager.calculateTargetChunkPos(progress, spiralChunkStep.get());
    }

    private ChunkPos getSpiralCorner(int completedSegments) {
        int x = 0;
        int z = 0;
        MapScanDirection direction = MapScanDirection.EAST;
        int length = 1;
        int stepsAtLength = 0;

        for (int segment = 0; segment < completedSegments; segment++) {
            int distance = length * spiralChunkStep.get();
            x += direction.dx * distance;
            z += direction.dz * distance;
            stepsAtLength++;
            if (stepsAtLength >= 2) {
                length++;
                stepsAtLength = 0;
            }
            direction = direction.getNext();
        }

        return new ChunkPos(x, z);
    }

    private void smoothSpiralRotation() {
        if (mc.player == null) {
            spiralRotating = false;
            return;
        }

        float currentYaw = mc.player.getYaw();
        float difference = spiralTargetYaw - currentYaw;
        if (difference > 180f) difference -= 360f;
        if (difference < -180f) difference += 360f;

        float rotationSpeed = 15f;
        if (Math.abs(difference) < rotationSpeed) {
            applySpiralRotation(spiralTargetYaw);
            spiralRotating = false;
            if (spiralDebug.get()) info("Spiral rotation complete.");
        } else {
            applySpiralRotation(currentYaw + Math.signum(difference) * rotationSpeed);
        }
    }

    private void applySpiralRotation(float yaw) {
        mc.player.setYaw(yaw);
        mc.player.headYaw = yaw;
        mc.player.bodyYaw = yaw;
    }

    private void saveSpiralProgress() {
        if (spiralStartChunk == null) return;

        ScanProgressManager.saveProgress(new ScanProgressManager.ScanProgress(
            spiralStartChunk.x,
            spiralStartChunk.z,
            spiralSegments,
            spiralDirection.ordinal(),
            spiralStepsInCurrentLength,
            spiralStepLength,
            spiralChunkStep.get()
        ));
    }

    private void clearSpiralState() {
        spiralStartChunk = null;
        spiralTargetChunk = null;
        spiralSegments = 0;
        spiralStepsInCurrentLength = 0;
        spiralStepLength = 1;
        spiralRotating = false;
        spiralNeedsInitialRotation = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSpiralTick(TickEvent.Pre event) {
        if (activeScanMethod != ScanMethod.SPIRAL) return;
        if (scanStartPending) initializeActiveScan();
        if (!scanStartPending) runSpiralScan();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        if (activeScanMethod == ScanMethod.SPIRAL) {
            renderSpiralRoute(event);
            return;
        }


        renderNormalRoute(event);
    }

    private void renderNormalRoute(Render3DEvent event) {
        if (activeScanMethod != ScanMethod.NORMAL || originChunk == null || currentPath == null || mc.player == null) return;

        ChunkPos playerChunk = mc.player.getChunkPos();
        int renderRadius = Math.max(1, renderDistance.get());
        double maxDistance = renderRadius * 16.0;
        double maxDistanceSq = maxDistance * maxDistance;

        for (int chunkX = playerChunk.x - renderRadius; chunkX <= playerChunk.x + renderRadius; chunkX++) {
            for (int chunkZ = playerChunk.z - renderRadius; chunkZ <= playerChunk.z + renderRadius; chunkZ++) {
                double deltaX = chunkX * 16.0 + 8.0 - mc.player.getX();
                double deltaZ = chunkZ * 16.0 + 8.0 - mc.player.getZ();
                if (deltaX * deltaX + deltaZ * deltaZ > maxDistanceSq) continue;

                boolean currentPathChunk = isChunkOnCurrentNormalPath(chunkX, chunkZ);
                boolean targetPreviewChunk = isChunkOnPreparedNormalRing(chunkX, chunkZ);
                if (!currentPathChunk && !targetPreviewChunk) continue;

                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                SettingColor sideColor;
                SettingColor lineColor;

                if (currentPathChunk) {
                    sideColor = currentPathSideColor.get();
                    lineColor = currentPathLineColor.get();
                } else if (visitedChunks.contains(chunk)) {
                    sideColor = visitedChunksSideColor.get();
                    lineColor = visitedChunksLineColor.get();
                } else {
                    sideColor = targetChunksSideColor.get();
                    lineColor = targetChunksLineColor.get();
                }

                if (sideColor.a > 5 || lineColor.a > 5) {
                    renderScanChunk(chunk, sideColor, lineColor, event);
                }
            }
        }
    }
    private void renderSpiralRoute(Render3DEvent event) {
        if (!spiralRenderRoute.get() || mc.world == null || spiralStartChunk == null || spiralTargetChunk == null) return;

        ChunkPos playerChunk = mc.player.getChunkPos();
        int markers = Math.max(1, spiralRenderRange.get() / 16);
        int maximumMarkers = Math.min(markers, Math.max(1, spiralStepLength * spiralChunkStep.get()));
        double y = Math.floor(mc.player.getY()) + spiralRenderHeight.get();

        for (int marker = 0; marker <= maximumMarkers; marker++) {
            int chunkX = playerChunk.x + spiralDirection.dx * marker;
            int chunkZ = playerChunk.z + spiralDirection.dz * marker;
            if (hasPassedSpiralRenderTarget(chunkX, chunkZ)) break;

            double minX = chunkX * 16.0;
            double minZ = chunkZ * 16.0;
            event.renderer.box(
                minX, y, minZ,
                minX + 16.0, y + 0.05, minZ + 16.0,
                spiralRouteSideColor.get(), spiralRouteLineColor.get(), spiralShapeMode.get(), 0
            );
        }
    }

    private boolean hasPassedSpiralRenderTarget(int chunkX, int chunkZ) {
        return switch (spiralDirection) {
            case EAST -> chunkX > spiralTargetChunk.x;
            case WEST -> chunkX < spiralTargetChunk.x;
            case NORTH -> chunkZ < spiralTargetChunk.z;
            case SOUTH -> chunkZ > spiralTargetChunk.z;
        };
    }

    private void renderScanChunk(ChunkPos chunk, SettingColor sideColor, SettingColor lineColor, Render3DEvent event) {
        Box box = new Box(
                new Vec3d(chunk.getStartX(), renderHeight.get(), chunk.getStartZ()),
                new Vec3d(chunk.getEndX() + 1, renderHeight.get() + 1, chunk.getEndZ() + 1));

        event.renderer.box(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                sideColor, lineColor, shapeMode.get(), 0);
    }

    // 更新下一个路径
    private void advanceSweepRoute() {
        switch (currentPath) {
            case NEXT_CIRCLE -> currentPath = SweepRoute.CENTER_TO_LEFT;
            case CENTER_TO_LEFT -> currentPath = SweepRoute.CENTER_LEFT_TO_UP_LEFT;
            case CENTER_LEFT_TO_UP_LEFT -> currentPath = SweepRoute.UP_LEFT_TO_UP_RIGHT;
            case UP_LEFT_TO_UP_RIGHT -> currentPath = SweepRoute.UP_RIGHT_TO_DOWN_RIGHT;
            case UP_RIGHT_TO_DOWN_RIGHT -> currentPath = SweepRoute.DOWN_RIGHT_TO_DOWN_LEFT;
            case DOWN_RIGHT_TO_DOWN_LEFT -> currentPath = SweepRoute.DOWN_LEFT_TO_LEFT;
            case DOWN_LEFT_TO_LEFT -> currentPath = SweepRoute.NEXT_CIRCLE;
        }
    }

    // ==================== 速度获取和设置方法 ====================

    /**
     * 获取玩家当前的X轴速度
     */
    private double getX() {
        return mc.player.getVelocity().x;
    }

    /**
     * 获取玩家当前的Y轴速度
     */
    private double getY() {
        return mc.player.getVelocity().y;
    }

    /**
     * 获取玩家当前的Z轴速度
     */
    private double getZ() {
        return mc.player.getVelocity().z;
    }

    /**
     * 设置玩家的X轴速度
     * 保持Y和Z轴速度不变
     */
    private void setX(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(f, currentVel.y, currentVel.z);
        mc.player.setVelocity(newVel);
    }

    /**
     * 设置玩家的Y轴速度
     * 保持X和Z轴速度不变
     */
    private void setY(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(currentVel.x, f, currentVel.z);
        mc.player.setVelocity(newVel);
    }

    /**
     * 设置玩家的Z轴速度
     * 保持X和Y轴速度不变
     */
    private void setZ(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(currentVel.x, currentVel.y, f);
        mc.player.setVelocity(newVel);
    }

    @Override
    public String getInfoString() {
        if (activeScanMethod != ScanMethod.SPIRAL) return null;
        return spiralSegments + " | " + spiralDirection.name();
    }

    public enum SweepRoute {
        NEXT_CIRCLE,
        CENTER_TO_LEFT,
        CENTER_LEFT_TO_UP_LEFT,
        UP_LEFT_TO_UP_RIGHT,
        UP_RIGHT_TO_DOWN_RIGHT,
        DOWN_RIGHT_TO_DOWN_LEFT,
        DOWN_LEFT_TO_LEFT
    }
}
