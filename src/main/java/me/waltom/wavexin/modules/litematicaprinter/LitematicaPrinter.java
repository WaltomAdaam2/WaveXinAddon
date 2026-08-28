package me.waltom.wavexin.modules.litematicaprinter;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.i18n.WaveXinI18n;
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
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class LitematicaPrinter extends WaveXinModule {
    private static final String MINECRAFT_VERSION = "1.21.1";
    private static final int MAX_COORDINATE = 30_000_000;

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
    }

    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgBuild = settings.createGroup("Build");
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");
    private final SettingGroup sgRestock = settings.createGroup("Restock Region");
    private final SettingGroup sgCache = settings.createGroup("Cache");
    private final SettingGroup sgSupplyRender = settings.createGroup("Supply Region Render");

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
        .defaultValue(5_000_000)
        .min(1_000)
        .max(50_000_000)
        .sliderRange(1_000, 10_000_000)
        .build()
    );

    private final Setting<Integer> placementsPerTick = sgBuild.add(new IntSetting.Builder()
        .name("Blocks Per Tick")
        .description("Maximum block interaction packets sent per tick.")
        .defaultValue(4)
        .min(1)
        .max(20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> buildInLayers = sgBuild.add(new BoolSetting.Builder()
        .name("Build In Layers")
        .description("Builds horizontal layer bands in order, matching Baritone builder semantics.")
        .defaultValue(true)
        .build()
    );

    private final Setting<PrinterBuildOrder.LayerOrder> layerOrder = sgBuild.add(new EnumSetting.Builder<PrinterBuildOrder.LayerOrder>()
        .name("Layer Order")
        .description("Chooses whether layer bands are built from bottom to top or top to bottom.")
        .defaultValue(PrinterBuildOrder.LayerOrder.BottomToTop)
        .visible(buildInLayers::get)
        .build()
    );

    private final Setting<Integer> layerHeight = sgBuild.add(new IntSetting.Builder()
        .name("Layer Height")
        .description("Number of vertical blocks grouped into one build layer.")
        .defaultValue(1)
        .min(1)
        .max(16)
        .sliderRange(1, 8)
        .visible(buildInLayers::get)
        .build()
    );

    private final Setting<PrinterBuildOrder.RowAxis> rowAxis = sgBuild.add(new EnumSetting.Builder<PrinterBuildOrder.RowAxis>()
        .name("Row Axis")
        .description("Build row direction; Automatic chooses the longer horizontal axis.")
        .defaultValue(PrinterBuildOrder.RowAxis.Automatic)
        .build()
    );

    private final Setting<Integer> placementDelay = sgBuild.add(new IntSetting.Builder()
        .name("Placement Delay")
        .description("Minimum ticks between placement batches.")
        .defaultValue(0)
        .min(0)
        .max(20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> maxPlacementRetries = sgBuild.add(new IntSetting.Builder()
        .name("Placement Retries")
        .description("Maximum placement attempts before a target is deferred.")
        .defaultValue(3)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Double> interactionRange = sgBuild.add(new DoubleSetting.Builder()
        .name("Interaction Range")
        .description("Maximum range used for placing, mining, and opening containers.")
        .defaultValue(4.5)
        .min(2.0)
        .max(6.0)
        .sliderRange(2.0, 6.0)
        .build()
    );

    private final Setting<Integer> actionTimeout = sgBuild.add(new IntSetting.Builder()
        .name("Action Timeout")
        .description("Ticks to wait for a placement, mining action, or container to respond.")
        .defaultValue(80)
        .min(20)
        .max(400)
        .sliderRange(20, 200)
        .build()
    );

    private final Setting<Integer> pathTimeout = sgNavigation.add(new IntSetting.Builder()
        .name("Path Timeout")
        .description("Ticks allowed for each Baritone approach before trying another stand position.")
        .defaultValue(400)
        .min(100)
        .max(2400)
        .sliderRange(100, 1200)
        .build()
    );

    private final Setting<Integer> pathRetries = sgNavigation.add(new IntSetting.Builder()
        .name("Path Retries")
        .description("Maximum Baritone stand-position attempts for one target.")
        .defaultValue(6)
        .min(1)
        .max(20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> chunkLoadGoalRange = sgNavigation.add(new IntSetting.Builder()
        .name("Chunk Load Goal Range")
        .description("Baritone goal radius used when loading projection or supply chunks.")
        .defaultValue(8)
        .min(1)
        .max(32)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Boolean> loadProjectionChunks = sgNavigation.add(new BoolSetting.Builder()
        .name("Load Projection Chunks")
        .description("Uses Baritone to load unknown projection chunks before scanning or completion checks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> restockEnabled = sgRestock.add(new BoolSetting.Builder()
        .name("Enabled")
        .description("Fetches missing materials from placed containers inside the configured cuboid.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> restockX1 = coordinate("X1");
    private final Setting<Integer> restockY1 = coordinate("Y1");
    private final Setting<Integer> restockZ1 = coordinate("Z1");
    private final Setting<Integer> restockX2 = coordinate("X2");
    private final Setting<Integer> restockY2 = coordinate("Y2");
    private final Setting<Integer> restockZ2 = coordinate("Z2");

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
        .description("Ticks between inventory transfers while taking restock materials.")
        .defaultValue(3)
        .min(1)
        .max(20)
        .sliderRange(1, 10)
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

    private final PrinterInventory inventory = new PrinterInventory();
    private final PrinterPlacement placement = new PrinterPlacement(inventory);
    private final PrinterSessionCache sessionCache = new PrinterSessionCache();
    private final SupplyContainerSession containerSession = new SupplyContainerSession();
    private final Deque<ProjectionScan.Target> pending = new ArrayDeque<>();
    private final List<ProjectionScan.Target> allTargets = new ArrayList<>();
    private final List<DeferredIssue> deferred = new ArrayList<>();
    private final Set<BlockPos> deferredPositions = new HashSet<>();
    private final Map<BlockPos, AwaitingPlacement> awaiting = new HashMap<>();
    private final Map<BlockPos, Integer> placementAttempts = new HashMap<>();
    private final Map<Item, Integer> missing = new LinkedHashMap<>();
    private final Map<Item, Integer> supplyGoals = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> supplyContainerAttempts = new HashMap<>();
    private final Deque<BlockPos> supplyContainers = new ArrayDeque<>();
    private final Deque<BlockPos> pathGoals = new ArrayDeque<>();

    private PrinterNavigator navigator;
    private LitematicaProjection.Selection selection;
    private LitematicaProjection.Selection cachedSelection;
    private ProjectionScan projectionScan;
    private ProjectionScan auditScan;
    private SupplyRegionScanner supplyScanner;
    private Stage stage = Stage.IDLE;
    private Stage pathResume;
    private PathPurpose pathPurpose;
    private ProjectionScan.Target miningTarget;
    private ProjectionScan.Target pathBuildTarget;
    private BlockPos pathActionTarget;
    private BlockPos currentSupplyContainer;
    private BlockPos lastBuildPosition;
    private String projectionFingerprint;
    private int stageTicks;
    private int totalTicks;
    private int roundRemaining;
    private boolean roundProgress;
    private boolean pathLoadOnly;
    private int pathAttempts;
    private int inaccessibleSupplyContainers;
    private int exactAuditFailures;
    private int entityCount;
    private int blockEntityCount;
    private boolean supplyCorner1Selected;
    private boolean supplyCorner2Selected;
    private int lastPlacementTick = Integer.MIN_VALUE / 2;

    public LitematicaPrinter() {
        super(WaveXinAddon.CATEGORY, "litematica-printer", "Builds the selected Litematica projection with packet placement and Baritone navigation.");
    }

    public void setSupplyCorner(int corner, BlockPos pos) {
        BlockPos other = corner == 1
            ? new BlockPos(restockX2.get(), restockY2.get(), restockZ2.get())
            : new BlockPos(restockX1.get(), restockY1.get(), restockZ1.get());
        boolean otherSelected = corner == 1 ? supplyCorner2Selected : supplyCorner1Selected;
        if (otherSelected && supplyVolume(pos, other) < 2L) {
            throw new IllegalArgumentException("Supply region must contain at least 2 blocks (1x1x2 minimum)");
        }

        if (corner == 1) {
            restockX1.set(pos.getX());
            restockY1.set(pos.getY());
            restockZ1.set(pos.getZ());
            supplyCorner1Selected = true;
        } else if (corner == 2) {
            restockX2.set(pos.getX());
            restockY2.set(pos.getY());
            restockZ2.set(pos.getZ());
            supplyCorner2Selected = true;
        } else {
            throw new IllegalArgumentException("Supply corner must be 1 or 2");
        }
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
        if (!renderSupplyRegion.get() || mc.world == null) return;
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

    @Override
    public void onActivate() {
        resetRuntime(false);
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            stopWithActivationError("error.wavexin.litematica_printer.world_unavailable", "(highlight)(bold)Litematica Printer requires an active world.(default)");
            return;
        }
        if (!PrinterNavigator.isBaritoneInstalled()) {
            stopWithActivationError("error.wavexin.litematica_printer.baritone_missing", "(highlight)(bold)Baritone for Minecraft 1.21.1 is required.(default)");
            return;
        }

        try {
            navigator = PrinterNavigator.create();
            navigator.configure();
            selection = LitematicaProjection.open(MINECRAFT_VERSION);
            cachedSelection = selection;
            projectionScan = new ProjectionScan(selection, mc.world, maximumProjectionVolume.get());
            entityCount = selection.entityCount();
            blockEntityCount = selection.blockEntityCount();
            inventory.begin();
            stage = Stage.LOADING_PROJECTION;
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
        resetRuntime(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (stage == Stage.IDLE || stage == Stage.PAUSED) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            pauseError("error.wavexin.litematica_printer.world_lost", "(highlight)(bold)World connection was lost; printing is paused.(default)");
            return;
        }
        if (selection != null && !selection.isStillSelected()) {
            clearSessionCache();
            pauseError("error.wavexin.litematica_printer.selection_changed", "(highlight)(bold)The selected Litematica placement changed; printing is paused.(default)");
            return;
        }

        totalTicks++;
        stageTicks++;
        inventory.enforcePriority();
        try {
            switch (stage) {
                case LOADING_PROJECTION -> tickProjectionScan();
                case BUILDING -> tickBuild();
                case PATHING -> tickPath();
                case MINING -> tickMine();
                case SCANNING_SUPPLIES -> tickSupplyScan();
                case NEXT_CONTAINER -> tickNextContainer();
                case OPENING_CONTAINER -> tickOpenContainer();
                case TAKING_SUPPLIES -> tickTakeSupplies();
                case AUDITING -> tickAudit();
                default -> {
                }
            }
        } catch (RuntimeException e) {
            pauseError("error.wavexin.litematica_printer.runtime_failed", "(highlight)(bold)Litematica Printer paused after an unexpected error: %s(default)", detail(e));
        }
    }

    private void tickProjectionScan() {
        projectionScan.scan(scanPositionsPerTick.get());
        if (!projectionScan.isDone()) {
            if (projectionScan.prepareNextChunkRescan()) return;
            BlockPos chunkTarget = projectionScan.nextUnknownChunkTarget(mc.player.getBlockY());
            if (chunkTarget != null) {
                if (loadProjectionChunks.get()) {
                    startPath(chunkTarget, null, Stage.LOADING_PROJECTION, PathPurpose.PROJECTION_SCAN, true);
                } else {
                    pauseError("error.wavexin.litematica_printer.projection_chunk_unloaded", "(highlight)(bold)A projection chunk is not loaded and automatic chunk loading is disabled.(default)");
                }
            }
            return;
        }

        projectionFingerprint = sessionFingerprint(projectionScan.fingerprint());
        if (cacheEnabled.get()) {
            sessionCache.begin(projectionFingerprint);
            sessionCache.recordVerifiedChunks(projectionScan.verifiedChunks());
            lastBuildPosition = sessionCache.lastPosition();
        }
        allTargets.addAll(projectionScan.targets());
        pending.addAll(allTargets);
        for (ProjectionScan.Skipped skipped : projectionScan.skipped()) {
            defer(skipped.pos(), skipped.reason().name());
        }
        projectionScan = null;
        beginRound();
        setStage(Stage.BUILDING);
        infoKey(
            "message.wavexin.litematica_printer.build_started",
            "Projection loaded: %d actionable blocks, %d deferred blocks.",
            pending.size(), deferred.size()
        );
    }

    private void tickBuild() {
        expirePlacements();
        if (pending.isEmpty()) {
            startAudit();
            return;
        }
        if (roundRemaining <= 0) {
            finishRound();
            return;
        }
        if (totalTicks - lastPlacementTick <= placementDelay.get()) return;

        int placed = 0;
        int inspections = Math.max(128, placementsPerTick.get() * 32);
        for (int checked = 0; checked < inspections && roundRemaining > 0; checked++) {
            ProjectionScan.Target target = pending.removeFirst();
            roundRemaining--;
            if (!isLoaded(target.pos())) {
                pending.addLast(target);
                startPath(target.pos(), target, Stage.BUILDING, PathPurpose.BUILD, true);
                return;
            }
            BlockState actual = mc.world.getBlockState(target.pos());
            if (PrinterState.compatibleDuringBuild(target.state(), actual)) {
                awaiting.remove(target.pos());
                placementAttempts.remove(target.pos());
                confirmCompleted(target, actual);
                roundProgress = true;
                continue;
            }

            pending.addLast(target);
            AwaitingPlacement wait = awaiting.get(target.pos());
            if (wait != null && totalTicks - wait.sentAt() <= actionTimeout.get()) continue;
            if (!actual.isReplaceable()) {
                startMining(target);
                return;
            }

            if (target.state().isAir()) {
                startMining(target);
                return;
            }

            Item item = target.state().getBlock().asItem();
            if (inventory.count(item) <= 0) {
                missing.merge(item, 1, Integer::sum);
                continue;
            }

            PrinterPlacement.Candidate anywhere = placement.findCandidate(target.pos(), target.state(), interactionRange.get(), false);
            if (anywhere == null) continue;

            PrinterPlacement.Candidate reachable = placement.findCandidate(target.pos(), target.state(), interactionRange.get(), true);
            if (reachable == null) {
                startPath(target.pos(), target, Stage.BUILDING, PathPurpose.BUILD, false);
                return;
            }
            if (placement.place(target.state(), reachable)) {
                int attempts = placementAttempts.merge(target.pos(), 1, Integer::sum);
                awaiting.put(target.pos(), new AwaitingPlacement(target, totalTicks, attempts));
                lastPlacementTick = totalTicks;
                lastBuildPosition = target.pos();
                placed++;
                if (placed >= placementsPerTick.get()) return;
            }
        }
        if (roundRemaining <= 0) finishRound();
    }

    private void finishRound() {
        if (pending.isEmpty()) {
            startAudit();
        } else if (roundProgress || !awaiting.isEmpty()) {
            beginRound();
        } else if (!missing.isEmpty()) {
            if (restockEnabled.get()) startSupplyScan();
            else pauseMissing("error.wavexin.litematica_printer.restock_disabled", "(highlight)(bold)Missing materials and restocking is disabled: %s(default)");
        } else {
            while (!pending.isEmpty()) {
                ProjectionScan.Target target = pending.removeFirst();
                defer(target.pos(), "UNSUPPORTED_OR_FLOATING");
            }
            startAudit();
        }
    }

    private void beginRound() {
        if (!pending.isEmpty()) {
            BlockPos cursor = lastBuildPosition == null ? mc.player.getBlockPos() : lastBuildPosition;
            List<ProjectionScan.Target> ordered = PrinterBuildOrder.order(
                pending, cursor, this::isSolidSupport,
                buildInLayers.get(), layerHeight.get(), layerOrder.get(), rowAxis.get()
            );
            pending.clear();
            pending.addAll(ordered);
        }
        roundRemaining = pending.size();
        roundProgress = false;
        missing.clear();
    }

    private void expirePlacements() {
        List<ProjectionScan.Target> rejected = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, AwaitingPlacement>> iterator = awaiting.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, AwaitingPlacement> entry = iterator.next();
            if (!isLoaded(entry.getKey())) continue;
            if (PrinterState.compatibleDuringBuild(entry.getValue().target().state(), mc.world.getBlockState(entry.getKey()))) {
                confirmCompleted(entry.getValue().target(), mc.world.getBlockState(entry.getKey()));
                iterator.remove();
                placementAttempts.remove(entry.getKey());
                roundProgress = true;
            } else if (totalTicks - entry.getValue().sentAt() > actionTimeout.get()) {
                iterator.remove();
                if (entry.getValue().attempts() >= maxPlacementRetries.get()) rejected.add(entry.getValue().target());
            }
        }
        for (ProjectionScan.Target target : rejected) {
            skipTarget(target, "PLACEMENT_REJECTED");
            roundProgress = true;
        }
    }

    private void startMining(ProjectionScan.Target target) {
        miningTarget = target;
        if (!inReach(target.pos())) {
            startPath(target.pos(), target, Stage.MINING, PathPurpose.MINE, false);
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
            if (miningTarget.state().isAir()) confirmCompleted(miningTarget, actual);
            inventory.releasePersistent();
            miningTarget = null;
            roundProgress = true;
            setStage(Stage.BUILDING);
            return;
        }
        if (!inReach(miningTarget.pos())) {
            inventory.releasePersistent();
            startPath(miningTarget.pos(), miningTarget, Stage.MINING, PathPurpose.MINE, false);
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

    private void startSupplyScan() {
        BlockPos pos1 = new BlockPos(restockX1.get(), restockY1.get(), restockZ1.get());
        BlockPos pos2 = new BlockPos(restockX2.get(), restockY2.get(), restockZ2.get());
        if (supplyVolume() < 2L) {
            pauseError(
                "error.wavexin.litematica_printer.supply_region_too_small",
                "(highlight)(bold)The supply region must contain at least 2 blocks (1x1x2 minimum).(default)"
            );
            return;
        }
        try {
            supplyScanner = new SupplyRegionScanner((ClientWorld) mc.world, pos1, pos2, maximumSupplyVolume.get());
        } catch (IllegalArgumentException e) {
            pauseError("error.wavexin.litematica_printer.supply_region_too_large", "(highlight)(bold)%s(default)", e.getMessage());
            return;
        }

        supplyGoals.clear();
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            supplyGoals.put(entry.getKey(), inventory.count(entry.getKey()) + entry.getValue());
        }
        supplyContainers.clear();
        supplyContainerAttempts.clear();
        inaccessibleSupplyContainers = 0;
        containerSession.reset();
        setStage(Stage.SCANNING_SUPPLIES);
        infoKey("message.wavexin.litematica_printer.restock_started", "Scanning the configured supply cuboid for: %s", formatMissing());
    }

    private void tickSupplyScan() {
        SupplyRegionScanner.ScanResult result = supplyScanner.scan(supplyScanPositionsPerTick.get());
        if (result.chunkLoadTarget() != null) {
            startPath(result.chunkLoadTarget(), null, Stage.SCANNING_SUPPLIES, PathPurpose.SUPPLY_SCAN, true);
            return;
        }
        if (!result.done()) return;
        supplyContainers.addAll(supplyScanner.containers());
        setStage(Stage.NEXT_CONTAINER);
    }

    private void tickNextContainer() {
        updateSupplyMissing();
        if (missing.isEmpty()) {
            finishRestock();
            return;
        }
        currentSupplyContainer = supplyContainers.pollFirst();
        if (currentSupplyContainer == null) {
            if (inaccessibleSupplyContainers > 0) {
                pauseMissing(
                    "error.wavexin.litematica_printer.container_inaccessible",
                    "(highlight)(bold)Required materials could not be obtained; %d supply containers were inaccessible or blocked: %s(default)",
                    inaccessibleSupplyContainers
                );
            } else {
                pauseMissing("error.wavexin.litematica_printer.material_not_found", "(highlight)(bold)Required materials were not found in accessible supply containers: %s(default)");
            }
            return;
        }
        if (!inReach(currentSupplyContainer)) {
            startPath(currentSupplyContainer, null, Stage.OPENING_CONTAINER, PathPurpose.CONTAINER, false);
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
            startPath(currentSupplyContainer, null, Stage.OPENING_CONTAINER, PathPurpose.CONTAINER, false);
            return;
        }
        if (stageTicks == 1 && !containerSession.open(currentSupplyContainer)) {
            markContainerInaccessible();
            return;
        }
        if (containerSession.isOpen()) {
            setStage(Stage.TAKING_SUPPLIES);
        } else if (stageTicks > containerTimeout.get()) {
            markContainerInaccessible();
        }
    }

    private void tickTakeSupplies() {
        updateSupplyMissing();
        if (missing.isEmpty()) {
            containerSession.close();
            finishRestock();
            return;
        }
        if (!containerSession.isOpen()) {
            markContainerInaccessible();
            return;
        }
        if (stageTicks % containerMoveDelay.get() != 1 % containerMoveDelay.get()) return;

        SupplyContainerSession.TakeResult result = containerSession.take(missing);
        if (result == SupplyContainerSession.TakeResult.NO_MATCH) {
            containerSession.close();
            setStage(Stage.NEXT_CONTAINER);
        } else if (result == SupplyContainerSession.TakeResult.INVENTORY_FULL) {
            pauseMissing("error.wavexin.litematica_printer.inventory_full", "(highlight)(bold)The player inventory cannot accept required supply items: %s(default)");
        } else if (result == SupplyContainerSession.TakeResult.NOT_OPEN) {
            markContainerInaccessible();
        }
    }

    private void markContainerInaccessible() {
        containerSession.close();
        int attempts = supplyContainerAttempts.merge(currentSupplyContainer, 1, Integer::sum);
        if (attempts < containerRetries.get()) supplyContainers.addLast(currentSupplyContainer);
        else inaccessibleSupplyContainers++;
        currentSupplyContainer = null;
        setStage(Stage.NEXT_CONTAINER);
    }

    private void finishRestock() {
        containerSession.close();
        supplyScanner = null;
        supplyGoals.clear();
        currentSupplyContainer = null;
        beginRound();
        setStage(Stage.BUILDING);
        infoKey("message.wavexin.litematica_printer.restock_completed", "Required materials were moved into the player inventory.");
    }

    private void updateSupplyMissing() {
        missing.clear();
        for (Map.Entry<Item, Integer> entry : supplyGoals.entrySet()) {
            int remaining = entry.getValue() - inventory.count(entry.getKey());
            if (remaining > 0) missing.put(entry.getKey(), remaining);
        }
    }

    private void startPath(BlockPos actionTarget, ProjectionScan.Target buildTarget, Stage resume, PathPurpose purpose, boolean loadOnly) {
        navigator.cancel();
        pathActionTarget = actionTarget.toImmutable();
        pathBuildTarget = buildTarget;
        pathResume = resume;
        pathPurpose = purpose;
        pathLoadOnly = loadOnly;
        pathAttempts = 0;
        pathGoals.clear();
        if (!loadOnly && isLoaded(actionTarget)) pathGoals.addAll(PrinterStandPlanner.find(actionTarget, interactionRange.get()));
        if (pathGoals.isEmpty()) pathGoals.add(actionTarget.toImmutable());
        submitNextPathGoal(loadOnly ? chunkLoadGoalRange.get() : 1);
        setStage(Stage.PATHING);
    }

    private void submitNextPathGoal(int range) {
        BlockPos goal = pathGoals.pollFirst();
        if (goal == null) return;
        pathAttempts++;
        navigator.goTo(goal, range);
        stageTicks = 0;
    }

    private void tickPath() {
        if ((pathLoadOnly && isLoaded(pathActionTarget)) || (!pathLoadOnly && inReach(pathActionTarget))) {
            navigator.cancel();
            setStage(pathResume);
            return;
        }
        if (stageTicks <= pathTimeout.get() && (stageTicks <= 20 || navigator.isNavigating())) return;
        if (pathAttempts < pathRetries.get()) {
            navigator.cancel();
            if (!pathGoals.isEmpty()) {
                submitNextPathGoal(pathLoadOnly ? chunkLoadGoalRange.get() : 1);
                return;
            } else if (pathLoadOnly) {
                pathAttempts++;
                navigator.goTo(pathActionTarget, chunkLoadGoalRange.get());
                stageTicks = 0;
                return;
            }
        }

        navigator.cancel();
        if (pathPurpose == PathPurpose.CONTAINER) {
            markContainerInaccessible();
        } else if (pathPurpose == PathPurpose.SUPPLY_SCAN) {
            pauseError("error.wavexin.litematica_printer.supply_region_unreachable", "(highlight)(bold)The supply region could not be reached; restocking is paused.(default)");
        } else if (pathPurpose == PathPurpose.PROJECTION_SCAN || pathPurpose == PathPurpose.AUDIT) {
            pauseError("error.wavexin.litematica_printer.projection_chunk_unreachable", "(highlight)(bold)A projection chunk could not be loaded; printing is paused.(default)");
        } else {
            skipTarget(pathBuildTarget, "PATH_FAILED");
            if (miningTarget == pathBuildTarget) miningTarget = null;
            setStage(Stage.BUILDING);
        }
    }

    private void startAudit() {
        navigator.cancel();
        inventory.releasePersistent();
        exactAuditFailures = 0;
        auditScan = new ProjectionScan(selection, mc.world, maximumProjectionVolume.get());
        setStage(Stage.AUDITING);
    }

    private void tickAudit() {
        auditScan.scan(scanPositionsPerTick.get());
        if (!auditScan.isDone()) {
            if (auditScan.prepareNextChunkRescan()) return;
            BlockPos chunkTarget = auditScan.nextUnknownChunkTarget(mc.player.getBlockY());
            if (chunkTarget != null) {
                if (loadProjectionChunks.get()) {
                    startPath(chunkTarget, null, Stage.AUDITING, PathPurpose.AUDIT, true);
                } else {
                    pauseError("error.wavexin.litematica_printer.projection_chunk_unloaded", "(highlight)(bold)A projection chunk is not loaded and automatic chunk loading is disabled.(default)");
                }
            }
            return;
        }

        if (!sessionFingerprint(auditScan.fingerprint()).equals(projectionFingerprint)) {
            clearSessionCache();
            pauseError("error.wavexin.litematica_printer.selection_changed", "(highlight)(bold)The selected Litematica placement changed; printing is paused.(default)");
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
        pending.remove(target);
        awaiting.remove(target.pos());
        placementAttempts.remove(target.pos());
        defer(target.pos(), reason);
        roundRemaining = Math.min(roundRemaining, pending.size());
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
    }

    void monitorSessionCache() {
        if (cachedSelection != null && !cachedSelection.isStillSelected()) clearSessionCache();
    }

    private void clearSessionCacheFromButton() {
        clearSessionCache();
        infoKey("message.wavexin.litematica_printer.cache_cleared", "Litematica Printer session cache cleared.");
    }

    private void pauseMissing(String key, String fallback, Object... beforeMissing) {
        Object[] args = new Object[beforeMissing.length + 1];
        System.arraycopy(beforeMissing, 0, args, 0, beforeMissing.length);
        args[beforeMissing.length] = formatMissing();
        pauseError(key, fallback, args);
    }

    private String formatMissing() {
        if (missing.isEmpty()) return "none";
        StringBuilder result = new StringBuilder();
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            if (!result.isEmpty()) result.append(", ");
            result.append(Registries.ITEM.getId(entry.getKey())).append(" x").append(entry.getValue());
        }
        return result.toString();
    }

    private void pauseError(String key, String fallback, Object... args) {
        if (navigator != null) navigator.restore();
        inventory.close();
        containerSession.close();
        setStage(Stage.PAUSED);
        errorKey(key, fallback, args);
    }

    private void stopWithActivationError(String key, String fallback, Object... args) {
        errorKey(key, fallback, args);
        if (isActive()) toggle();
    }

    private void setStage(Stage next) {
        stage = next;
        stageTicks = 0;
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
        supplyScanner = null;
        pending.clear();
        allTargets.clear();
        deferred.clear();
        deferredPositions.clear();
        awaiting.clear();
        placementAttempts.clear();
        missing.clear();
        supplyGoals.clear();
        supplyContainers.clear();
        supplyContainerAttempts.clear();
        pathGoals.clear();
        miningTarget = null;
        pathBuildTarget = null;
        pathActionTarget = null;
        currentSupplyContainer = null;
        lastBuildPosition = null;
        projectionFingerprint = null;
        stage = Stage.IDLE;
        totalTicks = 0;
        lastPlacementTick = Integer.MIN_VALUE / 2;
    }

    private Setting<Integer> coordinate(String name) {
        return sgRestock.add(new IntSetting.Builder()
            .name(name)
            .description("Supply cuboid coordinate " + name + ".")
            .defaultValue(0)
            .min(-MAX_COORDINATE)
            .max(MAX_COORDINATE)
            .noSlider()
            .build()
        );
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
        TAKING_SUPPLIES,
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

    private record AwaitingPlacement(ProjectionScan.Target target, int sentAt, int attempts) {
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
}
