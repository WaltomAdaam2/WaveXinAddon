package me.waltom.wavexin.modules.basefinder;

import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.core.WaveXinDataPaths;
import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.i18n.WaveXinI18n;
import me.waltom.wavexin.gui.WaveXinEnumDropdown;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.RainbowColors;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.world.chunk.WorldChunk;
import java.io.IOException;
import java.lang.ref.WeakReference;
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
import java.util.function.Consumer;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BaseFinder extends WaveXinModule {
    static {
        SettingsWidgetFactory.registerCustomFactory(RestartIntSetting.class, theme -> (table, setting) -> {
            RestartIntSetting intSetting = (RestartIntSetting) setting;
            WIntEdit edit = table.add(theme.intEdit(intSetting.get(), intSetting.min, intSetting.max, intSetting.sliderMin, intSetting.sliderMax, intSetting.noSlider)).expandX().widget();
            intSetting.addWidget(edit);
            edit.action = () -> {
                if (!intSetting.set(edit.get())) edit.set(intSetting.get());
            };

            var reset = table.add(theme.button(GuiRenderer.RESET)).widget();
            reset.action = () -> {
                intSetting.reset();
                edit.set(intSetting.get());
            };
            reset.tooltip = WaveXinI18n.tr("tooltip.wavexin.common.reset", "Reset");
        });
        SettingsWidgetFactory.registerCustomFactory(RestartRouteSetting.class, theme -> (table, setting) -> {
            RestartRouteSetting routeSetting = (RestartRouteSetting) setting;
            WDropdown<SweepRoute> dropdown = table.add(new WaveXinEnumDropdown<>(SweepRoute.values(), routeSetting.get(), routeSetting.module)).expandCellX().widget();
            routeSetting.addWidget(dropdown);
            dropdown.action = () -> routeSetting.set(dropdown.get());

            var reset = table.add(theme.button(GuiRenderer.RESET)).widget();
            reset.action = () -> {
                routeSetting.reset();
                dropdown.set(routeSetting.get());
            };
            reset.tooltip = WaveXinI18n.tr("tooltip.wavexin.common.reset", "Reset");
        });
        SettingsWidgetFactory.registerCustomFactory(RestartButtonSetting.class, theme -> (table, setting) -> {
            RestartButtonSetting buttonSetting = (RestartButtonSetting) setting;
            var button = table.add(theme.button(WaveXinI18n.tr(buttonSetting.buttonKey, buttonSetting.buttonLabel))).expandX().widget();
            button.action = buttonSetting::run;
            button.tooltip = WaveXinI18n.tr(buttonSetting.tooltipKey, setting.description);
        });
    }

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
    private final BaseFinderStateLogic.ViewRotationState movementViewState = new BaseFinderStateLogic.ViewRotationState();
    private final BaseFinderStateLogic.SprintState scanSprintState = new BaseFinderStateLogic.SprintState();

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
    private boolean spiralNeedsInitialRotation;
    private ScanMethod activeScanMethod;
    private boolean scanStartPending;
    private static final int NORMAL_DEBUG_HEARTBEAT_TICKS = 40;
    private int normalDebugTicks;
    private String normalDebugState = "INACTIVE";
    private int lastCompletedNormalRing = -1;
    private final Set<Long> recordedContainerChunks = new HashSet<>();
    private final Set<UUID> recordedThrownPearls = new HashSet<>();
    private final List<BlockPos> createdWaypointPositions = new ArrayList<>();
    private final Set<Long> warnedMissingLoadedChunks = new HashSet<>();
    private final Set<String> warnedNormalDebugStates = new HashSet<>();
    private int nextWaypointNumber = 1;
    private int nextPearlWaypointNumber = 1;
    private boolean warnedEmptyContainerBlocks;
    private boolean warnedContainerScanUnavailable;

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

// Normal scan settings
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

// Maximum scan rings
    public final Setting<Integer> circleLimit = sgNormalScan.add(new IntSetting.Builder()
            .name("Maximum Scan Rings")
            .description("Stops after this many outward scan rings.")
            .defaultValue(50)
            .min(1)
            .sliderMin(1)
            .sliderMax(1500)
            .visible(this::isNormalScan)
            .build());

// Move speed setting
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

// Chunk loading wait
    private final Setting<Boolean> waitChunkLoad = sgNormalScan.add(new BoolSetting.Builder()
            .name("Wait for Chunk Loading")
            .description("Stops movement until the required chunks have loaded.")
            .defaultValue(true)
            .visible(this::isNormalScan)
            .build());

    private final Setting<Integer> chunkWaitDistance = sgNormalScan.add(new IntSetting.Builder()
            .name("Chunk Wait Distance")
            .description("Forward chunk distance checked while waiting for chunks to load.")
            .defaultValue(4)
            .min(1)
            .max(30)
            .sliderMin(1)
            .sliderMax(30)
            .visible(() -> isNormalScan() && waitChunkLoad.get())
            .build());

// Resume previous scan
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

    private final Setting<Boolean> detectThrownPearls = sgContainerRecording.add(new BoolSetting.Builder()
        .name("Detect Thrown Pearls")
        .description("Announces thrown ender pearls detected while Base Finder is active.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> xaeroWaypoints = sgContainerRecording.add(new BoolSetting.Builder()
        .name("Xaero Waypoints")
        .description("Creates a Xaero waypoint when a container chunk is recorded. Requires Xaero's Minimap at runtime.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> recordThrownPearls = sgContainerRecording.add(new BoolSetting.Builder()
        .name("Record Thrown Pearl")
        .description("Creates unlimited Xaero waypoints for detected thrown ender pearls, using Pearl names and P aliases.")
        .defaultValue(false)
        .visible(xaeroWaypoints::get)
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

    private final Setting<Integer> lastCircle = sgRestart.add(new RestartIntSetting.Builder()
            .name("Previous Ring")
            .description("Saved ring number for resuming.")
            .visible(this::showNormalResumeProgressSettings)
            .defaultValue(0)
            .min(0)
            .max(1000)
            .sliderMin(0)
            .sliderMax(100)
            .build());

    // Previous Chunk X
    private final Setting<Integer> lastChunkX = sgRestart.add(new RestartIntSetting.Builder()
            .name("Previous Chunk X")
            .description("Saved chunk X position for resuming.")
            .visible(this::showNormalResumeProgressSettings)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .noSlider()
            .build());

    // Previous Chunk Z
    private final Setting<Integer> lastChunkZ = sgRestart.add(new RestartIntSetting.Builder()
            .name("Previous Chunk Z")
            .description("Saved chunk Z position for resuming.")
            .visible(this::showNormalResumeProgressSettings)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .noSlider()
            .build());

// Previous route
    private final Setting<SweepRoute> lastPath = sgRestart.add(new RestartRouteSetting.Builder()
            .name("Previous Route")
            .description("Saved route point for resuming.")
            .visible(this::showNormalResumeProgressSettings)
            .defaultValue(SweepRoute.NEXT_CIRCLE)
            .build());

    // Origin Chunk X
    private final Setting<Integer> lastOriginX = sgRestart.add(new RestartIntSetting.Builder()
            .name("Origin Chunk X")
            .description("Saved origin chunk X for resuming.")
            .visible(this::showNormalResumeProgressSettings)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .noSlider()
            .build());

    // Origin Chunk Z
    private final Setting<Integer> lastOriginZ = sgRestart.add(new RestartIntSetting.Builder()
            .name("Origin Chunk Z")
            .description("Saved origin chunk Z for resuming.")
            .visible(this::showNormalResumeProgressSettings)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .noSlider()
            .build());

    private final Setting<Boolean> resetRestartData = sgRestart.add(new RestartButtonSetting.Builder()
            .name("Reset Restart Data")
            .description("Clears saved Normal Scan restart data.")
            .buttonLabel("Reset")
            .action(this::resetNormalRestartData)
            .visible(this::isNormalScan)
            .build());

// Render distance setting
    public final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
            .name("Render Distance")
            .description("Maximum route render distance in chunks.")
            .defaultValue(128)
            .min(6)
            .max(256)
            .sliderMin(6)
            .sliderMax(256)
            .visible(this::isNormalScan)
            .build());

// Render height setting
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

// Shape mode setting
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("Shape Mode")
            .description("Route rendering shape mode.")
            .defaultValue(ShapeMode.Both)
            .visible(this::isNormalScan)
            .build());

// Preload rings setting
    private final Setting<Integer> preloadCircles = sgRender.add(new IntSetting.Builder()
            .name("Preload Rings")
            .description("Number of scan rings to prepare ahead of time.")
            .defaultValue(10)
            .min(1)
            .max(20)
            .sliderMin(1)
            .sliderMax(20)
            .visible(this::isNormalScan)
            .build());

// Target chunk color settings
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

// Current path chunk color settings
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

    private final Setting<SettingColor> resumeCheckpointSideColor = sgRender.add(new ColorSetting.Builder()
            .name("Resume Checkpoint Side Color")
            .description("Saved checkpoint fill color while returning to it.")
            .defaultValue(new SettingColor(224, 176, 255, 95))
            .visible(() -> isNormalScan() && (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
            .build());

    private final Setting<SettingColor> resumeCheckpointLineColor = sgRender.add(new ColorSetting.Builder()
            .name("Resume Checkpoint Line Color")
            .description("Saved checkpoint outline color while returning to it.")
            .defaultValue(new SettingColor(224, 176, 255, 205))
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

    private boolean showNormalResumeProgressSettings() {
        return isNormalScan() && lastBegin.get();
    }

    @Override
    public void onActivate() {
        migrateLegacyContainerRecords();
        activeScanMethod = scanMethod.get();
        setScanForwardKey(false);
        scanStartPending = true;
        normalDebugTicks = 0;
        normalDebugState = "INACTIVE";
        setNormalDebugState("ACTIVATED", "selectedMethod=" + activeScanMethod);
        if (mc.player != null && mc.world != null) initializeActiveScan();
    }

    private void initializeActiveScan() {
        if (!scanStartPending || mc.player == null || mc.world == null) return;
        if (!isScanStartReady()) {
            setNormalDebugState("WAITING_START_READY", describeStartReadiness());
            return;
        }

        scanStartPending = false;
        recordedContainerChunks.clear();
        recordedThrownPearls.clear();
        createdWaypointPositions.clear();
        warnedMissingLoadedChunks.clear();
        warnedNormalDebugStates.clear();
        nextWaypointNumber = 1;
        nextPearlWaypointNumber = 1;
        warnedEmptyContainerBlocks = false;
        warnedContainerScanUnavailable = false;
        validateXaeroWaypointSetting();
        warnIfUnsafeScanHeight();
        if (activeScanMethod == ScanMethod.SPIRAL) {
            startSpiralScan();
            return;
        }

        turnDelayTimer = 0;
        movementViewState.clear();
        scanSprintState.clear();
        lastCompletedNormalRing = -1;
        resumeCheckpointChunk = null;
        boolean resumed = false;
        if (lastBegin.get()) resumed = restoreNormalScanProgress();
        if (!resumed) {
            lastBegin.set(false);
            currentCircle = 0;
            currentPath = SweepRoute.NEXT_CIRCLE;
            originChunk = mc.player.getChunkPos();
        }

        visitedChunks.clear();
        targetChunk = null;
        if (resumed) {
            infoKey("message.wavexin.base_finder.normal_resumed", "Resumed normal scan at ring %d, route %s.", currentCircle, WaveXinI18n.enumLabelOr(currentPath, "Unknown route"));
        } else {
            infoKey("message.wavexin.base_finder.normal_started", "Normal scan started at origin chunk (%d, %d).", originChunk.x, originChunk.z);
        }
        setNormalDebugState("INITIALIZED", "resume=" + resumed);
    }
    private boolean restoreNormalScanProgress() {
        ScanProgressManager.NormalScanProgress progress = new ScanProgressManager.NormalScanProgress(
            lastOriginX.get(),
            lastOriginZ.get(),
            lastChunkX.get(),
            lastChunkZ.get(),
            lastCircle.get(),
            lastPath.get().name(),
            currentServerKey(),
            currentDimensionKey()
        );

        currentCircle = Math.max(0, progress.ring);
        originChunk = new ChunkPos(progress.originX, progress.originZ);
        resumeCheckpointChunk = new ChunkPos(progress.playerX, progress.playerZ);
        currentPath = lastPath.get();
        return true;
    }
    private void syncRestartSettingsFromProgress(ScanProgressManager.NormalScanProgress progress) {
        lastCircle.set(Math.max(0, progress.ring));
        lastChunkX.set(progress.playerX);
        lastChunkZ.set(progress.playerZ);
        lastOriginX.set(progress.originX);
        lastOriginZ.set(progress.originZ);
        try {
            lastPath.set(SweepRoute.valueOf(progress.route));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            lastPath.set(SweepRoute.NEXT_CIRCLE);
        }
    }
    private void resetNormalRestartData() {
        ScanProgressManager.clearNormalProgress();
        lastBegin.set(false);
        lastCircle.set(0);
        lastChunkX.set(0);
        lastChunkZ.set(0);
        lastOriginX.set(0);
        lastOriginZ.set(0);
        lastPath.set(SweepRoute.NEXT_CIRCLE);
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
        if (activeScanMethod == ScanMethod.NORMAL) normalDebugTicks++;
        if (scanStartPending) {
            initializeActiveScan();
            if (scanStartPending) {
                setScanForwardKey(false);
                return;
            }
        }
        if (activeScanMethod == ScanMethod.SPIRAL) return;

        if (activeScanMethod == null) {
            setScanForwardKey(false);
            return;
        }
        if (mc.player == null || mc.world == null) {
            setNormalDebugState("WAITING_PLAYER_OR_WORLD", "playerOrWorldMissing");
            setScanForwardKey(false);
            return;
        }

        if (resumeCheckpointChunk != null) {
            if (!normalDebugState.startsWith("WAITING_RESUME")) {
                setNormalDebugState("RETURNING_TO_CHECKPOINT", "checkpoint=" + chunkDebugLabel(resumeCheckpointChunk));
            }
            if (moveToResumeCheckpoint()) {
                infoKey("message.wavexin.base_finder.normal_checkpoint_reached", "Reached saved Normal Scan checkpoint at chunk (%d, %d).", resumeCheckpointChunk.x, resumeCheckpointChunk.z);
                resumeCheckpointChunk = null;
            }
            return;
        }


        ChunkPos playerChunk = mc.player.getChunkPos();
        visitedChunks.add(playerChunk);
        recordLoadedContainerChunksNear(playerChunk);

        if (currentCircle > circleLimit.get()) {
            setNormalDebugState("COMPLETE", "ringLimit=" + circleLimit.get());
            setScanForwardKey(false);
            infoKey("message.wavexin.base_finder.scan_complete", "Scan complete.");
            toggle();
            return;
        }

        if (currentPath == SweepRoute.NEXT_CIRCLE) {
            if (currentCircle > 0 && currentCircle != lastCompletedNormalRing) {
                infoKey("message.wavexin.base_finder.normal_ring_completed", "Completed normal scan ring %d.", currentCircle);
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
            setNormalDebugState("AT_TARGET", "turnDelay=" + turnDelayTimer);
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
            setNormalDebugState(targetChunk == null ? "NO_TARGET" : "TURN_DELAY", "turnDelay=" + turnDelayTimer);
            setScanForwardKey(false);
            return;
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d targetPos = new Vec3d(targetChunk.getStartX() + 8, mc.player.getY(), targetChunk.getStartZ() + 8);

        double deltaX = targetPos.x - playerPos.x;
        double deltaZ = targetPos.z - playerPos.z;

        double distance2D = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        if (distance2D < 1.0) {
            setNormalDebugState("WITHIN_TARGET_BLOCK", "distance=" + distance2D);
            setScanForwardKey(false);
            return;
        }

        targetYaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        applyNormalMovementYaw(targetYaw);

        if (waitChunkLoad.get()) {
            if (!areNeighborChunksLoaded(targetYaw)) {
                ChunkPos missingChunk = findFirstUnloadedNeighborChunk(targetYaw);
                setNormalDebugState("WAITING_NEIGHBOR_CHUNKS", "firstMissing=" + chunkDebugLabel(missingChunk)
                    + ",waitRadius=" + getChunkWaitRadius());
                setScanForwardKey(false);
                return;
            }

            int chunkX = (int) (mc.player.getX() / 16);
            int chunkZ = (int) (mc.player.getZ() / 16);
            if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                setNormalDebugState("WAITING_CURRENT_CHUNK", "checkedChunk=(" + chunkX + "," + chunkZ + ")");
                setScanForwardKey(false);
                return;
            }
        }

        if (moveSpeed.get() > 1.0) {
            mc.player.setSprinting(true);
        }

        setNormalDebugState("MOVING", "distance=" + distance2D);
        setScanForwardKey(true);
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (activeScanMethod != ScanMethod.NORMAL) return;
        restoreNormalViewYaw();
        if (normalDebugTicks > 0 && normalDebugTicks % NORMAL_DEBUG_HEARTBEAT_TICKS == 0) {
            logNormalDebugSnapshot("HEARTBEAT", "stateUnchanged");
        }
    }

    @Override
    public void onDeactivate() {
        setScanForwardKey(false);
        restoreNormalViewYaw();
        scanStartPending = false;
        ScanMethod stoppedScanMethod = activeScanMethod;
        if (stoppedScanMethod == ScanMethod.NORMAL) logNormalDebugSnapshot("DEACTIVATE", "moduleDisabled");
        ScanProgressManager.NormalScanProgress savedProgress = stoppedScanMethod == ScanMethod.NORMAL ? saveNormalScanProgress() : null;
        activeScanMethod = null;
        recordedThrownPearls.clear();

        if (stoppedScanMethod == ScanMethod.SPIRAL) {
            saveSpiralProgress();
            clearSpiralState();
            return;
        }

        boolean scanCompleted = currentCircle > circleLimit.get();
        boolean preservedResumeCheckpoint = resumeCheckpointChunk != null;

        if (!scanCompleted) {
            if (savedProgress != null && preservedResumeCheckpoint) {
                infoKey("message.wavexin.base_finder.normal_stopped_checkpoint_preserved", "Normal scan stopped. Last valid checkpoint was preserved: (%d, %d), ring %d, route %s.", savedProgress.playerX, savedProgress.playerZ, savedProgress.ring, WaveXinI18n.enumLabelOr(currentPath, "Unknown route"));
            } else if (savedProgress != null) {
                infoKey("message.wavexin.base_finder.normal_stopped_checkpoint", "Normal scan stopped. Saved checkpoint: (%d, %d), ring %d, route %s.", savedProgress.playerX, savedProgress.playerZ, savedProgress.ring, WaveXinI18n.enumLabelOr(currentPath, "Unknown route"));
            } else {
                infoKey("message.wavexin.base_finder.normal_stopped", "Normal scan stopped.");
            }
        }
// Clear chunk data
        visitedChunks.clear();

// Reset state
        originChunk = null;
        targetChunk = null;
        resumeCheckpointChunk = null;
        currentPath = null;
        lastCompletedNormalRing = -1;
        turnDelayTimer = 0;
        activeScanMethod = null;
    }

    private ScanProgressManager.NormalScanProgress saveNormalScanProgress() {
        if (originChunk == null || currentPath == null || mc.player == null) return null;

        ChunkPos checkpointChunk = resumeCheckpointChunk != null ? resumeCheckpointChunk : mc.player.getChunkPos();
        ScanProgressManager.NormalScanProgress progress = new ScanProgressManager.NormalScanProgress(
            originChunk.x,
            originChunk.z,
            checkpointChunk.x,
            checkpointChunk.z,
            currentCircle,
            currentPath.name()
        );
        ScanProgressManager.saveNormalProgress(progress);
        syncRestartSettingsFromProgress(progress);
        return progress;
    }

    private void warnIfUnsafeScanHeight() {
        if (mc.player == null || mc.world == null || isSafeScanHeight()) return;
        errorKey("error.wavexin.safe_flight_height", "Recommended to use above each dimension height limit: Nether (Y > 128), Overworld (Y > 320), End (Y > 256)");
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
        if (mc.player.getChunkPos().equals(resumeCheckpointChunk)) {
            setScanForwardKey(false);
            setNormalDebugState("AT_RESUME_CHECKPOINT", "checkpoint=" + chunkDebugLabel(resumeCheckpointChunk));
            return true;
        }

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
        applyNormalMovementYaw(targetYaw);

        if (waitChunkLoad.get()) {
            if (!areNeighborChunksLoaded(targetYaw)) {
                ChunkPos missingChunk = findFirstUnloadedNeighborChunk(targetYaw);
                setNormalDebugState("WAITING_RESUME_NEIGHBOR_CHUNKS", "firstMissing=" + chunkDebugLabel(missingChunk)
                    + ",waitRadius=" + getChunkWaitRadius());
                setScanForwardKey(false);
                return false;
            }

            int chunkX = (int) (mc.player.getX() / 16);
            int chunkZ = (int) (mc.player.getZ() / 16);
            if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                setNormalDebugState("WAITING_RESUME_CURRENT_CHUNK", "checkedChunk=(" + chunkX + "," + chunkZ + ")");
                setScanForwardKey(false);
                return false;
            }
        }

        setNormalDebugState("RETURNING_TO_CHECKPOINT", "checkpoint=" + chunkDebugLabel(resumeCheckpointChunk));
        if (moveSpeed.get() > 1.0) mc.player.setSprinting(true);
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

    private void applyMovementYaw(float yaw, boolean keepVisible) {
        if (mc.player == null || mc.world == null) return;
        movementViewState.captureIfNeeded(mc.player, mc.world, mc.player.getYaw(), mc.player.getPitch(), mc.player.headYaw, mc.player.bodyYaw, !keepVisible);
        mc.player.setYaw(yaw);
        mc.player.headYaw = yaw;
        mc.player.bodyYaw = yaw;
    }


    private void restoreMovementViewAfterTick() {
        if (movementViewState.shouldRestoreAfterTick()) restoreMovementView();
    }

    private void restoreMovementView() {
        BaseFinderStateLogic.Snapshot snapshot = movementViewState.consumeRestore(mc.player, mc.world);
        if (snapshot == null || mc.player == null) return;
        mc.player.setYaw(snapshot.yaw());
        mc.player.setPitch(snapshot.pitch());
        mc.player.headYaw = snapshot.headYaw();
        mc.player.bodyYaw = snapshot.bodyYaw();
    }

    private void forceScanSprint() {
        if (mc.player == null || mc.world == null) return;
        scanSprintState.captureIfNeeded(mc.player, mc.world, mc.player.isSprinting());
        mc.player.setSprinting(true);
    }

    private void setScanSprint(boolean shouldForce) {
        if (shouldForce) forceScanSprint();
        else restoreScanSprint();
    }

    private void restoreScanSprint() {
        Boolean sprinting = scanSprintState.consumeRestore(mc.player, mc.world);
        if (sprinting != null && mc.player != null) mc.player.setSprinting(sprinting);
    }

    private void setScanForwardKeyOnly(boolean pressed) {
        if (pressed) {
            if (mc.options != null) mc.options.forwardKey.setPressed(true);
            forcingForward = true;
            return;
        }

        if (mc.options != null && forcingForward) mc.options.forwardKey.setPressed(false);
        forcingForward = false;
    }

    private void setScanForwardKey(boolean pressed) {
        setScanForwardKeyOnly(pressed);
        if (pressed) return;

        restoreScanSprint();
        restoreMovementView();
    }


    private void setNormalDebugState(String state, String detail) {
        if (activeScanMethod != ScanMethod.NORMAL || state.equals(normalDebugState)) return;
        normalDebugState = state;
        logNormalDebugSnapshot("STATE", detail);
    }

    private void logNormalDebugSnapshot(String event, String detail) {
        if (!shouldLogNormalDebugSnapshot(event)) return;
        if (!"DEACTIVATE".equals(event) && !warnedNormalDebugStates.add(normalDebugState)) return;

        String playerState;
        if (mc.player == null) {
            playerState = "player=null";
        } else {
            Vec3d velocity = mc.player.getVelocity();
            ChunkPos playerChunk = mc.player.getChunkPos();
            boolean currentChunkLoaded = mc.world != null && mc.world.getChunkManager().isChunkLoaded(playerChunk.x, playerChunk.z);
            boolean forwardPressed = mc.options != null && mc.options.forwardKey.isPressed();
            playerState = "pos=(" + mc.player.getX() + "," + mc.player.getY() + "," + mc.player.getZ() + ")"
                + " playerChunk=" + chunkDebugLabel(playerChunk)
                + " currentChunkLoaded=" + currentChunkLoaded
                + " velocity=(" + velocity.x + "," + velocity.y + "," + velocity.z + ")"
                + " playerYaw=" + mc.player.getYaw()
                + " sprinting=" + mc.player.isSprinting()
                + " forwardPressed=" + forwardPressed
                + " forcingForward=" + forcingForward
                + " age=" + mc.player.age;
        }

        WaveXinAddon.LOG.warn(
            "[BaseFinderDebug] event={} state={} detail={} method={} pending={} origin={} target={} resume={} ring={} route={} turnDelay={} targetYaw={} waitChunks={} chunkRadius={} waitDistance={} waitRadius={} viewDistance={} clampedViewDistance={} screen={} {}",
            event,
            normalDebugState,
            detail,
            activeScanMethod,
            scanStartPending,
            chunkDebugLabel(originChunk),
            chunkDebugLabel(targetChunk),
            chunkDebugLabel(resumeCheckpointChunk),
            currentCircle,
            currentPath,
            turnDelayTimer,
            targetYaw,
            waitChunkLoad.get(),
            chunkLoadRadius.get(),
            chunkWaitDistance.get(),
            getChunkWaitRadius(),
            mc.options == null ? -1 : mc.options.getViewDistance().getValue(),
            mc.options == null ? -1 : mc.options.getClampedViewDistance(),
            mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName(),
            playerState
        );
    }

    private boolean shouldLogNormalDebugSnapshot(String event) {
        return BaseFinderStateLogic.shouldLogNormalDebugSnapshot(event, normalDebugState);
    }

    private String describeStartReadiness() {
        ChunkPos playerChunk = mc.player.getChunkPos();
        boolean loaded = mc.world.getChunkManager().isChunkLoaded(playerChunk.x, playerChunk.z);
        return "playerChunk=" + chunkDebugLabel(playerChunk) + ",loaded=" + loaded + ",age=" + mc.player.age;
    }

    private String chunkDebugLabel(ChunkPos chunk) {
        return chunk == null ? "null" : "(" + chunk.x + "," + chunk.z + ")";
    }

    private String currentServerKey() {
        if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
            return mc.getCurrentServerEntry().address;
        }

        return mc.isInSingleplayer() ? "singleplayer" : "unknown";
    }

    private String currentDimensionKey() {
        return mc.world == null ? "unknown" : mc.world.getRegistryKey().getValue().toString();
    }

    private Vec3d getChunkCenter(ChunkPos chunk) {
        return new Vec3d(chunk.getStartX() + 8, mc.player.getY(), chunk.getStartZ() + 8);
    }

    private double horizontalDistanceTo(Vec3d target) {
        double deltaX = target.x - mc.player.getX();
        double deltaZ = target.z - mc.player.getZ();
        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }

    private float yawTo(Vec3d target) {
        double deltaX = target.x - mc.player.getX();
        double deltaZ = target.z - mc.player.getZ();
        return (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
    }

    private ChunkPos findFirstUnloadedNeighborChunk(float yaw) {
        if (mc.player == null || mc.world == null) return null;

        ChunkPos currentChunk = mc.player.getChunkPos();
        int waitRadius = getChunkWaitRadius();
        int stepX = getForwardChunkStepX(yaw);
        int stepZ = getForwardChunkStepZ(yaw);

        for (int step = 1; step <= waitRadius; step++) {
            int chunkX = currentChunk.x + stepX * step;
            int chunkZ = currentChunk.z + stepZ * step;
            if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) return new ChunkPos(chunkX, chunkZ);
        }

        return null;
    }

    private int getForwardChunkStepX(float yaw) {
        double radians = Math.toRadians(yaw);
        double x = -Math.sin(radians);
        double z = Math.cos(radians);
        return Math.abs(x) >= Math.abs(z) ? (x > 0 ? 1 : -1) : 0;
    }

    private int getForwardChunkStepZ(float yaw) {
        double radians = Math.toRadians(yaw);
        double x = -Math.sin(radians);
        double z = Math.cos(radians);
        return Math.abs(z) > Math.abs(x) ? (z > 0 ? 1 : -1) : 0;
    }

    private int getChunkWaitRadius() {
        return Math.min(Math.max(0, chunkLoadRadius.get()), Math.max(0, chunkWaitDistance.get()));
    }

    private boolean isScanStartReady() {
        ChunkPos playerChunk = mc.player.getChunkPos();
        if (!mc.world.getChunkManager().isChunkLoaded(playerChunk.x, playerChunk.z)) return false;

        if (playerChunk.x != 0 || playerChunk.z != 0) return true;
        if (Math.abs(mc.player.getX()) >= 16.0 || Math.abs(mc.player.getZ()) >= 16.0) return true;

        return mc.isInSingleplayer() || mc.player.age > 100;
    }

    private boolean areNeighborChunksLoaded(float yaw) {
        ChunkPos currentChunk = mc.player.getChunkPos();
        int waitRadius = getChunkWaitRadius();
        int stepX = getForwardChunkStepX(yaw);
        int stepZ = getForwardChunkStepZ(yaw);

        for (int step = 1; step <= waitRadius; step++) {
            int chunkX = currentChunk.x + stepX * step;
            int chunkZ = currentChunk.z + stepZ * step;
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
    private void recordLoadedContainerChunksNear(ChunkPos center) {
        if (mc.world == null) {
            if (!warnedContainerScanUnavailable) {
                WaveXinAddon.LOG.warn("[BaseFinderDebug] Skipped container scan because world is null. method={} center={}", activeScanMethod, chunkDebugLabel(center));
                warnedContainerScanUnavailable = true;
            }
            return;
        }

        detectThrownPearlsIfEnabled();

        int radius = getContainerScanRadius();
        for (int chunkX = center.x - radius; chunkX <= center.x + radius; chunkX++) {
            for (int chunkZ = center.z - radius; chunkZ <= center.z + radius; chunkZ++) {
                if (mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                    recordContainerChunkIfNeeded(new ChunkPos(chunkX, chunkZ));
                }
            }
        }
    }

    private int getContainerScanRadius() {
        int radius = Math.max(1, getChunkWaitRadius());
        if (mc.options == null) return radius;
        return Math.min(radius, Math.max(1, mc.options.getViewDistance().getValue()));
    }

    private void recordContainerChunkIfNeeded(ChunkPos chunkPos) {
        long key = chunkPos.toLong();
        if (recordedContainerChunks.contains(key)) return;

        WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false);
        if (chunk == null) {
            if (warnedMissingLoadedChunks.add(key)) {
                WaveXinAddon.LOG.warn("[BaseFinderDebug] Loaded container candidate had no WorldChunk. chunk={} playerChunk={} method={} resume={}", chunkDebugLabel(chunkPos), chunkDebugLabel(mc.player.getChunkPos()), activeScanMethod, chunkDebugLabel(resumeCheckpointChunk));
            }
            return;
        }

        List<BlockEntityType<?>> selectedContainerBlocks = containerBlocks.get();
        if (selectedContainerBlocks == null || selectedContainerBlocks.isEmpty()) {
            if (!warnedEmptyContainerBlocks) {
                WaveXinAddon.LOG.warn("[BaseFinderDebug] Container scan skipped because no container block types are selected. threshold={} method={}", containerThreshold.get(), activeScanMethod);
                warnedEmptyContainerBlocks = true;
            }
            return;
        }

        int count = 0;
        BlockPos firstPos = null;

        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!selectedContainerBlocks.contains(blockEntity.getType())) continue;

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
        warningKey("warning.wavexin.base_finder.base_found", "(highlight)(bold)Base found! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default) | Containers: (highlight)%d(default)",
            chunkPos.x, chunkPos.z, recordPos.getX(), recordPos.getY(), recordPos.getZ(), count);
    }

    private void detectThrownPearlsIfEnabled() {
        if (!detectThrownPearls.get() || mc.world == null) return;

        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() != EntityType.ENDER_PEARL) continue;
            UUID uuid = entity.getUuid();
            if (!recordedThrownPearls.add(uuid)) continue;

            BlockPos pearlPos = entity.getBlockPos();
            ChunkPos pearlChunk = new ChunkPos(pearlPos);
            announceThrownPearl(pearlChunk, pearlPos);
            createPearlXaeroWaypointIfEnabled(pearlPos);
        }
    }

    private void announceThrownPearl(ChunkPos chunkPos, BlockPos pos) {
        warningKey("warning.wavexin.base_finder.pearl_found", "(highlight)(bold)Thrown pearl detected! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default)",
            chunkPos.x, chunkPos.z, pos.getX(), pos.getY(), pos.getZ());
    }

    private boolean hasReachedWaypointLimit(BlockPos candidate) {
        int radiusBlocks = waypointLimitRadius.get() * 16;
        int nearby = 0;
        for (BlockPos existing : createdWaypointPositions) {
            if (Math.abs(existing.getX() - candidate.getX()) > radiusBlocks || Math.abs(existing.getZ() - candidate.getZ()) > radiusBlocks) continue;
            if (++nearby >= maximumWaypointsPerArea.get()) {
                WaveXinAddon.LOG.warn("[BaseFinderDebug] Skipped Xaero waypoint because area limit was reached. candidate=({}, {}) radiusChunks={} limit={}", candidate.getX(), candidate.getZ(), waypointLimitRadius.get(), maximumWaypointsPerArea.get());
                infoKey("message.wavexin.base_finder.xaero_area_limit", "Skipped Xaero waypoint near (%d, %d): area limit of %d reached.", candidate.getX(), candidate.getZ(), maximumWaypointsPerArea.get());
                return true;
            }
        }
        return false;
    }

    private int getXaeroWaypointColorId() {
        XaeroWaypointColor color = xaeroWaypointColor.get();
        return color == XaeroWaypointColor.RANDOM ? ThreadLocalRandom.current().nextInt(16) : color.colorId;
    }

    private void sendXaeroCreatedMessage(String name, int colorId) {
        Color color = getXaeroWaypointDisplayColor(colorId);
        int rgb = ((color.r & 0xFF) << 16) | ((color.g & 0xFF) << 8) | (color.b & 0xFF);
        Text message = Text.literal(WaveXinI18n.tr("message.wavexin.base_finder.xaero_created", "Created Xaero waypoint: %s", ""))
            .append(Text.literal(name).setStyle(Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(rgb))));

        ChatUtils.forceNextPrefixClass(getClass());
        ChatUtils.sendMsg(message);
    }

    private static Color getXaeroWaypointDisplayColor(int colorId) {
        for (XaeroWaypointColor color : XaeroWaypointColor.values()) {
            if (color.colorId == colorId) return color.displayColor;
        }
        return XaeroWaypointColor.RANDOM.displayColor;
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
            WaveXinAddon.LOG.error("[BaseFinderDebug] Failed to save container chunk record. path={} chunk={} recordPos={} playerPos={} count={}", CONTAINER_RECORD_PATH, chunkDebugLabel(chunkPos), recordPos, playerPos, count, e);
            errorKey("error.wavexin.base_finder.record_save_failed", "Failed to save container chunk record: %s", e.getMessage());
        }
    }

    private void createXaeroWaypointIfEnabled(BlockPos pos) {
        if (!validateXaeroWaypointSetting()) return;
        if (hasReachedWaypointLimit(pos)) return;

        String name = xaeroWaypointPrefix.get() + nextWaypointNumber + xaeroWaypointSuffix.get();
        String initials = makeWaypointInitials(name);
        if (createXaeroWaypoint(pos, name, initials)) {
            createdWaypointPositions.add(pos.toImmutable());
            nextWaypointNumber++;
        }
    }

    private void createPearlXaeroWaypointIfEnabled(BlockPos pos) {
        if (!xaeroWaypoints.get() || !recordThrownPearls.get()) return;
        if (!validateXaeroWaypointSetting()) return;

        int number = nextPearlWaypointNumber;
        String name = BaseFinderStateLogic.pearlWaypointName(number);
        String initials = BaseFinderStateLogic.pearlWaypointAlias(number);
        if (createXaeroWaypoint(pos, name, initials)) {
            nextPearlWaypointNumber++;
        }
    }

    private boolean createXaeroWaypoint(BlockPos pos, String name, String initials) {
        try {
            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Object currentSession = sessionClass.getMethod("getCurrentSession").invoke(null);
            if (currentSession == null) {
                WaveXinAddon.LOG.warn("[BaseFinderDebug] Xaero waypoint skipped because current session is null. pos={} method={} name={}", pos, activeScanMethod, name);
                warningKey("warning.wavexin.base_finder.xaero_session_not_ready", "Xaero's Minimap session is not ready. Record saved without a waypoint.");
                return false;
            }

            Object processor = currentSession.getClass().getMethod("getMinimapProcessor").invoke(currentSession);
            Object minimapSession = processor.getClass().getMethod("getSession").invoke(processor);
            Object worldManager = minimapSession.getClass().getMethod("getWorldManager").invoke(minimapSession);
            Object currentWorld = worldManager.getClass().getMethod("getCurrentWorld").invoke(worldManager);
            if (currentWorld == null) {
                WaveXinAddon.LOG.warn("[BaseFinderDebug] Xaero waypoint skipped because current waypoint world is null. pos={} method={} name={}", pos, activeScanMethod, name);
                warningKey("warning.wavexin.base_finder.xaero_world_not_ready", "Xaero current waypoint world is not ready. Record saved without a waypoint.");
                return false;
            }

            Object waypointSet = currentWorld.getClass().getMethod("getCurrentWaypointSet").invoke(currentWorld);
            if (waypointSet == null) {
                WaveXinAddon.LOG.warn("[BaseFinderDebug] Xaero waypoint skipped because current waypoint set is null. pos={} method={} name={}", pos, activeScanMethod, name);
                warningKey("warning.wavexin.base_finder.xaero_set_not_ready", "Xaero current waypoint set is not ready. Record saved without a waypoint.");
                return false;
            }

            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Constructor<?> constructor = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
            int colorId = getXaeroWaypointColorId();
            Object waypoint = constructor.newInstance(pos.getX(), pos.getY(), pos.getZ(), name, initials, colorId);
            Method addMethod = waypointSet.getClass().getMethod("add", waypointClass);
            addMethod.invoke(waypointSet, waypoint);

            Object waypointSession = minimapSession.getClass().getMethod("getWaypointSession").invoke(minimapSession);
            waypointSession.getClass().getMethod("setSetChangedTime", long.class).invoke(waypointSession, System.currentTimeMillis());
            sendXaeroCreatedMessage(name, colorId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            WaveXinAddon.LOG.warn("[BaseFinderDebug] Failed to create Xaero waypoint. pos={} method={} name={}", pos, activeScanMethod, name, e);
            warningKey("warning.wavexin.base_finder.xaero_create_failed", "Failed to create Xaero waypoint: %s", e.getMessage());
            return false;
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
        WaveXinAddon.LOG.warn("[BaseFinderDebug] Xaero waypoint support is unavailable. Disabling Xaero Waypoints for this module instance.");
        warningKey("warning.wavexin.base_finder.xaero_missing", "Xaero's Minimap was not detected. Xaero Waypoints has been disabled, but container recording will continue.");
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
        if (spiralLockView.get()) applySpiralRotation(spiralDirection.yaw);
        saveSpiralProgress();

        if (spiralDebug.get()) {
            infoKey("message.wavexin.base_finder.spiral_started", "Spiral scan started at chunk (%d, %d).", startChunk.x, startChunk.z);
            infoKey("message.wavexin.base_finder.spiral_next_target", "Next spiral target: (%d, %d).", spiralTargetChunk.x, spiralTargetChunk.z);
        }
    }

    private void resumeSpiralScan() {
        ScanProgressManager.ScanProgress progress = ScanProgressManager.loadProgress();
        if (progress == null) {
            if (spiralDebug.get()) infoKey("message.wavexin.base_finder.spiral_no_progress", "No saved spiral progress was found. Starting from the current chunk.");
            startNewSpiralScan(mc.player.getChunkPos());
            return;
        }

        if (progress.chunkStep != spiralChunkStep.get()) {
            infoKey("message.wavexin.base_finder.spiral_step_resumed", "Saved spiral chunk step is %d. Using it to resume this scan.", progress.chunkStep);
            spiralChunkStep.set(progress.chunkStep);
        }

        applySpiralProgress(progress);
        calibrateSpiralDirection();

        if (spiralDebug.get()) {
            infoKey("message.wavexin.base_finder.spiral_resumed", "Resumed spiral scan from chunk (%d, %d) after %d segments.", spiralStartChunk.x, spiralStartChunk.z, spiralSegments);
            infoKey("message.wavexin.base_finder.spiral_next_target", "Next spiral target: (%d, %d).", spiralTargetChunk.x, spiralTargetChunk.z);
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
            warningKey("warning.wavexin.base_finder.spiral_too_far", "Current position is too far from the calculated spiral route.");
            infoKey("message.wavexin.base_finder.spiral_recommended_chunk", "Recommended chunk: (%d, %d).", corner.x, corner.z);
            infoKey("message.wavexin.base_finder.spiral_recommended_block", "Recommended block position: (%d, %d).", corner.x * 16, corner.z * 16);
            toggle();
            return;
        }

        applySpiralProgress(progress);
        calibrateSpiralDirection();

        if (spiralDebug.get()) {
            infoKey("message.wavexin.base_finder.spiral_calculated", "Calculated spiral progress after %d segments.", spiralSegments);
            infoKey("message.wavexin.base_finder.spiral_next_target", "Next spiral target: (%d, %d).", spiralTargetChunk.x, spiralTargetChunk.z);
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
        if (!spiralLockView.get()) {
            spiralNeedsInitialRotation = false;
            spiralRotating = false;
            return;
        }

        if (spiralDirection.isFacingDirection(mc.player.getYaw())) {
            applySpiralRotation(spiralDirection.yaw);
            return;
        }

        spiralNeedsInitialRotation = true;
        spiralTargetYaw = spiralDirection.yaw;
        spiralRotating = true;
        if (spiralDebug.get()) infoKey("message.wavexin.base_finder.spiral_calibrating", "Calibrating view toward %s.", WaveXinI18n.enumLabelOr(spiralDirection, "Unknown direction"));
    }

    private void runSpiralScan() {
        if (mc.player == null || mc.world == null || spiralTargetChunk == null) {
            setScanForwardKey(false);
            return;
        }

        cancelSpiralRotationIfLockViewDisabled();

        if (spiralPauseOnScreen.get() && mc.currentScreen != null) {
            setScanForwardKey(false);
            return;
        }

        ChunkPos playerChunk = mc.player.getChunkPos();
        recordLoadedContainerChunksNear(playerChunk);

        if (spiralMaximumSegments.get() > 0 && spiralSegments >= spiralMaximumSegments.get()) {
            setScanForwardKey(false);
            infoKey("message.wavexin.base_finder.spiral_complete", "Maximum segments reached. Spiral scan complete.");
            toggle();
            return;
        }

        if (spiralNeedsInitialRotation && spiralRotating) {
            smoothSpiralRotation();
            if (spiralRotating) {
                setScanForwardKeyOnly(false);
                restoreScanSprint();
                return;
            }
        }

        if (spiralRotating) {
            smoothSpiralRotation();
            if (spiralRotating) {
                setScanForwardKeyOnly(false);
                restoreScanSprint();
                return;
            }
        }

        if (spiralLockView.get() && !spiralRotating && !spiralNeedsInitialRotation) {
            applySpiralRotation(spiralDirection.yaw);
        }

        handleSpiralAutoWalk();


        if (!hasReachedSpiralTarget()) return;
        boolean smoothRotation = advanceSpiralDirection();
        updateSpiralTarget();
        saveSpiralProgress();
        if (smoothRotation) {
            spiralTargetYaw = spiralDirection.yaw;
            spiralRotating = true;
        }

        if (spiralDebug.get()) {
            infoKey("message.wavexin.base_finder.spiral_direction", "Spiral direction: %s. Next target: (%d, %d).", WaveXinI18n.enumLabelOr(spiralDirection, "Unknown direction"), spiralTargetChunk.x, spiralTargetChunk.z);
        }
    }

    private void cancelSpiralRotationIfLockViewDisabled() {
        if (!BaseFinderStateLogic.shouldCancelSpiralRotation(spiralLockView.get(), spiralRotating, spiralNeedsInitialRotation)) return;

        spiralRotating = false;
        spiralNeedsInitialRotation = false;
        restoreMovementView();
    }

    private void handleSpiralAutoWalk() {
        boolean lockView = spiralLockView.get();
        if (!spiralAutoWalk.get()) {
            setScanForwardKeyOnly(false);
            restoreScanSprint();
            if (!lockView) restoreMovementView();
            return;
        }

        Vec3d targetCenter = getChunkCenter(spiralTargetChunk);
        double distance = horizontalDistanceTo(targetCenter);
        boolean shouldMove = distance >= 1.0;
        if (shouldMove) applyMovementYaw(yawTo(targetCenter), lockView);

        setScanForwardKeyOnly(shouldMove);
        setScanSprint(shouldMove && spiralSprint.get());
        if (!shouldMove && !lockView) restoreMovementView();
    }

    private boolean hasReachedSpiralTarget() {
        return mc.player.getChunkPos().equals(spiralTargetChunk)
            && horizontalDistanceTo(getChunkCenter(spiralTargetChunk)) < 1.0;
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
        }
        return false;
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
            spiralNeedsInitialRotation = false;
            if (spiralDebug.get()) infoKey("message.wavexin.base_finder.spiral_rotation_complete", "Spiral rotation complete.");
        } else {
            applySpiralRotation(currentYaw + Math.signum(difference) * rotationSpeed);
        }
    }

    private void applySpiralRotation(float yaw) {
        applyMovementYaw(yaw, true);
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

                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                boolean resumeCheckpoint = resumeCheckpointChunk != null && resumeCheckpointChunk.equals(chunk);
                boolean currentPathChunk = isChunkOnCurrentNormalPath(chunkX, chunkZ);
                boolean targetPreviewChunk = isChunkOnPreparedNormalRing(chunkX, chunkZ);
                if (!resumeCheckpoint && !currentPathChunk && !targetPreviewChunk) continue;

                SettingColor sideColor;
                SettingColor lineColor;

                if (resumeCheckpoint) {
                    sideColor = resumeCheckpointSideColor.get();
                    lineColor = resumeCheckpointLineColor.get();
                } else if (currentPathChunk) {
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

// Velocity helpers

    /**
     * Gets the current X velocity.
     */
    private double getX() {
        return mc.player.getVelocity().x;
    }

    /**
     * Gets the current Y velocity.
     */
    private double getY() {
        return mc.player.getVelocity().y;
    }

    /**
     * Gets the current Z velocity.
     */
    private double getZ() {
        return mc.player.getVelocity().z;
    }

    /**
     * Sets the current X velocity.
     * Keeps Y and Z velocity unchanged.
     */
    private void setX(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(f, currentVel.y, currentVel.z);
        mc.player.setVelocity(newVel);
    }

    /**
     * Sets the current Y velocity.
     * Keeps X and Z velocity unchanged.
     */
    private void setY(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(currentVel.x, f, currentVel.z);
        mc.player.setVelocity(newVel);
    }

    /**
     * Sets the current Z velocity.
     * Keeps X and Y velocity unchanged.
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

    private static class RestartIntSetting extends Setting<Integer> {
        public final int min, max;
        public final int sliderMin, sliderMax;
        public final boolean noSlider;
        private final List<WeakReference<WIntEdit>> widgets = new ArrayList<>();

        private RestartIntSetting(String name, String description, int defaultValue, Consumer<Integer> onChanged, Consumer<Setting<Integer>> onModuleActivated, IVisible visible, int min, int max, int sliderMin, int sliderMax, boolean noSlider) {
            super(name, description, defaultValue, onChanged, onModuleActivated, visible);
            this.min = min;
            this.max = max;
            this.sliderMin = sliderMin;
            this.sliderMax = sliderMax;
            this.noSlider = noSlider;
        }

        @Override
        public boolean set(Integer value) {
            boolean changed = super.set(value);
            if (changed) refreshWidgets();
            return changed;
        }

        private void addWidget(WIntEdit widget) {
            widgets.add(new WeakReference<>(widget));
        }

        private void refreshWidgets() {
            widgets.removeIf(reference -> {
                WIntEdit widget = reference.get();
                if (widget == null) return true;
                if (widget.get() != get()) widget.set(get());
                return false;
            });
        }

        @Override
        protected Integer parseImpl(String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        @Override
        protected boolean isValueValid(Integer value) {
            return value != null && value >= min && value <= max;
        }

        @Override
        protected NbtCompound save(NbtCompound tag) {
            tag.putInt("value", get());
            return tag;
        }

        @Override
        protected Integer load(NbtCompound tag) {
            set(tag.getInt("value", 0));
            return get();
        }

        private static class Builder extends SettingBuilder<Builder, Integer, RestartIntSetting> {
            private int min = Integer.MIN_VALUE, max = Integer.MAX_VALUE;
            private int sliderMin = 0, sliderMax = 10;
            private boolean noSlider = false;

            private Builder() {
                super(0);
            }

            public Builder min(int min) {
                this.min = min;
                return this;
            }

            public Builder max(int max) {
                this.max = max;
                return this;
            }

            public Builder sliderMin(int min) {
                this.sliderMin = min;
                return this;
            }

            public Builder sliderMax(int max) {
                this.sliderMax = max;
                return this;
            }

            public Builder noSlider() {
                noSlider = true;
                return this;
            }

            @Override
            public RestartIntSetting build() {
                return new RestartIntSetting(name, description, defaultValue, onChanged, onModuleActivated, visible, min, max, Math.max(sliderMin, min), Math.min(sliderMax, max), noSlider);
            }
        }
    }

    private static class RestartRouteSetting extends Setting<SweepRoute> {
        private final List<WeakReference<WDropdown<SweepRoute>>> widgets = new ArrayList<>();

        private RestartRouteSetting(String name, String description, SweepRoute defaultValue, Consumer<SweepRoute> onChanged, Consumer<Setting<SweepRoute>> onModuleActivated, IVisible visible) {
            super(name, description, defaultValue, onChanged, onModuleActivated, visible);
        }

        @Override
        public boolean set(SweepRoute value) {
            boolean changed = super.set(value);
            if (changed) refreshWidgets();
            return changed;
        }

        private void addWidget(WDropdown<SweepRoute> widget) {
            widgets.add(new WeakReference<>(widget));
            widget.set(get());
        }

        private void refreshWidgets() {
            widgets.removeIf(reference -> {
                WDropdown<SweepRoute> widget = reference.get();
                if (widget == null) return true;
                if (widget.get() != get()) widget.set(get());
                return false;
            });
        }

        @Override
        protected SweepRoute parseImpl(String str) {
            try {
                return SweepRoute.valueOf(str.trim());
            } catch (IllegalArgumentException | NullPointerException ignored) {
                return null;
            }
        }

        @Override
        protected boolean isValueValid(SweepRoute value) {
            return value != null;
        }

        @Override
        protected NbtCompound save(NbtCompound tag) {
            tag.putString("value", get().name());
            return tag;
        }

        @Override
        protected SweepRoute load(NbtCompound tag) {
            parse(tag.getString("value", SweepRoute.NEXT_CIRCLE.name()));
            return get();
        }

        private static class Builder extends SettingBuilder<Builder, SweepRoute, RestartRouteSetting> {
            private Builder() {
                super(SweepRoute.NEXT_CIRCLE);
            }

            @Override
            public RestartRouteSetting build() {
                return new RestartRouteSetting(name, description, defaultValue, onChanged, onModuleActivated, visible);
            }
        }
    }

    private static class RestartButtonSetting extends Setting<Boolean> {
        private final String buttonLabel;
        private final String buttonKey;
        private final String tooltipKey;
        private final Runnable action;

        private RestartButtonSetting(String name, String description, String buttonLabel, Runnable action, Consumer<Boolean> onChanged, Consumer<Setting<Boolean>> onModuleActivated, IVisible visible) {
            super(name, description, false, onChanged, onModuleActivated, visible);
            String segment = WaveXinI18n.keySegment(name);
            this.buttonLabel = buttonLabel;
            this.buttonKey = "button.wavexin.base_finder." + segment + ".label";
            this.tooltipKey = "button.wavexin.base_finder." + segment + ".tooltip";
            this.action = action;
        }

        private void run() {
            if (action != null) action.run();
        }

        @Override
        protected Boolean parseImpl(String str) {
            return false;
        }

        @Override
        protected boolean isValueValid(Boolean value) {
            return true;
        }

        @Override
        protected NbtCompound save(NbtCompound tag) {
            tag.putBoolean("value", false);
            return tag;
        }

        @Override
        protected Boolean load(NbtCompound tag) {
            set(false);
            return false;
        }

        private static class Builder extends SettingBuilder<Builder, Boolean, RestartButtonSetting> {
            private String buttonLabel = "Run";
            private Runnable action;

            private Builder() {
                super(false);
            }

            private Builder buttonLabel(String buttonLabel) {
                this.buttonLabel = buttonLabel;
                return this;
            }

            private Builder action(Runnable action) {
                this.action = action;
                return this;
            }

            @Override
            public RestartButtonSetting build() {
                return new RestartButtonSetting(name, description, buttonLabel, action, onChanged, onModuleActivated, visible);
            }
        }
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
