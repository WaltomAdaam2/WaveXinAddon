package me.waltom.wavexin.modules.litematicaprinter;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class LitematicaPrinter extends WaveXinModule {
    private static final String MINECRAFT_VERSION = "1.21.11";
    private static final int MAX_COORDINATE = 30_000_000;
    private static final int SCREEN_GUARD_TICKS = 10;
    private static final SettingColor MANUAL_SIDE_COLOR = new SettingColor(255, 45, 45, 35);
    private static final SettingColor MANUAL_LINE_COLOR = new SettingColor(255, 70, 70, 220);
    static final int DEFAULT_BLOCKS_PER_TICK = 3;
    static final int DEFAULT_MAXIMUM_PROJECTION_VOLUME = 10_000_000;
    static final int MAXIMUM_PROJECTION_VOLUME = 500_000_000;

    static {
        SettingsWidgetFactory.registerCustomFactory(CacheButtonSetting.class, theme -> (table, setting) -> {
            CacheButtonSetting buttonSetting = (CacheButtonSetting) setting;
            var button = table.add(theme.button(WaveXinI18n.tr(
                "button.wavexin.litematica_printer.clear_cache.label", "Clear Cache"
            ))).widget();
            button.action = buttonSetting::run;
            button.tooltip = WaveXinI18n.tr(
                "button.wavexin.litematica_printer.clear_cache.tooltip", setting.description
            );
        });
        SettingsWidgetFactory.registerCustomFactory(SelectionHelpSetting.class, theme -> (table, setting) ->
            table.add(theme.label(WaveXinI18n.tr(
                "status.wavexin.litematica_printer.selection_help",
                "Use .sel, .sel 1, .sel 2, and .sel c to manage the restock region."
            ))).expandX()
        );
    }

    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgBuild = settings.createGroup("Build");
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");
    private final SettingGroup sgRestock = settings.createGroup("Restock Region");
    private final SettingGroup sgCache = settings.createGroup("Cache");
    private final SettingGroup sgSupplyRender = sgRestock;
    private final SettingGroup sgBatchRender = settings.createGroup("Next Batch Render");

    private final Setting<Integer> scanPositionsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("Scan Positions Per Tick")
        .description("Maximum projection positions parsed each tick.")
        .defaultValue(4096)
        .min(256)
        .sliderRange(256, 32768)
        .build()
    );

    private final Setting<Integer> maximumProjectionVolume = sgGeneral.add(new IntSetting.Builder()
        .name("Maximum Projection Volume")
        .description("Maximum number of positions allowed in the selected projection bounds.")
        .defaultValue(DEFAULT_MAXIMUM_PROJECTION_VOLUME)
        .min(1_000)
        .max(MAXIMUM_PROJECTION_VOLUME)
        .sliderRange(1_000, 50_000_000)
        .build()
    );

    private final Setting<Boolean> debugLogEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("Debug Log")
        .description("Writes detailed Litematica Printer diagnostics to meteor-client/wavexin/printer.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> placementsPerTick = sgBuild.add(new IntSetting.Builder()
        .name("Blocks Per Tick")
        .description("Maximum block interaction packets sent per tick.")
        .defaultValue(DEFAULT_BLOCKS_PER_TICK)
        .min(1)
        .max(20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> allowInventoryPull = sgBuild.add(new BoolSetting.Builder()
        .name("Allow Inventory Pull")
        .description("Allows building materials to be pulled from inventory slots outside the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> buildInLayers = sgBuild.add(new BoolSetting.Builder()
        .name("Build In Layers")
        .description("Builds horizontal layer bands in order, matching Baritone builder semantics.")
        .defaultValue(true)
        .visible(() -> false)
        .build()
    );

    private final Setting<PrinterBuildOrder.LayerOrder> layerOrder = sgBuild.add(new EnumSetting.Builder<PrinterBuildOrder.LayerOrder>()
        .name("Layer Order")
        .description("Chooses whether layer bands are built from bottom to top or top to bottom.")
        .defaultValue(PrinterBuildOrder.LayerOrder.BottomToTop)
        .visible(() -> false)
        .build()
    );

    private final Setting<Integer> layerHeight = sgBuild.add(new IntSetting.Builder()
        .name("Layer Height")
        .description("Number of vertical blocks grouped into one build layer.")
        .defaultValue(1)
        .min(1)
        .max(16)
        .sliderRange(1, 8)
        .visible(() -> false)
        .build()
    );

    private final Setting<PrinterBuildOrder.RowAxis> rowAxis = sgBuild.add(new EnumSetting.Builder<PrinterBuildOrder.RowAxis>()
        .name("Row Axis")
        .description("Build row direction; Automatic chooses the longer horizontal axis.")
        .defaultValue(PrinterBuildOrder.RowAxis.Automatic)
        .visible(() -> false)
        .build()
    );

    private final Setting<Integer> placementDelay = sgBuild.add(new IntSetting.Builder()
        .name("Placement Delay")
        .description("Minimum delay between placement batches. (ms)")
        .defaultValue(1)
        .min(0)
        .max(1000)
        .sliderRange(0, 500)
        .build()
    );

    private final Setting<Integer> maxPlacementRetries = sgBuild.add(new IntSetting.Builder()
        .name("Placement Retries")
        .description("Maximum placement attempts in one retry round before the target is temporarily parked.")
        .defaultValue(5)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> retryInterval = sgBuild.add(new IntSetting.Builder()
        .name("Retry Interval")
        .description("Minimum delay before another placement attempt for the same target. (ms)")
        .defaultValue(250)
        .min(50)
        .max(5000)
        .sliderRange(50, 1000)
        .build()
    );

    private final Setting<Double> interactionRange = sgBuild.add(new DoubleSetting.Builder()
        .name("Interaction Range")
        .description("Maximum range used for placement checks and supply containers.")
        .defaultValue(4.5)
        .min(2.0)
        .max(6.0)
        .sliderRange(2.0, 6.0)
        .build()
    );

    private final Setting<Integer> actionTimeout = sgBuild.add(new IntSetting.Builder()
        .name("Action Timeout")
        .description("Ticks to wait for placement confirmation or a supply container response.")
        .defaultValue(80)
        .min(20)
        .max(400)
        .sliderRange(20, 200)
        .build()
    );

    private final Setting<Integer> pathTimeout = sgNavigation.add(new IntSetting.Builder()
        .name("Path Timeout")
        .description("Ticks allowed for each Baritone builder-style goal attempt.")
        .defaultValue(400)
        .min(100)
        .max(2400)
        .sliderRange(100, 1200)
        .visible(() -> !manualMovementMode())
        .build()
    );

    private final Setting<Integer> pathRetries = sgNavigation.add(new IntSetting.Builder()
        .name("Path Retries")
        .description("Maximum Baritone goal attempts for one target.")
        .defaultValue(6)
        .min(1)
        .max(20)
        .sliderRange(1, 10)
        .visible(() -> !manualMovementMode())
        .build()
    );

    private final Setting<Integer> chunkLoadGoalRange = sgNavigation.add(new IntSetting.Builder()
        .name("Chunk Load Goal Range")
        .description("Baritone goal radius used when loading projection or supply chunks.")
        .defaultValue(8)
        .min(1)
        .max(32)
        .sliderRange(1, 16)
        .visible(() -> !manualMovementMode())
        .build()
    );

    private final Setting<Boolean> loadProjectionChunks = sgNavigation.add(new BoolSetting.Builder()
        .name("Load Projection Chunks")
        .description("Uses Baritone to load unknown projection chunks before scanning or completion checks.")
        .defaultValue(true)
        .visible(() -> !manualMovementMode())
        .build()
    );

    private final Setting<Boolean> restockEnabled = sgRestock.add(new BoolSetting.Builder()
        .name("Enabled")
        .description("Fetches missing materials from placed containers inside the configured cuboid.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> restockSelectionHelp = sgRestock.add(new SelectionHelpSetting.Builder()
        .name("Selection")
        .description("Use .sel, .sel 1, .sel 2, and .sel c to manage the restock region.")
        .build()
    );

    private final Setting<Integer> restockX1 = coordinate("X1");
    private final Setting<Integer> restockY1 = coordinate("Y1");
    private final Setting<Integer> restockZ1 = coordinate("Z1");
    private final Setting<Integer> restockX2 = coordinate("X2");
    private final Setting<Integer> restockY2 = coordinate("Y2");
    private final Setting<Integer> restockZ2 = coordinate("Z2");
    private final Setting<Boolean> restockCorner1Stored = storedCorner("Restock Corner 1 Selected");
    private final Setting<Boolean> restockCorner2Stored = storedCorner("Restock Corner 2 Selected");

    private final Setting<Integer> supplyScanPositionsPerTick = sgRestock.add(new IntSetting.Builder()
        .name("Scan Positions Per Tick")
        .description("Maximum supply-region positions scanned each tick.")
        .defaultValue(4096)
        .min(256)
        .sliderRange(256, 32768)
        .build()
    );

    private final Setting<Integer> maximumSupplyVolume = sgRestock.add(new IntSetting.Builder()
        .name("Maximum Supply Volume")
        .description("Maximum number of positions allowed in the supply cuboid.")
        .defaultValue(2_000_000)
        .min(1)
        .max(20_000_000)
        .sliderRange(1_000, 5_000_000)
        .build()
    );

    private final Setting<Integer> containerMoveDelay = sgRestock.add(new IntSetting.Builder()
        .name("Container Move Delay")
        .description("Minimum delay between whole-stack container transfers. (ms)")
        .defaultValue(150)
        .min(50)
        .max(2000)
        .sliderRange(50, 1000)
        .build()
    );

    private final Setting<Integer> containerTimeout = sgRestock.add(new IntSetting.Builder()
        .name("Container Timeout")
        .description("Ticks to wait for a supply container screen to open.")
        .defaultValue(80)
        .min(20)
        .max(400)
        .sliderRange(20, 200)
        .build()
    );

    private final Setting<Integer> containerRetries = sgRestock.add(new IntSetting.Builder()
        .name("Container Retries")
        .description("Maximum attempts to open one supply container.")
        .defaultValue(2)
        .min(1)
        .max(10)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Boolean> cacheEnabled = sgCache.add(new BoolSetting.Builder()
        .name("Enabled")
        .description("Stores current-session projection progress for module restarts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clearCache = sgCache.add(new CacheButtonSetting.Builder()
        .name("Clear Cache")
        .description("Deletes the current Litematica Printer session cache.")
        .action(this::clearSessionCacheFromButton)
        .build()
    );

    private final Setting<Boolean> renderSupplyRegion = sgSupplyRender.add(new BoolSetting.Builder()
        .name("Render Region")
        .description("Renders the selected restock cuboid even while the printer is disabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> supplyRegionShapeMode = sgSupplyRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("Shape Mode")
        .description("Rendering style for the selected restock cuboid.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> supplyRegionSideColor = sgSupplyRender.add(new ColorSetting.Builder()
        .name("Side Color")
        .description("Fill color for the selected restock cuboid.")
        .defaultValue(new SettingColor(0, 170, 255, 35))
        .visible(() -> supplyRegionShapeMode.get() == ShapeMode.Sides || supplyRegionShapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> supplyRegionLineColor = sgSupplyRender.add(new ColorSetting.Builder()
        .name("Line Color")
        .description("Outline color for the selected restock cuboid.")
        .defaultValue(new SettingColor(0, 220, 255, 190))
        .visible(() -> supplyRegionShapeMode.get() == ShapeMode.Lines || supplyRegionShapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<Boolean> renderNextBatch = sgBatchRender.add(new BoolSetting.Builder()
        .name("Render Next Batch")
        .description("Highlights the exact batch planned for the next placement tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> nextBatchShapeMode = sgBatchRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("Shape Mode")
        .description("Rendering style for the next placement batch.")
        .defaultValue(ShapeMode.Both)
        .visible(renderNextBatch::get)
        .build()
    );

    private final Setting<SettingColor> nextBatchSideColor = sgBatchRender.add(new ColorSetting.Builder()
        .name("Side Color")
        .description("Fill color for the next placement batch.")
        .defaultValue(new SettingColor(40, 220, 80, 35))
        .visible(() -> renderNextBatch.get()
            && (nextBatchShapeMode.get() == ShapeMode.Sides || nextBatchShapeMode.get() == ShapeMode.Both))
        .build()
    );

    private final Setting<SettingColor> nextBatchLineColor = sgBatchRender.add(new ColorSetting.Builder()
        .name("Line Color")
        .description("Outline color for the next placement batch.")
        .defaultValue(new SettingColor(60, 255, 100, 210))
        .visible(() -> renderNextBatch.get()
            && (nextBatchShapeMode.get() == ShapeMode.Lines || nextBatchShapeMode.get() == ShapeMode.Both))
        .build()
    );

    private final PrinterInventory inventory = new PrinterInventory();
    private final PrinterPlacement placement = new PrinterPlacement(inventory);
    private final PrinterBatchPlanner<PrinterPlacement.Candidate> batchPlanner = new PrinterBatchPlanner<>();
    private final PrinterDebugLog debugLog = new PrinterDebugLog();
    private final PrinterSessionCache sessionCache = new PrinterSessionCache();
    private final SupplyContainerSession containerSession = new SupplyContainerSession();
    private final Map<BlockPos, ProjectionScan.Target> pending = new LinkedHashMap<>();
    private final List<ProjectionScan.Target> allTargets = new ArrayList<>();
    private final List<DeferredIssue> deferred = new ArrayList<>();
    private final Set<BlockPos> deferredPositions = new HashSet<>();
    private final Map<BlockPos, AwaitingPlacement> awaiting = new HashMap<>();
    private final Map<BlockPos, Integer> placementAttempts = new HashMap<>();
    private final Map<BlockPos, Integer> retryAfterTick = new HashMap<>();
    private final Map<BlockPos, ParkedPlacement> parkedPlacements = new HashMap<>();
    private final Map<BlockPos, Integer> screenGuards = new HashMap<>();
    private final Set<BlockPos> manualCorrections = new LinkedHashSet<>();
    private final Map<Item, Integer> missing = new LinkedHashMap<>();
    private final Map<Item, Integer> excess = new LinkedHashMap<>();
    private final Map<Item, Integer> supplyGoals = new LinkedHashMap<>();
    private final Map<Item, Integer> restockServedStacks = new HashMap<>();
    private final Set<Item> projectionMaterials = new HashSet<>();
    private final Map<BlockPos, Integer> supplyContainerAttempts = new HashMap<>();
    private final Deque<BlockPos> supplyContainers = new ArrayDeque<>();
    private final List<BlockPos> allSupplyContainers = new ArrayList<>();
    private List<ProjectionScan.Target> restockPlanningWindow = List.of();
    private PrinterBatchPlan<PrinterPlacement.Candidate> nextPlan;

    private PrinterNavigator navigator;
    private LitematicaProjection.Selection selection;
    private LitematicaProjection.Selection cachedSelection;
    private ProjectionScan projectionScan;
    private ProjectionScan auditScan;
    private ProjectionRequirementScan requirementScan;
    private ProjectionRequirementScan.Result layerRequirement;
    private SupplyRegionScanner supplyScanner;
    private SupplyRegionScanner supplyCacheScanner;
    private ClientWorld sessionWorld;
    private LitematicaProjection.LayerFilter activeLayerFilter = LitematicaProjection.LayerFilter.all();
    private RequirementPurpose requirementPurpose;
    private Stage stage = Stage.IDLE;
    private Stage pathResume;
    private PathPurpose pathPurpose;
    private ProjectionScan.Target miningTarget;
    private BlockPos pathActionTarget;
    private PrinterNavigator.NavigationPlan pathNavigationPlan;
    private BlockPos currentSupplyContainer;
    private BlockPos lastBuildPosition;
    private String projectionFingerprint;
    private String activeLayerSignature = "ALL";
    private String announcedLayerSignature;
    private String restockRegionFingerprint;
    private int stageTicks;
    private int totalTicks;
    private boolean pathLoadOnly;
    private int pathAttempts;
    private int inaccessibleSupplyContainers;
    private int exactAuditFailures;
    private int entityCount;
    private int blockEntityCount;
    private RestockPhase restockPhase = RestockPhase.TAKE_REQUIRED;
    private boolean closeUnexpectedScreen;
    private boolean layerStatusDirty = true;
    private boolean projectionScanCompleteLogged;
    private boolean suspendedRuntime;
    private boolean resumeRestockAfterCleanup;
    private boolean supplyCacheCoverageComplete;
    private int lastSupplyCacheScanChunkX = Integer.MIN_VALUE;
    private int lastSupplyCacheScanChunkZ = Integer.MIN_VALUE;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int lastPlacementTick = Integer.MIN_VALUE / 2;

    public LitematicaPrinter() {
        super(WaveXinAddon.CATEGORY, "litematica-printer", "Places reachable Litematica blocks and restocks after the player reaches the supply region.");
    }

    public void setSupplyCorner(int corner, BlockPos pos) {
        migrateStoredSupplySelection();
        BlockPos other = corner == 1
            ? new BlockPos(restockX2.get(), restockY2.get(), restockZ2.get())
            : new BlockPos(restockX1.get(), restockY1.get(), restockZ1.get());
        boolean otherSelected = corner == 1 ? restockCorner2Stored.get() : restockCorner1Stored.get();
        if (otherSelected && supplyVolume(pos, other) < 2L) {
            throw new IllegalArgumentException("Supply region must contain at least 2 blocks (1x1x2 minimum)");
        }

        if (corner == 1) {
            restockX1.set(pos.getX());
            restockY1.set(pos.getY());
            restockZ1.set(pos.getZ());
            restockCorner1Stored.set(true);
        } else if (corner == 2) {
            restockX2.set(pos.getX());
            restockY2.set(pos.getY());
            restockZ2.set(pos.getZ());
            restockCorner2Stored.set(true);
        } else {
            throw new IllegalArgumentException("Supply corner must be 1 or 2");
        }
        restockRegionFingerprint = null;
        sessionCache.clearSupply();
        startIncrementalSupplyCacheScan();
    }

    public void clearSupplySelection() {
        restockX1.set(0);
        restockY1.set(0);
        restockZ1.set(0);
        restockX2.set(0);
        restockY2.set(0);
        restockZ2.set(0);
        restockCorner1Stored.set(false);
        restockCorner2Stored.set(false);
        restockRegionFingerprint = null;
        sessionCache.clearSupply();
        supplyScanner = null;
        supplyContainers.clear();
        allSupplyContainers.clear();
        supplyGoals.clear();
        missing.clear();
        currentSupplyContainer = null;
        containerSession.close();
        if (isActive() && isRestockStage()) setStage(Stage.BUILDING);
        nextPlan = null;
        supplyCacheScanner = null;
    }

    private long supplyVolume() {
        return supplyVolume(
            new BlockPos(restockX1.get(), restockY1.get(), restockZ1.get()),
            new BlockPos(restockX2.get(), restockY2.get(), restockZ2.get())
        );
    }

    static long supplyVolume(BlockPos first, BlockPos second) {
        try {
            return Math.multiplyExact(
                (long) Math.abs(second.getX() - first.getX()) + 1L,
                Math.multiplyExact((long) Math.abs(second.getY() - first.getY()) + 1L,
                    (long) Math.abs(second.getZ() - first.getZ()) + 1L)
            );
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    void renderSupplySelection(Render3DEvent event) {
        if (!renderSupplyRegion.get() || mc.world == null || !hasSupplyRegionSelection()) return;
        int minX = Math.min(restockX1.get(), restockX2.get());
        int minY = Math.min(restockY1.get(), restockY2.get());
        int minZ = Math.min(restockZ1.get(), restockZ2.get());
        int maxX = Math.max(restockX1.get(), restockX2.get());
        int maxY = Math.max(restockY1.get(), restockY2.get());
        int maxZ = Math.max(restockZ1.get(), restockZ2.get());
        event.renderer.box(
            minX, minY, minZ,
            maxX + 1.0, maxY + 1.0, maxZ + 1.0,
            supplyRegionSideColor.get(), supplyRegionLineColor.get(), supplyRegionShapeMode.get(), 0
        );
    }

    void renderNextBatch(Render3DEvent event) {
        if (!isActive() || !renderNextBatch.get() || mc.world == null || nextPlan == null
            || nextPlan.action() != PrinterBatchPlan.Action.PLACE_BATCH) return;
        for (PrinterBatchPlan.PlannedPlacement<PrinterPlacement.Candidate> placement : nextPlan.batch()) {
            BlockPos pos = placement.target().pos();
            event.renderer.box(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0,
                nextBatchSideColor.get(), nextBatchLineColor.get(), nextBatchShapeMode.get(), 0
            );
        }
    }

    void renderManualCorrections(Render3DEvent event) {
        if (!isActive() || mc.world == null) return;
        for (BlockPos pos : manualCorrections) {
            event.renderer.box(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0,
                MANUAL_SIDE_COLOR, MANUAL_LINE_COLOR, ShapeMode.Both, 0
            );
        }
    }

    @Override
    public void onActivate() {
        migrateStoredSupplySelection();
        debugLog.open(debugLogEnabled.get(), mc.runDirectory.toPath());
        debugLog.info("activation", "minecraft", MINECRAFT_VERSION, "debug_file", debugLog.path());
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            stopWithActivationError("error.wavexin.litematica_printer.world_unavailable", "(highlight)(bold)Litematica Printer requires an active world.(default)");
            return;
        }
        if (canResumeRuntime()) {
            resumeRuntime();
            return;
        }
        resetRuntime(false);
        try {
            selection = LitematicaProjection.open(MINECRAFT_VERSION);
            cachedSelection = selection;
            sessionWorld = (ClientWorld) mc.world;
            projectionScan = new ProjectionScan(selection, mc.world, maximumProjectionVolume.get());
            projectionScan.prepareNextChunkRescan();
            entityCount = selection.entityCount();
            blockEntityCount = selection.blockEntityCount();
            inventory.begin();
            stage = Stage.LOADING_PROJECTION;
            startIncrementalSupplyCacheScan();
            debugLog.info(
                "selection", "name", selection.name(), "litematica", selection.litematicaVersion(),
                "min", selection.min(), "max", selection.max()
            );
            infoKey(
                "message.wavexin.litematica_printer.scan_started",
                "Reading projection %s with Litematica %s (%d positions).",
                selection.name(), selection.litematicaVersion(), projectionScan.volume()
            );
        } catch (LitematicaProjection.ProjectionException e) {
            stopWithActivationError(projectionFailureKey(e.failure()), "(highlight)(bold)Litematica projection could not be opened: %s(default)", e.getMessage());
        } catch (RuntimeException e) {
            stopWithActivationError("error.wavexin.litematica_printer.initialization_failed", "(highlight)(bold)Litematica Printer initialization failed: %s(default)", detail(e));
        }
    }

    @Override
    public void onDeactivate() {
        debugLog.info("deactivation", "stage", stage, "pending", pending.size(), "awaiting", awaiting.size());
        debugLog.flush();
        if (selection != null && mc.world == sessionWorld && selection.isStillSelected()) suspendRuntime();
        else resetRuntime(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (stage == Stage.IDLE || stage == Stage.PAUSED) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            pauseError("error.wavexin.litematica_printer.world_lost", "(highlight)(bold)World connection was lost; the printer was disabled.(default)");
            return;
        }
        if (selection != null && !selection.isStillSelected()) {
            clearSessionCache();
            pauseError("error.wavexin.litematica_printer.selection_changed", "(highlight)(bold)The selected Litematica placement changed; the printer was disabled.(default)");
            return;
        }

        totalTicks++;
        stageTicks++;
        if (supplyCacheScanner == null && !supplyCacheCoverageComplete && totalTicks % 20 == 0) {
            startIncrementalSupplyCacheScan();
        }
        tickIncrementalSupplyCacheScan();
        screenGuards.entrySet().removeIf(entry -> totalTicks > entry.getValue());
        closeUnexpectedScreen();
        inventory.enforcePriority();
        try {
            refreshLayerFilter();
            refreshRetryState();
            queueNearbyChunkRescan();
            switch (stage) {
                case LOADING_PROJECTION -> tickProjectionScan();
                case BUILDING -> tickBuild();
                case PATHING -> tickPath();
                case MINING -> tickMine();
                case SCANNING_SUPPLIES -> tickSupplyScan();
                case NEXT_CONTAINER -> tickNextContainer();
                case OPENING_CONTAINER -> tickOpenContainer();
                case RETURNING_SUPPLIES -> tickReturnSupplies();
                case TAKING_SUPPLIES -> tickTakeSupplies();
                case SCANNING_REQUIREMENTS -> tickRequirementScan();
                case WAITING_SUPPLY_LOAD -> tickWaitingSupplyLoad();
                case AUDITING -> tickAudit();
                default -> {
                }
            }
        } catch (RuntimeException e) {
            pauseError("error.wavexin.litematica_printer.runtime_failed", "(highlight)(bold)Litematica Printer was disabled after an unexpected error: %s(default)", detail(e));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onOpenScreen(OpenScreenEvent event) {
        if (!isActive() || screenGuards.isEmpty() || isRestockStage()
            || !(event.screen instanceof HandledScreen<?>)) return;
        event.cancel();
        closeUnexpectedScreen = true;
        debugLog.warn("interaction_screen_blocked", "guards", screenGuards.keySet(), "stage", stage);
    }

    private void tickProjectionScan() {
        projectionScan.prepareNextChunkRescan();
        projectionScan.scan(scanPositionsPerTick.get());
        absorbProjectionScan(projectionScan);
        if (!projectionScan.isFingerprintReady()) return;

        projectionFingerprint = sessionFingerprint(projectionScan.fingerprint());
        debugLog.info(
            "selection_fingerprint", "fingerprint", projectionFingerprint,
            "verified_chunks", projectionScan.verifiedChunks().size()
        );
        if (cacheEnabled.get()) {
            sessionCache.begin(projectionFingerprint);
            sessionCache.recordVerifiedChunks(projectionScan.verifiedChunks());
            lastBuildPosition = sessionCache.lastPosition();
        }
        absorbProjectionScan(projectionScan);
        projectionScan.prepareNextChunkRescan();
        nextPlan = null;
        setStage(Stage.BUILDING);
        infoKey(
            "message.wavexin.litematica_printer.build_started",
            "Projection indexed: %d currently loaded actionable blocks, %d deferred blocks.",
            pending.size(), deferred.size()
        );
    }

    private void scanLoadedProjectionChunks() {
        if (projectionScan == null) return;
        projectionScan.prepareNextChunkRescan();
        projectionScan.scan(scanPositionsPerTick.get());
        absorbProjectionScan(projectionScan);
        if (projectionScan.isDone()) {
            if (!projectionScanCompleteLogged) {
                debugLog.info("projection_chunks_complete", "targets", allTargets.size(), "deferred", deferred.size());
                projectionScanCompleteLogged = true;
            }
        } else {
            projectionScanCompleteLogged = false;
            projectionScan.prepareNextChunkRescan();
        }
    }

    private void queueNearbyChunkRescan() {
        if (projectionScan == null || mc.player == null) return;
        int chunkX = mc.player.getBlockX() >> 4;
        int chunkZ = mc.player.getBlockZ() >> 4;
        if (chunkX == lastPlayerChunkX && chunkZ == lastPlayerChunkZ) return;
        lastPlayerChunkX = chunkX;
        lastPlayerChunkZ = chunkZ;
        if (supplyCacheScanner == null
            && (chunkX != lastSupplyCacheScanChunkX || chunkZ != lastSupplyCacheScanChunkZ)) {
            startIncrementalSupplyCacheScan();
        }
        for (int x = chunkX - 1; x <= chunkX + 1; x++) {
            for (int z = chunkZ - 1; z <= chunkZ + 1; z++) projectionScan.requestChunkRescan(x, z);
        }
        projectionScanCompleteLogged = false;
        layerStatusDirty = true;
        nextPlan = null;
        debugLog.info("projection_revalidate", "chunk_x", chunkX, "chunk_z", chunkZ);
    }

    private void absorbProjectionScan(ProjectionScan scan) {
        boolean changed = false;
        int discovered = 0;
        for (ProjectionScan.Target target : scan.drainDiscoveredTargets()) {
            if (pending.put(target.pos(), target) == null) {
                allTargets.add(target);
                changed = true;
                discovered++;
            }
        }
        for (ProjectionScan.Skipped skipped : scan.drainDiscoveredSkipped()) {
            defer(skipped.pos(), skipped.reason().name());
        }
        boolean cacheReady = projectionFingerprint != null;
        Set<Long> verified = cacheReady ? scan.drainNewlyVerifiedChunks() : Set.of();
        List<ProjectionScan.Observation> observations = cacheReady ? scan.drainDiscoveredObservations() : List.of();
        if (cacheEnabled.get()) {
            sessionCache.recordObserved(observations);
            if (!verified.isEmpty()) sessionCache.recordVerifiedChunks(verified);
        }
        if (!observations.isEmpty() || !verified.isEmpty()) layerStatusDirty = true;
        for (ProjectionScan.Observation observation : observations) {
            ProjectionScan.Target target = pending.get(observation.pos());
            if (target != null && PrinterState.confirmed(observation.expected(), observation.actual())) {
                onPlacementConfirmed(target, observation.actual());
                changed = true;
            }
        }
        if (changed) {
            batchPlanner.reset(pending.values());
            nextPlan = null;
            debugLog.info(
                "projection_chunk_loaded", "new_targets", discovered,
                "pending", pending.size(), "verified_chunks", verified.size()
            );
        }
    }

    private void tickBuild() {
        scanLoadedProjectionChunks();
        expirePlacements();
        refreshManualCorrections();
        if (pending.isEmpty()) {
            nextPlan = null;
            if (activeLayerFilter.enabled() && awaiting.isEmpty() && layerStatusDirty) {
                startRequirementScan(RequirementPurpose.LAYER_COMPLETION);
            }
            return;
        }
        if (nextPlan != null && nextPlan.action() == PrinterBatchPlan.Action.PLACE_BATCH) {
            if (totalTicks - lastPlacementTick <= millisToTicks(placementDelay.get())) return;
            executeBatch(nextPlan);
            nextPlan = null;
            return;
        }

        long planningStarted = System.nanoTime();
        PrinterBatchPlan<PrinterPlacement.Candidate> fresh = createPlan();
        long planningMicros = (System.nanoTime() - planningStarted) / 1_000L;
        if (fresh.action() == PrinterBatchPlan.Action.PLACE_BATCH) {
            setNextPlan(fresh, planningMicros);
            return;
        }

        setNextPlan(fresh, planningMicros);
        if (fresh.action() == PrinterBatchPlan.Action.NEED_RESTOCK) {
            restockPlanningWindow = fresh.planningWindow();
            if (activeLayerFilter.enabled()) {
                if (layerStatusDirty || layerRequirement == null) {
                    nextPlan = null;
                    startRequirementScan(RequirementPurpose.RESTOCK);
                } else if (layerRequirement.unknown() == 0) {
                    nextPlan = null;
                    prepareRestock(
                        layerRequirement.required(),
                        layerRequirement.nearestDistanceSquared(),
                        layerRequirement.firstOrder(),
                        fresh.planningWindow()
                    );
                }
            } else {
                nextPlan = null;
                prepareRestock(fresh.planningWindow());
            }
            return;
        }
        if (manualMovementMode()) {
            if (fresh.action() == PrinterBatchPlan.Action.NO_ACTION
                && activeLayerFilter.enabled() && awaiting.isEmpty() && layerStatusDirty) {
                startRequirementScan(RequirementPurpose.LAYER_COMPLETION);
            }
            return;
        }
        switch (fresh.action()) {
            case MINE -> {
                nextPlan = null;
                startMining(fresh.target());
            }
            case NEED_PATH -> {
                nextPlan = null;
                startBuildPath(fresh);
            }
            case NEED_RESTOCK -> {
                nextPlan = null;
                prepareRestock(fresh.planningWindow());
            }
            case NO_ACTION -> {
                if (!awaiting.isEmpty()) return;
                while (!pending.isEmpty()) {
                    ProjectionScan.Target target = pending.values().iterator().next();
                    pending.remove(target.pos());
                    defer(target.pos(), "UNSUPPORTED_OR_FLOATING");
                }
                batchPlanner.clear();
                startAudit();
            }
            default -> {
            }
        }
    }

    private boolean manualMovementMode() {
        return true;
    }

    private void refreshLayerFilter() {
        if (selection == null) return;
        LitematicaProjection.LayerFilter current = selection.layerFilter();
        if (activeLayerSignature.equals(current.signature())) return;
        activeLayerFilter = current;
        activeLayerSignature = current.signature();
        announcedLayerSignature = null;
        requirementScan = null;
        layerRequirement = null;
        layerStatusDirty = true;
        nextPlan = null;
        debugLog.info("layer_filter", "enabled", current.enabled(), "signature", current.signature());
    }

    private void refreshRetryState() {
        retryAfterTick.entrySet().removeIf(entry -> totalTicks >= entry.getValue());
        List<ProjectionScan.Target> completed = new ArrayList<>();
        List<BlockState> completedStates = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, ParkedPlacement>> iterator = parkedPlacements.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, ParkedPlacement> entry = iterator.next();
            ProjectionScan.Target target = pending.get(entry.getKey());
            if (target == null) {
                iterator.remove();
                continue;
            }
            if (isLoaded(target.pos())
                && PrinterState.confirmed(target.state(), mc.world.getBlockState(target.pos()))) {
                iterator.remove();
                completed.add(target);
                completedStates.add(mc.world.getBlockState(target.pos()));
                continue;
            }

            boolean inRange = inReach(target.pos());
            ParkedPlacement parked = entry.getValue();
            if (!inRange && !parked.leftRange()) {
                entry.setValue(new ParkedPlacement(true, parked.retryAt()));
            } else if (retryAfterReentry(parked.leftRange(), inRange, totalTicks, parked.retryAt())) {
                iterator.remove();
                placementAttempts.remove(target.pos());
                retryAfterTick.remove(target.pos());
                debugLog.info("retry_reentered", "pos", target.pos());
            }
        }
        for (int i = 0; i < completed.size(); i++) onPlacementConfirmed(completed.get(i), completedStates.get(i));
    }

    private void expirePlacements() {
        Iterator<Map.Entry<BlockPos, AwaitingPlacement>> iterator = awaiting.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, AwaitingPlacement> entry = iterator.next();
            ProjectionScan.Target target = entry.getValue().target();
            if (!isLoaded(target.pos())) continue;
            BlockState actual = mc.world.getBlockState(target.pos());
            if (PrinterState.confirmed(target.state(), actual)) {
                iterator.remove();
                onPlacementConfirmed(target, actual);
                debugLog.info("placement_confirmed", "pos", target.pos(), "state", actual);
                continue;
            }

            AwaitingPlacement wait = entry.getValue();
            if (wait != null && totalTicks - wait.sentAt() > actionTimeout.get()) {
                iterator.remove();
                debugLog.warn(
                    "awaiting_timeout", "pos", target.pos(), "attempt", wait.attempts(), "age", totalTicks - wait.sentAt()
                );
                placementFailed(target, wait.attempts(), "AWAITING_TIMEOUT");
            }
        }
    }

    private PrinterBatchPlan<PrinterPlacement.Candidate> createPlan() {
        BlockPos cursor = mc.player.getBlockPos();
        int baseY = selection == null ? cursor.getY() : selection.min().getY();
        List<ProjectionScan.Target> nearbyTargets = nearbyPendingTargets();
        inventory.setWorkSet(localMaterialOrder(nearbyTargets));
        PrinterBatchPlan<PrinterPlacement.Candidate> chestPair = createChestPairPlan(nearbyTargets);
        if (chestPair != null) return chestPair;
        List<ProjectionScan.Target> confirmed = new ArrayList<>();
        PrinterBatchPlanner.Input input = new PrinterBatchPlanner.Input(
            nearbyTargets,
            awaiting.keySet(),
            cursor,
            baseY,
            false,
            1,
            PrinterBuildOrder.LayerOrder.BottomToTop,
            PrinterBuildOrder.RowAxis.Automatic,
            placementsPerTick.get(),
            Math.max(placementsPerTick.get(), nearbyTargets.size())
        );
        PrinterBatchPlan<PrinterPlacement.Candidate> plan = batchPlanner.planManualBuilder(input, new PrinterBatchPlanner.Environment<>() {
            @Override
            public PrinterBatchPlanner.TargetStatus status(ProjectionScan.Target target) {
                return targetStatus(target);
            }

            @Override
            public boolean isSolidSupport(BlockPos pos) {
                return LitematicaPrinter.this.isSolidSupport(pos);
            }

            @Override
            public Object materialKey(ProjectionScan.Target target) {
                return target.state().getBlock().asItem();
            }

            @Override
            public int availableMaterial(Object material) {
                return inventory.availableForBuild((Item) material, allowInventoryPull.get());
            }

            @Override
            public PrinterBatchPlanner.PlacementOption<PrinterPlacement.Candidate> findPlacement(
                ProjectionScan.Target target,
                Set<BlockPos> plannedSupports,
                java.util.function.Predicate<BlockPos> stableSupport,
                boolean requireReach,
                boolean requireMaterial
            ) {
                if (PrinterState.isDoubleChest(target.state()) && requireMaterial) return null;
                PrinterPlacement.Candidate candidate = placement.findCandidate(
                    target.pos(), target.state(), interactionRange.get(), requireReach,
                    plannedSupports, stableSupport, allowInventoryPull.get(), requireMaterial
                );
                if (candidate == null) return null;
                return new PrinterBatchPlanner.PlacementOption<>(
                    candidate,
                    candidate.support(),
                    candidate.supportSource() == PrinterBatchPlan.SupportSource.BATCH
                );
            }

            @Override
            public void confirmed(ProjectionScan.Target target) {
                confirmed.add(target);
            }
        });
        if (!confirmed.isEmpty()) {
            Set<BlockPos> positions = confirmed.stream().map(ProjectionScan.Target::pos).collect(java.util.stream.Collectors.toSet());
            positions.forEach(pending::remove);
            for (ProjectionScan.Target target : confirmed) {
                awaiting.remove(target.pos());
                BlockState actual = mc.world.getBlockState(target.pos());
                onPlacementConfirmed(target, actual);
                debugLog.info("target_already_correct", "pos", target.pos(), "state", actual);
            }
        }
        return plan;
    }

    private List<ProjectionScan.Target> nearbyPendingTargets() {
        double radius = interactionRange.get() + 2.0;
        double radiusSquared = radius * radius;
        List<ProjectionScan.Target> result = new ArrayList<>();
        for (ProjectionScan.Target target : pending.values()) {
            if (activeLayerFilter.includes(target.pos())
                && !parkedPlacements.containsKey(target.pos())
                && retryAfterTick.getOrDefault(target.pos(), 0) <= totalTicks
                && mc.player.getEyePos().squaredDistanceTo(target.pos().toCenterPos()) <= radiusSquared) {
                result.add(target);
            }
        }
        return result;
    }

    private List<Item> localMaterialOrder(List<ProjectionScan.Target> targets) {
        return targets.stream()
            .filter(target -> targetStatus(target) == PrinterBatchPlanner.TargetStatus.PLACE)
            .sorted(java.util.Comparator
                .comparingDouble((ProjectionScan.Target target) -> target.pos().getSquaredDistance(mc.player.getBlockPos()))
                .thenComparingInt(target -> target.pos().getY())
                .thenComparingInt(target -> target.pos().getZ())
                .thenComparingInt(target -> target.pos().getX()))
            .map(target -> target.state().getBlock().asItem())
            .filter(item -> item instanceof BlockItem)
            .distinct()
            .toList();
    }

    private PrinterBatchPlanner.TargetStatus targetStatus(ProjectionScan.Target target) {
        if (!isLoaded(target.pos())) return PrinterBatchPlanner.TargetStatus.UNLOADED;
        BlockState actual = mc.world.getBlockState(target.pos());
        if (PrinterState.confirmed(target.state(), actual)) return PrinterBatchPlanner.TargetStatus.CORRECT;
        if (target.state().isAir() || !actual.isReplaceable()) return PrinterBatchPlanner.TargetStatus.MINE;
        return PrinterBatchPlanner.TargetStatus.PLACE;
    }

    private PrinterBatchPlan<PrinterPlacement.Candidate> createChestPairPlan(List<ProjectionScan.Target> nearbyTargets) {
        List<ProjectionScan.Target> ordered = nearbyTargets.stream()
            .filter(target -> PrinterState.isDoubleChest(target.state()))
            .sorted(java.util.Comparator
                .comparingDouble((ProjectionScan.Target target) -> target.pos().getSquaredDistance(mc.player.getBlockPos()))
                .thenComparingInt(target -> target.pos().getY())
                .thenComparingInt(target -> target.pos().getZ())
                .thenComparingInt(target -> target.pos().getX()))
            .toList();
        Set<BlockPos> checked = new HashSet<>();
        for (ProjectionScan.Target target : ordered) {
            if (!checked.add(target.pos())) continue;
            Direction connection = PrinterState.chestConnection(target.state());
            BlockPos matePos = target.pos().offset(connection);
            BlockState mateExpected = selection.targetState(matePos);
            if (!isChestMate(target.state(), mateExpected, connection)) continue;
            checked.add(matePos);
            if (!activeLayerFilter.includes(matePos) || !isLoaded(target.pos()) || !isLoaded(matePos)
                || !inReach(target.pos()) || !inReach(matePos)) continue;

            ProjectionScan.Target mate = pending.get(matePos);
            BlockState targetActual = mc.world.getBlockState(target.pos());
            BlockState mateActual = mc.world.getBlockState(matePos);
            boolean targetMissing = targetActual.isReplaceable();
            boolean mateMissing = mateActual.isReplaceable();
            if (!targetMissing && !isUsableChestHalf(target.state(), targetActual)) continue;
            if (!mateMissing && !isUsableChestHalf(mateExpected, mateActual)) continue;
            if (!targetMissing && !mateMissing) continue;
            if (targetMissing && !isChestPlacementEligible(target)) continue;
            if (mateMissing && (mate == null || !isChestPlacementEligible(mate))) continue;

            int needed = (targetMissing ? 1 : 0) + (mateMissing ? 1 : 0);
            Item material = target.state().getBlock().asItem();
            if (inventory.availableForBuild(material, allowInventoryPull.get()) < needed) continue;

            if (targetMissing && mateMissing) {
                PrinterBatchPlan<PrinterPlacement.Candidate> firstOrder = chestPairPlan(target, mate);
                if (firstOrder != null) return firstOrder;
                PrinterBatchPlan<PrinterPlacement.Candidate> secondOrder = chestPairPlan(mate, target);
                if (secondOrder != null) return secondOrder;
                continue;
            }

            ProjectionScan.Target missingTarget = targetMissing ? target : mate;
            BlockPos partner = targetMissing ? matePos : target.pos();
            PrinterPlacement.Candidate candidate = placement.findChestMateCandidate(
                missingTarget.pos(), missingTarget.state(), partner, false, interactionRange.get()
            );
            if (candidate == null) continue;
            PrinterBatchPlan.PlannedPlacement<PrinterPlacement.Candidate> planned = new PrinterBatchPlan.PlannedPlacement<>(
                missingTarget, candidate, partner, PrinterBatchPlan.SupportSource.REAL
            );
            return PrinterBatchPlan.place(0, List.of(planned), List.of(missingTarget));
        }
        return null;
    }

    private PrinterBatchPlan<PrinterPlacement.Candidate> chestPairPlan(
        ProjectionScan.Target first,
        ProjectionScan.Target second
    ) {
        PrinterPlacement.Candidate firstCandidate = placement.findCandidate(
            first.pos(), first.state(), interactionRange.get(), true,
            Set.of(), this::isSolidSupport, allowInventoryPull.get(), true
        );
        if (firstCandidate == null || firstCandidate.supportSource() != PrinterBatchPlan.SupportSource.REAL) return null;
        PrinterPlacement.Candidate secondCandidate = placement.findChestMateCandidate(
            second.pos(), second.state(), first.pos(), true, interactionRange.get()
        );
        if (secondCandidate == null) return null;
        return PrinterBatchPlan.place(
            0,
            List.of(
                new PrinterBatchPlan.PlannedPlacement<>(
                    first, firstCandidate, firstCandidate.support(), PrinterBatchPlan.SupportSource.REAL
                ),
                new PrinterBatchPlan.PlannedPlacement<>(
                    second, secondCandidate, first.pos(), PrinterBatchPlan.SupportSource.BATCH
                )
            ),
            List.of(first, second)
        );
    }

    private boolean isChestPlacementEligible(ProjectionScan.Target target) {
        return !awaiting.containsKey(target.pos())
            && !parkedPlacements.containsKey(target.pos())
            && retryAfterTick.getOrDefault(target.pos(), 0) <= totalTicks;
    }

    static boolean isChestMate(BlockState first, BlockState second, Direction connection) {
        if (!PrinterState.isDoubleChest(first) || !PrinterState.isDoubleChest(second)
            || first.getBlock() != second.getBlock()
            || first.get(Properties.HORIZONTAL_FACING) != second.get(Properties.HORIZONTAL_FACING)
            || first.get(Properties.CHEST_TYPE) == second.get(Properties.CHEST_TYPE)) return false;
        return PrinterState.chestPair(
            first.get(Properties.CHEST_TYPE),
            second.get(Properties.CHEST_TYPE),
            first.get(Properties.HORIZONTAL_FACING),
            connection
        );
    }

    private static boolean isUsableChestHalf(BlockState expected, BlockState actual) {
        if (expected.equals(actual)) return true;
        return actual.getBlock() == expected.getBlock()
            && actual.contains(Properties.CHEST_TYPE)
            && actual.contains(Properties.HORIZONTAL_FACING)
            && actual.get(Properties.CHEST_TYPE) == ChestType.SINGLE
            && actual.get(Properties.HORIZONTAL_FACING) == expected.get(Properties.HORIZONTAL_FACING);
    }

    private void refreshManualCorrections() {
        Set<BlockPos> current = new LinkedHashSet<>();
        for (ProjectionScan.Target target : pending.values()) {
            if (!activeLayerFilter.includes(target.pos()) || !isLoaded(target.pos())
                || awaiting.containsKey(target.pos()) || targetStatus(target) != PrinterBatchPlanner.TargetStatus.MINE
                || isFixableChestHalf(target)) continue;
            current.add(target.pos());
        }
        if (current.equals(manualCorrections)) return;
        boolean hadCorrections = !manualCorrections.isEmpty();
        manualCorrections.clear();
        manualCorrections.addAll(current);
        debugLog.warn("manual_corrections", "count", current.size(), "positions", formatPositions(current));
        if (!current.isEmpty() && !hadCorrections) {
            errorKey(
                "error.wavexin.litematica_printer.manual_correction_required",
                "(highlight)(bold)Printer is waiting for manual correction at %s. Remove or fix the highlighted blocks to continue.(default)",
                formatPositions(current)
            );
        } else if (current.isEmpty() && hadCorrections) {
            infoKey(
                "message.wavexin.litematica_printer.manual_correction_resolved",
                "Manual correction targets were resolved; building resumed."
            );
        }
    }

    private boolean isFixableChestHalf(ProjectionScan.Target target) {
        if (!PrinterState.isDoubleChest(target.state())) return false;
        BlockState actual = mc.world.getBlockState(target.pos());
        if (!isUsableChestHalf(target.state(), actual) || actual.equals(target.state())) return false;
        Direction connection = PrinterState.chestConnection(target.state());
        BlockPos matePos = target.pos().offset(connection);
        BlockState mateExpected = selection.targetState(matePos);
        if (!isChestMate(target.state(), mateExpected, connection) || !isLoaded(matePos)) return false;
        return mc.world.getBlockState(matePos).isReplaceable();
    }

    private static String formatPositions(Set<BlockPos> positions) {
        StringBuilder result = new StringBuilder();
        int shown = 0;
        for (BlockPos pos : positions) {
            if (shown++ >= 4) break;
            if (!result.isEmpty()) result.append(", ");
            result.append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ());
        }
        if (positions.size() > 4) result.append(" (+").append(positions.size() - 4).append(')');
        return result.toString();
    }

    private void executeBatch(PrinterBatchPlan<PrinterPlacement.Candidate> plan) {
        long batchStarted = System.nanoTime();
        Set<BlockPos> sentSupports = new HashSet<>();
        int accepted = 0;
        int attempted = 0;
        inventory.setWorkSet(plan.planningWindow().stream()
            .map(target -> target.state().getBlock().asItem())
            .filter(item -> item instanceof BlockItem)
            .distinct()
            .toList());
        try (PrinterPlacement.PlacementBatch placementBatch = placement.beginBatch(allowInventoryPull.get())) {
            for (PrinterBatchPlan.PlannedPlacement<PrinterPlacement.Candidate> planned : plan.batch()) {
                if (!isPlacementStillUsable(planned, sentSupports)) {
                    debugLog.info("placement_stale", "pos", planned.target().pos(), "support", planned.support());
                    continue;
                }
                attempted++;
                if (planned.candidate().interactiveSupport()) {
                    screenGuards.put(planned.target().pos(), totalTicks + SCREEN_GUARD_TICKS);
                }
                if (placementBatch.place(planned.target().state(), planned.candidate())) {
                    int attempts = placementAttempts.merge(planned.target().pos(), 1, Integer::sum);
                    awaiting.put(planned.target().pos(), new AwaitingPlacement(planned.target(), totalTicks, attempts));
                    sentSupports.add(planned.target().pos());
                    accepted++;
                    debugLog.info(
                        "placement_accepted", "pos", planned.target().pos(), "expected", planned.target().state(),
                        "actual", mc.world.getBlockState(planned.target().pos()), "support", planned.support(),
                        "support_source", planned.supportSource(), "side", planned.candidate().hit().getSide(),
                        "sneak", planned.candidate().sneak(), "yaw", planned.candidate().yaw(),
                        "pitch", planned.candidate().pitch(), "rotated", planned.candidate().rotate(), "attempt", attempts
                    );
                } else {
                    screenGuards.remove(planned.target().pos());
                    int attempts = placementAttempts.merge(planned.target().pos(), 1, Integer::sum);
                    debugLog.warn(
                        "placement_rejected", "pos", planned.target().pos(), "expected", planned.target().state(),
                        "actual", mc.world.getBlockState(planned.target().pos()), "support", planned.support(),
                        "side", planned.candidate().hit().getSide(), "sneak", planned.candidate().sneak(),
                        "rotated", planned.candidate().rotate(), "attempt", attempts
                    );
                    placementFailed(planned.target(), attempts, "PLACEMENT_REJECTED");
                }
            }
        }
        for (PrinterInventory.HotbarMove move : inventory.drainHotbarMoves()) {
            debugLog.info(
                "hotbar_refill", "source", move.sourceSlot(), "hotbar", move.hotbarSlot(),
                "moved", Registries.ITEM.getId(move.moved()), "replaced", Registries.ITEM.getId(move.replaced())
            );
        }
        if (accepted > 0) lastPlacementTick = totalTicks;
        debugLog.info(
            "batch_complete", "attempted", attempted, "accepted", accepted,
            "micros", (System.nanoTime() - batchStarted) / 1_000L
        );
    }

    private boolean isPlacementStillUsable(
        PrinterBatchPlan.PlannedPlacement<PrinterPlacement.Candidate> planned,
        Set<BlockPos> sentSupports
    ) {
        if (targetStatus(planned.target()) != PrinterBatchPlanner.TargetStatus.PLACE) return false;
        if (inventory.findSlot(planned.target().state().getBlock().asItem(), allowInventoryPull.get()) < 0) return false;
        if (mc.player.getEyePos().squaredDistanceTo(planned.candidate().hit().getPos())
            > interactionRange.get() * interactionRange.get()) return false;
        return planned.supportSource() == PrinterBatchPlan.SupportSource.BATCH
            ? sentSupports.contains(planned.support())
            : planned.support() != null && isSolidSupport(planned.support());
    }

    private void placementFailed(ProjectionScan.Target target, int attempts, String reason) {
        screenGuards.remove(target.pos());
        int retryAt = totalTicks + millisToTicks(retryInterval.get());
        retryAfterTick.put(target.pos(), retryAt);
        if (attempts >= maxPlacementRetries.get()) {
            parkedPlacements.put(target.pos(), new ParkedPlacement(false, retryAt));
            debugLog.warn("placement_parked", "pos", target.pos(), "reason", reason, "attempts", attempts);
        } else {
            debugLog.info("placement_retry_scheduled", "pos", target.pos(), "reason", reason, "attempts", attempts, "retry_at", retryAt);
        }
        nextPlan = null;
    }

    private void onPlacementConfirmed(ProjectionScan.Target target, BlockState actual) {
        screenGuards.remove(target.pos());
        pending.remove(target.pos());
        batchPlanner.remove(target.pos());
        awaiting.remove(target.pos());
        placementAttempts.remove(target.pos());
        retryAfterTick.remove(target.pos());
        parkedPlacements.remove(target.pos());
        confirmCompleted(target, actual);
        layerStatusDirty = true;

        int retryAt = totalTicks + millisToTicks(retryInterval.get());
        for (BlockPos parked : List.copyOf(parkedPlacements.keySet())) {
            parkedPlacements.remove(parked);
            placementAttempts.remove(parked);
            retryAfterTick.put(parked, retryAt);
            debugLog.info("retry_released_by_progress", "pos", parked, "retry_at", retryAt);
        }
    }

    private void setNextPlan(PrinterBatchPlan<PrinterPlacement.Candidate> plan, long planningMicros) {
        if (nextPlan != null && nextPlan.sameDecision(plan)) return;
        nextPlan = plan;
        debugLog.info(
            "planner", "action", plan.action(), "layer", plan.layer(), "batch", batchPositions(plan),
            "target", plan.target() == null ? "none" : plan.target().pos(),
            "window", plan.planningWindow().size(), "micros", planningMicros
        );
    }

    private static List<BlockPos> batchPositions(PrinterBatchPlan<?> plan) {
        return plan.batch().stream().map(placement -> placement.target().pos()).toList();
    }

    static boolean shouldReplacePlan(PrinterBatchPlan<?> current, PrinterBatchPlan<?> fresh) {
        return current == null || !current.sameDecision(fresh);
    }

    private void startMining(ProjectionScan.Target target) {
        miningTarget = target;
        debugLog.info("mine_action", "pos", target.pos(), "expected", target.state());
        if (!inReach(target.pos())) {
            startPath(target.pos(), Stage.MINING, PathPurpose.MINE, false);
            return;
        }
        inventory.releasePersistent();
        setStage(Stage.MINING);
    }

    private void tickMine() {
        if (miningTarget == null) {
            setStage(Stage.BUILDING);
            return;
        }
        BlockState actual = mc.world.getBlockState(miningTarget.pos());
        if (actual.isAir()) {
            if (miningTarget.state().isAir()) {
                pending.remove(miningTarget.pos());
                batchPlanner.remove(miningTarget.pos());
                confirmCompleted(miningTarget, actual);
            }
            debugLog.info("mine_completed", "pos", miningTarget.pos(), "expected", miningTarget.state());
            inventory.releasePersistent();
            miningTarget = null;
            nextPlan = null;
            setStage(Stage.BUILDING);
            return;
        }
        if (!inReach(miningTarget.pos())) {
            inventory.releasePersistent();
            startPath(miningTarget.pos(), Stage.MINING, PathPurpose.MINE, false);
            return;
        }
        if (stageTicks == 1) inventory.acquirePersistentTool(actual);
        BlockUtils.breakBlock(miningTarget.pos(), false);
        if (stageTicks > actionTimeout.get() * 4) {
            inventory.releasePersistent();
            skipTarget(miningTarget, "MINING_FAILED");
            miningTarget = null;
            setStage(Stage.BUILDING);
        }
    }

    private void startRequirementScan(RequirementPurpose purpose) {
        requirementScan = new ProjectionRequirementScan(
            selection,
            activeLayerFilter,
            (ClientWorld) mc.world,
            sessionCache,
            mc.player.getBlockPos()
        );
        requirementPurpose = purpose;
        setStage(Stage.SCANNING_REQUIREMENTS);
        debugLog.info("requirement_scan_started", "purpose", purpose, "layer", activeLayerSignature);
    }

    private void tickRequirementScan() {
        requirementScan.scan(scanPositionsPerTick.get());
        if (!requirementScan.isDone()) return;

        layerRequirement = requirementScan.result();
        layerStatusDirty = false;
        if (cacheEnabled.get()) sessionCache.flush();
        debugLog.info(
            "requirement_scan_complete", "purpose", requirementPurpose,
            "remaining", layerRequirement.remaining(), "unknown", layerRequirement.unknown(),
            "required", formatItems(layerRequirement.required())
        );
        RequirementPurpose completedPurpose = requirementPurpose;
        requirementScan = null;
        requirementPurpose = null;

        if (completedPurpose == RequirementPurpose.LAYER_COMPLETION) {
            if (layerRequirement.remaining() == 0 && layerRequirement.unknown() == 0
                && !activeLayerSignature.equals(announcedLayerSignature)) {
                announcedLayerSignature = activeLayerSignature;
                infoKey(
                    "message.wavexin.litematica_printer.layer_completed",
                    "The current Litematica layer is complete. Switch layers manually to continue."
                );
            }
            setStage(Stage.BUILDING);
            return;
        }

        if (layerRequirement.unknown() > 0) {
            infoKey(
                "message.wavexin.litematica_printer.layer_waiting_for_data",
                "Waiting for cached or loaded block data for %d positions in the current Litematica layer.",
                layerRequirement.unknown()
            );
            setStage(Stage.BUILDING);
            return;
        }
        prepareRestock(
            layerRequirement.required(),
            layerRequirement.nearestDistanceSquared(),
            layerRequirement.firstOrder(),
            restockPlanningWindow
        );
    }

    private void prepareRestock(List<ProjectionScan.Target> planningWindow) {
        Map<Item, Integer> goals = new LinkedHashMap<>();
        Map<Item, Double> distances = new LinkedHashMap<>();
        Map<Item, Integer> order = new LinkedHashMap<>();
        BlockPos origin = mc.player.getBlockPos();
        for (ProjectionScan.Target target : planningWindow) {
            if (target.state().isAir()) continue;
            Item item = target.state().getBlock().asItem();
            if (item instanceof BlockItem) {
                goals.merge(item, 1, Integer::sum);
                distances.merge(item, origin.getSquaredDistance(target.pos()), Math::min);
                order.putIfAbsent(item, order.size());
            }
        }
        prepareRestock(goals, distances, order, planningWindow);
    }

    private void prepareRestock(
        Map<Item, Integer> goals,
        Map<Item, Double> distances,
        Map<Item, Integer> order,
        List<ProjectionScan.Target> planningWindow
    ) {
        supplyGoals.clear();
        List<PrinterRestockPlanner.Demand<Item>> demands = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : goals.entrySet()) {
            Item item = entry.getKey();
            demands.add(new PrinterRestockPlanner.Demand<>(
                item,
                entry.getValue(),
                inventory.count(item, allowInventoryPull.get()),
                inventory.stackCount(item, allowInventoryPull.get()),
                new ItemStack(item).getMaxCount(),
                distances.getOrDefault(item, Double.MAX_VALUE),
                order.getOrDefault(item, Integer.MAX_VALUE),
                restockServedStacks.getOrDefault(item, 0)
            ));
        }
        PrinterRestockPlanner.Plan<Item> restockPlan = PrinterRestockPlanner.plan(
            demands,
            inventory.emptySlots(allowInventoryPull.get()),
            inventory.occupiedSlotsOutside(goals.keySet(), allowInventoryPull.get())
        );
        supplyGoals.putAll(restockPlan.targetCounts());
        updateSupplyMissing();
        debugLog.info(
            "restock_plan", "layer_demand", formatItems(goals), "inventory_targets", formatItems(supplyGoals),
            "missing", formatMissing(), "empty_slots", inventory.emptySlots(allowInventoryPull.get()),
            "cleanup", restockPlan.requiresCleanup()
        );
        if (restockPlan.requiresCleanup()) {
            resumeRestockAfterCleanup = true;
            pauseError(
                "error.wavexin.litematica_printer.inventory_cleanup_required",
                "(highlight)(bold)The inventory is full of materials not needed by the current layer. Clean it, then enable the printer to resume restocking. Needed: %s(default)",
                formatItems(goals)
            );
            return;
        }
        if (missing.isEmpty()) return;
        if (!restockEnabled.get()) {
            pauseMissing(
                "error.wavexin.litematica_printer.restock_disabled",
                "(highlight)(bold)Missing materials and restocking is disabled: %s(default)"
            );
            return;
        }
        startSupplyScan();
    }

    private void startSupplyScan() {
        BlockPos pos1 = new BlockPos(restockX1.get(), restockY1.get(), restockZ1.get());
        BlockPos pos2 = new BlockPos(restockX2.get(), restockY2.get(), restockZ2.get());
        if (!hasSupplyRegionSelection()) {
            pauseError(
                "error.wavexin.litematica_printer.supply_region_not_selected",
                "(highlight)(bold)Select a restock region with .sel 1 and .sel 2 before restocking.(default)"
            );
            return;
        }
        restockRegionFingerprint = supplyRegionFingerprint(pos1, pos2);
        if (!supplyRegionLoaded(pos1, pos2)) {
            PrinterSessionCache.SupplyKnowledge knowledge = cacheEnabled.get()
                ? sessionCache.supplyKnowledge(restockRegionFingerprint, missing)
                : PrinterSessionCache.SupplyKnowledge.UNKNOWN;
            debugLog.info("restock_region_unloaded", "knowledge", knowledge, "missing", formatMissing());
            if (knowledge == PrinterSessionCache.SupplyKnowledge.INSUFFICIENT) {
                pauseMissing(
                    "error.wavexin.litematica_printer.cached_material_not_found",
                    "(highlight)(bold)The cached restock region does not contain the required materials: %s(default)"
                );
                return;
            }
            setStage(Stage.WAITING_SUPPLY_LOAD);
            infoKey(
                "message.wavexin.litematica_printer.restock_waiting_for_load",
                "Restocking is waiting for the player to load the selected supply region."
            );
            return;
        }

        beginSupplyScanner(pos1, pos2);
    }

    private void beginSupplyScanner(BlockPos pos1, BlockPos pos2) {
        try {
            supplyScanner = new SupplyRegionScanner((ClientWorld) mc.world, pos1, pos2, maximumSupplyVolume.get());
        } catch (IllegalArgumentException e) {
            pauseError("error.wavexin.litematica_printer.supply_region_too_large", "(highlight)(bold)%s(default)", e.getMessage());
            return;
        }

        supplyContainers.clear();
        allSupplyContainers.clear();
        supplyContainerAttempts.clear();
        inaccessibleSupplyContainers = 0;
        containerSession.reset();
        setStage(Stage.SCANNING_SUPPLIES);
        debugLog.info("restock_scan_started", "from", pos1, "to", pos2, "missing", formatMissing());
        infoKey("message.wavexin.litematica_printer.restock_started", "Scanning the configured supply cuboid for: %s", formatMissing());
    }

    private void tickWaitingSupplyLoad() {
        updateSupplyMissing();
        if (missing.isEmpty()) {
            finishRestock();
            return;
        }
        BlockPos pos1 = new BlockPos(restockX1.get(), restockY1.get(), restockZ1.get());
        BlockPos pos2 = new BlockPos(restockX2.get(), restockY2.get(), restockZ2.get());
        if (supplyRegionLoaded(pos1, pos2)) startSupplyScan();
    }

    private void tickSupplyScan() {
        SupplyRegionScanner.ScanResult result = supplyScanner.scan(supplyScanPositionsPerTick.get());
        if (result.chunkLoadTarget() != null) return;
        if (!result.done()) return;
        allSupplyContainers.addAll(supplyScanner.containers());
        allSupplyContainers.sort(java.util.Comparator
            .comparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ)
            .thenComparing(java.util.Comparator.comparingInt(BlockPos::getY).reversed()));
        List<Item> requiredOrder = List.copyOf(missing.keySet());
        allSupplyContainers.sort(java.util.Comparator.comparingInt(
            pos -> sessionCache.containerPriority(restockRegionFingerprint, pos, requiredOrder)
        ));
        if (cacheEnabled.get()) {
            sessionCache.recordSupplyContainers(restockRegionFingerprint, allSupplyContainers);
            sessionCache.flush();
        }
        beginTakePhase();
    }

    private void beginReturnPhase() {
        restockPhase = RestockPhase.RETURN_EXCESS;
        supplyContainers.clear();
        supplyContainers.addAll(allSupplyContainers);
        supplyContainerAttempts.clear();
        inaccessibleSupplyContainers = 0;
        currentSupplyContainer = null;
        updateSupplyExcess();
        debugLog.info("restock_return_phase", "excess", formatItems(excess), "containers", allSupplyContainers.size());
        setStage(Stage.NEXT_CONTAINER);
    }

    private void beginTakePhase() {
        containerSession.close();
        restockPhase = RestockPhase.TAKE_REQUIRED;
        supplyContainers.clear();
        supplyContainers.addAll(allSupplyContainers);
        supplyContainerAttempts.clear();
        inaccessibleSupplyContainers = 0;
        currentSupplyContainer = null;
        updateSupplyMissing();
        debugLog.info("restock_take_phase", "missing", formatMissing(), "containers", allSupplyContainers.size());
        setStage(Stage.NEXT_CONTAINER);
    }

    private void tickNextContainer() {
        if (restockPhase == RestockPhase.RETURN_EXCESS) {
            updateSupplyExcess();
            if (excess.isEmpty()) {
                beginTakePhase();
                return;
            }
            currentSupplyContainer = pollReachableSupplyContainer();
            if (currentSupplyContainer == null) {
                if (supplyContainers.isEmpty()) {
                    warningKey(
                        "warning.wavexin.litematica_printer.excess_not_returned",
                        "Some surplus projection materials could not be returned and will be retried later: %s",
                        formatItems(excess)
                    );
                    debugLog.warn("restock_return_incomplete", "excess", formatItems(excess));
                    beginTakePhase();
                }
                return;
            }
            approachCurrentContainer();
            return;
        }

        updateSupplyMissing();
        if (missing.isEmpty()) {
            finishRestock();
            return;
        }
        currentSupplyContainer = pollReachableSupplyContainer();
        if (currentSupplyContainer == null) {
            if (supplyContainers.isEmpty()) {
                if (inaccessibleSupplyContainers > 0) {
                    pauseMissing(
                        "error.wavexin.litematica_printer.container_inaccessible",
                        "(highlight)(bold)Required materials could not be obtained; %d supply containers were inaccessible or blocked: %s(default)",
                        inaccessibleSupplyContainers
                    );
                } else {
                    pauseMissing("error.wavexin.litematica_printer.material_not_found", "(highlight)(bold)Required materials were not found in accessible supply containers: %s(default)");
                }
            }
            return;
        }
        approachCurrentContainer();
    }

    private BlockPos pollReachableSupplyContainer() {
        int candidates = supplyContainers.size();
        for (int i = 0; i < candidates; i++) {
            BlockPos candidate = supplyContainers.pollFirst();
            if (candidate == null) return null;
            if (inReach(candidate)) return candidate;
            supplyContainers.addLast(candidate);
        }
        return null;
    }

    private void approachCurrentContainer() {
        if (!inReach(currentSupplyContainer)) {
            supplyContainers.addLast(currentSupplyContainer);
            currentSupplyContainer = null;
            setStage(Stage.NEXT_CONTAINER);
            return;
        }
        setStage(Stage.OPENING_CONTAINER);
    }

    private void tickOpenContainer() {
        if (currentSupplyContainer == null) {
            setStage(Stage.NEXT_CONTAINER);
            return;
        }
        if (!inReach(currentSupplyContainer)) {
            supplyContainers.addLast(currentSupplyContainer);
            currentSupplyContainer = null;
            setStage(Stage.NEXT_CONTAINER);
            return;
        }
        if (stageTicks == 1 && !containerSession.open(currentSupplyContainer)) {
            markContainerInaccessible();
            return;
        }
        if (containerSession.isOpen()) {
            setStage(restockPhase == RestockPhase.RETURN_EXCESS ? Stage.RETURNING_SUPPLIES : Stage.TAKING_SUPPLIES);
        } else if (stageTicks > containerTimeout.get()) {
            markContainerInaccessible();
        }
    }

    private void tickReturnSupplies() {
        if (!containerSession.isTransferring()) {
            updateSupplyExcess();
            if (excess.isEmpty()) {
                containerSession.close();
                beginTakePhase();
                return;
            }
        }
        if (!containerSession.isOpen()) {
            markContainerInaccessible();
            return;
        }
        if (stageTicks % containerMoveDelay.get() != 1 % containerMoveDelay.get()) return;

        SupplyContainerSession.TransferResult result = containerSession.returnExcess(excess, allowInventoryPull.get());
        debugLog.info("restock_return", "container", currentSupplyContainer, "result", result, "excess", formatItems(excess));
        if (result == SupplyContainerSession.TransferResult.NO_MATCH
            || result == SupplyContainerSession.TransferResult.CONTAINER_FULL) {
            containerSession.close();
            setStage(Stage.NEXT_CONTAINER);
        } else if (result == SupplyContainerSession.TransferResult.NOT_OPEN) {
            markContainerInaccessible();
        } else if (result == SupplyContainerSession.TransferResult.CURSOR_BLOCKED) {
            pauseError(
                "error.wavexin.litematica_printer.container_transfer_failed",
                "(highlight)(bold)Container transfer could not safely return the cursor stack; the printer was disabled.(default)"
            );
        }
    }

    private void tickTakeSupplies() {
        if (!containerSession.isTransferring()) {
            updateSupplyMissing();
            if (missing.isEmpty()) {
                cacheOpenContainer();
                containerSession.close();
                finishRestock();
                return;
            }
        }
        if (!containerSession.isOpen()) {
            markContainerInaccessible();
            return;
        }
        int delayTicks = millisToTicks(containerMoveDelay.get());
        if (stageTicks % delayTicks != 1 % delayTicks) return;

        SupplyContainerSession.TransferResult result = containerSession.takeWholeStack(missing, allowInventoryPull.get());
        if (result == SupplyContainerSession.TransferResult.MOVED) {
            Item moved = containerSession.consumeLastMovedItem();
            if (moved != null) restockServedStacks.merge(moved, 1, Integer::sum);
        }
        cacheOpenContainer();
        debugLog.info("restock_take", "container", currentSupplyContainer, "result", result, "missing", formatMissing());
        if (result == SupplyContainerSession.TransferResult.NO_MATCH) {
            containerSession.close();
            setStage(Stage.NEXT_CONTAINER);
        } else if (result == SupplyContainerSession.TransferResult.INVENTORY_FULL) {
            resumeRestockAfterCleanup = true;
            pauseMissing(
                "error.wavexin.litematica_printer.inventory_cleanup_required",
                "(highlight)(bold)The inventory cannot accept the planned full stacks. Clean it, then enable the printer to resume restocking. Missing: %s(default)"
            );
        } else if (result == SupplyContainerSession.TransferResult.NOT_OPEN) {
            markContainerInaccessible();
        } else if (result == SupplyContainerSession.TransferResult.CURSOR_BLOCKED) {
            pauseError(
                "error.wavexin.litematica_printer.container_transfer_failed",
                "(highlight)(bold)Container transfer could not safely return the cursor stack; the printer was disabled.(default)"
            );
        }
    }

    private void markContainerInaccessible() {
        cacheOpenContainer();
        containerSession.close();
        int attempts = supplyContainerAttempts.merge(currentSupplyContainer, 1, Integer::sum);
        if (attempts < containerRetries.get()) supplyContainers.addLast(currentSupplyContainer);
        else inaccessibleSupplyContainers++;
        currentSupplyContainer = null;
        setStage(Stage.NEXT_CONTAINER);
    }

    private void finishRestock() {
        cacheOpenContainer();
        containerSession.close();
        supplyScanner = null;
        supplyGoals.clear();
        excess.clear();
        allSupplyContainers.clear();
        restockPlanningWindow = List.of();
        currentSupplyContainer = null;
        nextPlan = null;
        setStage(Stage.BUILDING);
        debugLog.info("restock_completed");
        infoKey("message.wavexin.litematica_printer.restock_completed", "Required materials were moved into the player inventory.");
    }

    private void cacheOpenContainer() {
        if (!cacheEnabled.get() || currentSupplyContainer == null || restockRegionFingerprint == null) return;
        if (!containerSession.isOpen() && !containerSession.hasObserved(currentSupplyContainer)) return;
        Map<Item, Integer> contents = containerSession.observeOpenContainer();
        if (!containerSession.isOpen()) contents = containerSession.observedContents(currentSupplyContainer);
        sessionCache.recordContainer(restockRegionFingerprint, currentSupplyContainer, contents);
    }

    private boolean supplyRegionLoaded(BlockPos first, BlockPos second) {
        int minChunkX = Math.min(first.getX(), second.getX()) >> 4;
        int maxChunkX = Math.max(first.getX(), second.getX()) >> 4;
        int minChunkZ = Math.min(first.getZ(), second.getZ()) >> 4;
        int maxChunkZ = Math.max(first.getZ(), second.getZ()) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!((ClientWorld) mc.world).isChunkLoaded(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    private static String supplyRegionFingerprint(BlockPos first, BlockPos second) {
        return Math.min(first.getX(), second.getX()) + "," + Math.min(first.getY(), second.getY()) + "," + Math.min(first.getZ(), second.getZ())
            + "|" + Math.max(first.getX(), second.getX()) + "," + Math.max(first.getY(), second.getY()) + "," + Math.max(first.getZ(), second.getZ());
    }

    private void updateSupplyMissing() {
        missing.clear();
        for (Map.Entry<Item, Integer> entry : supplyGoals.entrySet()) {
            int remaining = entry.getValue() - inventory.count(entry.getKey(), allowInventoryPull.get());
            if (remaining > 0) missing.put(entry.getKey(), remaining);
        }
    }

    private void updateSupplyExcess() {
        excess.clear();
        for (Item item : projectionMaterials) {
            int surplus = surplus(
                inventory.count(item, allowInventoryPull.get()),
                supplyGoals.getOrDefault(item, 0)
            );
            if (surplus > 0) excess.put(item, surplus);
        }
    }

    static int surplus(int current, int demand) {
        return Math.max(0, current - demand);
    }

    private void refreshProjectionMaterials() {
        projectionMaterials.clear();
        for (ProjectionScan.Target target : allTargets) {
            if (target.state().isAir()) continue;
            Item item = target.state().getBlock().asItem();
            if (item instanceof BlockItem blockItem && !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
                projectionMaterials.add(item);
            }
        }
    }

    private void startBuildPath(PrinterBatchPlan<PrinterPlacement.Candidate> plan) {
        ProjectionScan.Target target = plan.target();
        if (plan.loadOnly()) {
            startPath(target.pos(), Stage.BUILDING, PathPurpose.BUILD, true);
            return;
        }
        boolean allowSameLevel = !mc.world.getBlockState(target.pos().up()).isAir();
        startPath(
            PrinterNavigator.NavigationPlan.build(target.pos(), plan.navigationSupport(), allowSameLevel),
            Stage.BUILDING,
            PathPurpose.BUILD,
            false
        );
    }

    private void startPath(
        BlockPos actionTarget,
        Stage resume,
        PathPurpose purpose,
        boolean loadOnly
    ) {
        PrinterNavigator.NavigationPlan navigationPlan = purpose == PathPurpose.MINE
            ? PrinterNavigator.NavigationPlan.mine(actionTarget)
            : PrinterNavigator.NavigationPlan.near(actionTarget, loadOnly ? chunkLoadGoalRange.get() : 1);
        startPath(navigationPlan, resume, purpose, loadOnly);
    }

    private void startPath(
        PrinterNavigator.NavigationPlan navigationPlan,
        Stage resume,
        PathPurpose purpose,
        boolean loadOnly
    ) {
        navigator.cancel();
        pathNavigationPlan = navigationPlan;
        pathActionTarget = navigationPlan.target();
        pathResume = resume;
        pathPurpose = purpose;
        pathLoadOnly = loadOnly;
        pathAttempts = 0;
        submitPathGoal();
        setStage(Stage.PATHING);
    }

    private void submitPathGoal() {
        pathAttempts++;
        navigator.goTo(pathNavigationPlan);
        debugLog.info(
            "path_goal", "purpose", pathPurpose, "kind", pathNavigationPlan.kind(),
            "target", pathNavigationPlan.target(), "support", pathNavigationPlan.support(),
            "attempt", pathAttempts
        );
        stageTicks = 0;
    }

    private void tickPath() {
        if ((pathLoadOnly && isLoaded(pathActionTarget)) || (!pathLoadOnly && inReach(pathActionTarget))) {
            navigator.cancel();
            debugLog.info("path_arrived", "purpose", pathPurpose, "target", pathActionTarget, "attempt", pathAttempts);
            if (pathPurpose == PathPurpose.BUILD) nextPlan = null;
            setStage(pathResume);
            return;
        }
        if (stageTicks <= pathTimeout.get() && (stageTicks <= 20 || navigator.isNavigating())) return;
        if (pathAttempts < pathRetries.get()) {
            navigator.cancel();
            submitPathGoal();
            return;
        }

        navigator.cancel();
        debugLog.error("path_failure", "purpose", pathPurpose, "target", pathActionTarget, "attempts", pathAttempts);
        if (pathPurpose == PathPurpose.CONTAINER) {
            markContainerInaccessible();
        } else if (pathPurpose == PathPurpose.SUPPLY_SCAN) {
            pauseError("error.wavexin.litematica_printer.supply_region_unreachable", "(highlight)(bold)The supply region could not be reached; the printer was disabled.(default)");
        } else if (pathPurpose == PathPurpose.PROJECTION_SCAN || pathPurpose == PathPurpose.AUDIT) {
            pauseError("error.wavexin.litematica_printer.projection_chunk_unreachable", "(highlight)(bold)A projection chunk could not be loaded; the printer was disabled.(default)");
        } else {
            pauseError(
                "error.wavexin.litematica_printer.build_path_failed",
                "(highlight)(bold)Baritone could not reach the required %s position at %d %d %d; the printer was disabled.(default)",
                pathPurpose.name().toLowerCase(java.util.Locale.ROOT),
                pathActionTarget.getX(), pathActionTarget.getY(), pathActionTarget.getZ()
            );
        }
    }

    private void startAudit() {
        if (navigator != null) navigator.cancel();
        inventory.releasePersistent();
        nextPlan = null;
        exactAuditFailures = 0;
        auditScan = new ProjectionScan(selection, mc.world, maximumProjectionVolume.get());
        setStage(Stage.AUDITING);
    }

    private void tickAudit() {
        auditScan.scan(scanPositionsPerTick.get());
        if (!auditScan.isDone()) {
            if (auditScan.prepareNextChunkRescan()) return;
            return;
        }

        if (!sessionFingerprint(auditScan.fingerprint()).equals(projectionFingerprint)) {
            clearSessionCache();
            pauseError("error.wavexin.litematica_printer.selection_changed", "(highlight)(bold)The selected Litematica placement changed; the printer was disabled.(default)");
            return;
        }

        Set<BlockPos> failedPositions = new HashSet<>(deferredPositions);
        for (ProjectionScan.Target target : auditScan.targets()) failedPositions.add(target.pos());
        for (ProjectionScan.Skipped skipped : auditScan.skipped()) failedPositions.add(skipped.pos());
        exactAuditFailures = failedPositions.size();
        long remainingFromLitematica = selection.remainingBlockCount().orElse(0L);
        long failedBlocks = Math.max(exactAuditFailures, remainingFromLitematica);
        if (cacheEnabled.get()) {
            sessionCache.recordVerifiedChunks(auditScan.verifiedChunks());
            sessionCache.flush();
        }

        debugLog.info(
            "audit", "exact_failures", exactAuditFailures, "litematica_remaining", remainingFromLitematica,
            "verified_chunks", auditScan.verifiedChunks().size(), "entities", entityCount, "block_entities", blockEntityCount
        );

        long skipped = failedBlocks + entityCount + blockEntityCount;
        if (skipped == 0) {
            infoKey("message.wavexin.litematica_printer.completed", "Litematica Printer completed %d projected block states.", allTargets.size());
        } else {
            errorKey(
                "error.wavexin.litematica_printer.completed_with_skips",
                "(highlight)(bold)All supported blocks are complete. Skipped or mismatched: %d blocks, %d entities, %d block-entity data records.(default)",
                failedBlocks, entityCount, blockEntityCount
            );
        }
        if (isActive()) toggle();
    }

    private void skipTarget(ProjectionScan.Target target, String reason) {
        if (target == null) return;
        pending.remove(target.pos());
        batchPlanner.remove(target.pos());
        awaiting.remove(target.pos());
        placementAttempts.remove(target.pos());
        defer(target.pos(), reason);
        nextPlan = null;
        debugLog.warn("target_deferred", "pos", target.pos(), "reason", reason);
    }

    private void defer(BlockPos pos, String reason) {
        if (pos != null && deferredPositions.add(pos.toImmutable())) {
            deferred.add(new DeferredIssue(pos.toImmutable(), reason));
        }
    }

    private boolean inReach(BlockPos pos) {
        return mc.player.getEyePos().squaredDistanceTo(pos.toCenterPos()) <= interactionRange.get() * interactionRange.get();
    }

    private boolean isLoaded(BlockPos pos) {
        return ((ClientWorld) mc.world).isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private boolean isSolidSupport(BlockPos pos) {
        if (!isLoaded(pos)) return false;
        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir() && !state.isReplaceable() && state.getFluidState().isEmpty();
    }

    private void confirmCompleted(ProjectionScan.Target target, BlockState actual) {
        lastBuildPosition = target.pos();
        if (cacheEnabled.get()) sessionCache.recordCompleted(target.pos(), target.state(), actual);
    }

    private String sessionFingerprint(String projectionHash) {
        String server = mc.getCurrentServerEntry() == null
            ? String.valueOf(mc.getNetworkHandler().getConnection().getAddress())
            : mc.getCurrentServerEntry().address;
        return MINECRAFT_VERSION + '|' + mc.world.getRegistryKey().getValue() + '|' + server + '|' + projectionHash;
    }

    void clearSessionCache() {
        sessionCache.clear();
        cachedSelection = null;
        debugLog.info("cache_cleared");
    }

    void monitorSessionCache() {
        if (cachedSelection != null && !cachedSelection.isStillSelected()) clearSessionCache();
    }

    private void clearSessionCacheFromButton() {
        clearSessionCache();
        if (cacheEnabled.get() && projectionFingerprint != null) {
            sessionCache.begin(projectionFingerprint);
            cachedSelection = selection;
        }
        infoKey("message.wavexin.litematica_printer.cache_cleared", "Litematica Printer session cache cleared.");
    }

    private void pauseMissing(String key, String fallback, Object... beforeMissing) {
        Object[] args = new Object[beforeMissing.length + 1];
        System.arraycopy(beforeMissing, 0, args, 0, beforeMissing.length);
        args[beforeMissing.length] = formatMissing();
        pauseError(key, fallback, args);
    }

    private String formatMissing() {
        return formatItems(missing);
    }

    private static String formatItems(Map<Item, Integer> items) {
        if (items.isEmpty()) return "none";
        StringBuilder result = new StringBuilder();
        for (Map.Entry<Item, Integer> entry : items.entrySet()) {
            if (!result.isEmpty()) result.append(", ");
            result.append(Registries.ITEM.getId(entry.getKey())).append(" x").append(entry.getValue());
        }
        return result.toString();
    }

    static int millisToTicks(int milliseconds) {
        return Math.max(1, (milliseconds + 49) / 50);
    }

    static boolean retryAfterReentry(boolean leftRange, boolean inRange, int currentTick, int retryAt) {
        return leftRange && inRange && currentTick >= retryAt;
    }

    private void pauseError(String key, String fallback, Object... args) {
        debugLog.error("disabled", "stage", stage, "key", key, "detail", java.util.Arrays.toString(args));
        setStage(Stage.PAUSED);
        errorKey(key, fallback, args);
        if (isActive()) toggle();
    }

    private void stopWithActivationError(String key, String fallback, Object... args) {
        debugLog.error("activation_failed", "key", key, "detail", java.util.Arrays.toString(args));
        errorKey(key, fallback, args);
        if (isActive()) toggle();
    }

    private void setStage(Stage next) {
        stage = next;
        stageTicks = 0;
    }

    private boolean canResumeRuntime() {
        return suspendedRuntime
            && mc.world == sessionWorld
            && selection != null
            && selection.isStillSelected()
            && (stage != Stage.PAUSED || resumeRestockAfterCleanup);
    }

    private void suspendRuntime() {
        if (cacheEnabled.get()) sessionCache.flush();
        if (navigator != null) {
            navigator.cancel();
            navigator.restore();
            navigator = null;
        }
        cacheOpenContainer();
        containerSession.close();
        inventory.close();
        screenGuards.clear();
        closeUnexpectedScreen = false;
        nextPlan = null;
        suspendedRuntime = true;
        debugLog.info("session_suspended", "stage", stage, "restock_resume", resumeRestockAfterCleanup);
    }

    private void resumeRuntime() {
        suspendedRuntime = false;
        inventory.begin();
        startIncrementalSupplyCacheScan();
        if (resumeRestockAfterCleanup) {
            resumeRestockAfterCleanup = false;
            setStage(Stage.BUILDING);
            if (activeLayerFilter.enabled() && layerRequirement != null) {
                prepareRestock(
                    layerRequirement.required(),
                    layerRequirement.nearestDistanceSquared(),
                    layerRequirement.firstOrder(),
                    restockPlanningWindow
                );
            } else {
                prepareRestock(restockPlanningWindow);
            }
        } else if (isRestockStage()) {
            setStage(Stage.BUILDING);
            updateSupplyMissing();
            if (!missing.isEmpty()) startSupplyScan();
        }
        debugLog.info("session_resumed", "stage", stage, "pending", pending.size(), "missing", formatMissing());
    }

    private void startIncrementalSupplyCacheScan() {
        if (!cacheEnabled.get() || mc.world == null || !hasSupplyRegionSelection()) return;
        BlockPos first = new BlockPos(restockX1.get(), restockY1.get(), restockZ1.get());
        BlockPos second = new BlockPos(restockX2.get(), restockY2.get(), restockZ2.get());
        try {
            supplyCacheScanner = new SupplyRegionScanner(
                (ClientWorld) mc.world, first, second, maximumSupplyVolume.get(), true
            );
            supplyCacheCoverageComplete = false;
            restockRegionFingerprint = supplyRegionFingerprint(first, second);
            debugLog.info("supply_cache_scan_started", "from", first, "to", second);
        } catch (IllegalArgumentException e) {
            supplyCacheScanner = null;
            supplyCacheCoverageComplete = true;
            debugLog.warn("supply_cache_scan_skipped", "reason", e.getMessage());
        }
    }

    private void tickIncrementalSupplyCacheScan() {
        if (supplyCacheScanner == null) return;
        SupplyRegionScanner.ScanResult result = supplyCacheScanner.scan(supplyScanPositionsPerTick.get());
        if (!result.done() || projectionFingerprint == null || restockRegionFingerprint == null) return;
        List<BlockPos> containers = supplyCacheScanner.containers();
        sessionCache.recordSupplyContainers(
            restockRegionFingerprint, containers, supplyCacheScanner.completeCoverage()
        );
        for (Map.Entry<BlockPos, Map<Item, Integer>> entry : supplyCacheScanner.visibleContents().entrySet()) {
            sessionCache.recordContainer(restockRegionFingerprint, entry.getKey(), entry.getValue());
        }
        sessionCache.flush();
        supplyCacheCoverageComplete = supplyCacheScanner.completeCoverage();
        lastSupplyCacheScanChunkX = mc.player.getBlockX() >> 4;
        lastSupplyCacheScanChunkZ = mc.player.getBlockZ() >> 4;
        debugLog.info(
            "supply_cache_scan_complete", "containers", containers.size(),
            "complete", supplyCacheScanner.completeCoverage(),
            "visible_contents", supplyCacheScanner.visibleContents().size()
        );
        supplyCacheScanner = null;
    }

    private void resetRuntime(boolean restoreDependencies) {
        if (cacheEnabled.get()) sessionCache.flush();
        if (navigator != null) {
            if (restoreDependencies) navigator.restore();
            else navigator.cancel();
        }
        containerSession.reset();
        inventory.close();
        navigator = null;
        selection = null;
        projectionScan = null;
        auditScan = null;
        requirementScan = null;
        layerRequirement = null;
        supplyScanner = null;
        supplyCacheScanner = null;
        pending.clear();
        batchPlanner.clear();
        allTargets.clear();
        deferred.clear();
        deferredPositions.clear();
        awaiting.clear();
        placementAttempts.clear();
        retryAfterTick.clear();
        parkedPlacements.clear();
        screenGuards.clear();
        manualCorrections.clear();
        missing.clear();
        excess.clear();
        supplyGoals.clear();
        restockServedStacks.clear();
        projectionMaterials.clear();
        supplyContainers.clear();
        allSupplyContainers.clear();
        restockPlanningWindow = List.of();
        supplyContainerAttempts.clear();
        miningTarget = null;
        pathActionTarget = null;
        pathNavigationPlan = null;
        currentSupplyContainer = null;
        lastBuildPosition = null;
        nextPlan = null;
        projectionFingerprint = null;
        activeLayerFilter = LitematicaProjection.LayerFilter.all();
        activeLayerSignature = "ALL";
        announcedLayerSignature = null;
        restockRegionFingerprint = null;
        sessionWorld = null;
        requirementPurpose = null;
        restockPhase = RestockPhase.TAKE_REQUIRED;
        layerStatusDirty = true;
        projectionScanCompleteLogged = false;
        suspendedRuntime = false;
        resumeRestockAfterCleanup = false;
        supplyCacheCoverageComplete = false;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        lastSupplyCacheScanChunkX = Integer.MIN_VALUE;
        lastSupplyCacheScanChunkZ = Integer.MIN_VALUE;
        stage = Stage.IDLE;
        totalTicks = 0;
        lastPlacementTick = Integer.MIN_VALUE / 2;
        closeUnexpectedScreen = false;
        debugLog.flush();
    }

    private void closeUnexpectedScreen() {
        if (!closeUnexpectedScreen || mc.player == null) return;
        closeUnexpectedScreen = false;
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.player.closeHandledScreen();
    }

    private boolean isRestockStage() {
        return stage == Stage.SCANNING_SUPPLIES
            || stage == Stage.NEXT_CONTAINER
            || stage == Stage.OPENING_CONTAINER
            || stage == Stage.RETURNING_SUPPLIES
            || stage == Stage.TAKING_SUPPLIES
            || stage == Stage.WAITING_SUPPLY_LOAD;
    }

    private Setting<Integer> coordinate(String name) {
        return sgRestock.add(new IntSetting.Builder()
            .name(name)
            .description("Supply cuboid coordinate " + name + ".")
            .defaultValue(0)
            .min(-MAX_COORDINATE)
            .max(MAX_COORDINATE)
            .noSlider()
            .visible(() -> false)
            .build()
        );
    }

    private Setting<Boolean> storedCorner(String name) {
        return sgRestock.add(new BoolSetting.Builder()
            .name(name)
            .description("Stores whether this restock corner has been selected.")
            .defaultValue(false)
            .visible(() -> false)
            .build()
        );
    }

    private void migrateStoredSupplySelection() {
        if (!restockCorner1Stored.get() && !restockCorner2Stored.get() && supplyVolume() >= 2L) {
            restockCorner1Stored.set(true);
            restockCorner2Stored.set(true);
        }
    }

    private boolean hasSupplyRegionSelection() {
        return validSupplySelection(
            restockCorner1Stored.get(),
            restockCorner2Stored.get(),
            new BlockPos(restockX1.get(), restockY1.get(), restockZ1.get()),
            new BlockPos(restockX2.get(), restockY2.get(), restockZ2.get())
        );
    }

    static boolean validSupplySelection(boolean firstSelected, boolean secondSelected, BlockPos first, BlockPos second) {
        return firstSelected && secondSelected && supplyVolume(first, second) >= 2L;
    }

    private static String projectionFailureKey(LitematicaProjection.Failure failure) {
        return switch (failure) {
            case MISSING_LITEMATICA -> "error.wavexin.litematica_printer.projection_missing_litematica";
            case WRONG_MINECRAFT_VERSION -> "error.wavexin.litematica_printer.projection_wrong_minecraft_version";
            case WRONG_LITEMATICA_VERSION -> "error.wavexin.litematica_printer.projection_wrong_litematica_version";
            case NO_SELECTED_PLACEMENT -> "error.wavexin.litematica_printer.projection_no_selected_placement";
            case DISABLED_PLACEMENT -> "error.wavexin.litematica_printer.projection_disabled_placement";
            case EMPTY_PLACEMENT -> "error.wavexin.litematica_printer.projection_empty_placement";
            case API_UNAVAILABLE -> "error.wavexin.litematica_printer.projection_api_unavailable";
        };
    }

    private static String detail(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private enum Stage {
        IDLE,
        LOADING_PROJECTION,
        BUILDING,
        PATHING,
        MINING,
        SCANNING_SUPPLIES,
        NEXT_CONTAINER,
        OPENING_CONTAINER,
        RETURNING_SUPPLIES,
        TAKING_SUPPLIES,
        SCANNING_REQUIREMENTS,
        WAITING_SUPPLY_LOAD,
        AUDITING,
        PAUSED
    }

    private enum PathPurpose {
        BUILD,
        MINE,
        SUPPLY_SCAN,
        CONTAINER,
        PROJECTION_SCAN,
        AUDIT
    }

    private enum RestockPhase {
        RETURN_EXCESS,
        TAKE_REQUIRED
    }

    private enum RequirementPurpose {
        RESTOCK,
        LAYER_COMPLETION
    }

    private record AwaitingPlacement(ProjectionScan.Target target, int sentAt, int attempts) {
    }

    private record ParkedPlacement(boolean leftRange, int retryAt) {
    }

    private record DeferredIssue(BlockPos pos, String reason) {
    }

    private static final class CacheButtonSetting extends Setting<Boolean> {
        private final Runnable action;

        private CacheButtonSetting(
            String name,
            String description,
            Runnable action,
            Consumer<Boolean> onChanged,
            Consumer<Setting<Boolean>> onModuleActivated,
            IVisible visible
        ) {
            super(name, description, false, onChanged, onModuleActivated, visible);
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

        private static final class Builder extends SettingBuilder<Builder, Boolean, CacheButtonSetting> {
            private Runnable action;

            private Builder() {
                super(false);
            }

            private Builder action(Runnable action) {
                this.action = action;
                return this;
            }

            @Override
            public CacheButtonSetting build() {
                return new CacheButtonSetting(name, description, action, onChanged, onModuleActivated, visible);
            }
        }
    }

    private static final class SelectionHelpSetting extends Setting<Boolean> {
        private SelectionHelpSetting(
            String name,
            String description,
            Consumer<Boolean> onChanged,
            Consumer<Setting<Boolean>> onModuleActivated,
            IVisible visible
        ) {
            super(name, description, false, onChanged, onModuleActivated, visible);
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

        private static final class Builder extends SettingBuilder<Builder, Boolean, SelectionHelpSetting> {
            private Builder() {
                super(false);
            }

            @Override
            public SelectionHelpSetting build() {
                return new SelectionHelpSetting(name, description, onChanged, onModuleActivated, visible);
            }
        }
    }
}
