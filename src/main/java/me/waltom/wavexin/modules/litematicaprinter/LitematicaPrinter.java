package me.waltom.wavexin.modules.litematicaprinter;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinModule;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
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

public final class LitematicaPrinter extends WaveXinModule {
    private static final String MINECRAFT_VERSION = "1.21.1";
    private static final int MAX_COORDINATE = 30_000_000;

    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgRestock = settings.createGroup("Restock Region");

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

    private final Setting<Integer> placementsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("Placements Per Tick")
        .description("Maximum block interaction packets sent per tick.")
        .defaultValue(4)
        .min(1)
        .max(20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Double> interactionRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("Interaction Range")
        .description("Maximum range used for placing, mining, and opening containers.")
        .defaultValue(4.5)
        .min(2.0)
        .max(6.0)
        .sliderRange(2.0, 6.0)
        .build()
    );

    private final Setting<Integer> actionTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("Action Timeout")
        .description("Ticks to wait for a placement, mining action, or container to respond.")
        .defaultValue(80)
        .min(20)
        .max(400)
        .sliderRange(20, 200)
        .build()
    );

    private final Setting<Integer> pathTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("Path Timeout")
        .description("Ticks allowed for each Baritone approach before trying another stand position.")
        .defaultValue(400)
        .min(100)
        .max(2400)
        .sliderRange(100, 1200)
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

    private final PrinterInventory inventory = new PrinterInventory();
    private final PrinterPlacement placement = new PrinterPlacement(inventory);
    private final SupplyContainerSession containerSession = new SupplyContainerSession();
    private final Deque<ProjectionScan.Target> pending = new ArrayDeque<>();
    private final List<ProjectionScan.Target> allTargets = new ArrayList<>();
    private final List<DeferredIssue> deferred = new ArrayList<>();
    private final Set<BlockPos> deferredPositions = new HashSet<>();
    private final Map<BlockPos, AwaitingPlacement> awaiting = new HashMap<>();
    private final Map<BlockPos, Integer> placementAttempts = new HashMap<>();
    private final Map<Item, Integer> missing = new LinkedHashMap<>();
    private final Map<Item, Integer> supplyGoals = new LinkedHashMap<>();
    private final Deque<BlockPos> supplyContainers = new ArrayDeque<>();
    private final Deque<BlockPos> pathGoals = new ArrayDeque<>();

    private PrinterNavigator navigator;
    private LitematicaProjection.Selection selection;
    private ProjectionScan projectionScan;
    private SupplyRegionScanner supplyScanner;
    private Stage stage = Stage.IDLE;
    private Stage pathResume;
    private PathPurpose pathPurpose;
    private ProjectionScan.Target miningTarget;
    private ProjectionScan.Target pathBuildTarget;
    private BlockPos pathActionTarget;
    private BlockPos currentSupplyContainer;
    private int stageTicks;
    private int totalTicks;
    private int roundRemaining;
    private boolean roundProgress;
    private boolean pathLoadOnly;
    private int pathAttempts;
    private int inaccessibleSupplyContainers;
    private int auditIndex;
    private int exactAuditFailures;
    private int entityCount;
    private int blockEntityCount;

    public LitematicaPrinter() {
        super(WaveXinAddon.CATEGORY, "litematica-printer", "Builds the selected Litematica projection with packet placement and Baritone navigation.");
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
        if (!projectionScan.isDone()) return;

        allTargets.addAll(projectionScan.targets());
        allTargets.sort((first, second) -> {
            int y = Integer.compare(first.pos().getY(), second.pos().getY());
            if (y != 0) return y;
            return Double.compare(first.pos().getSquaredDistance(mc.player.getBlockPos()), second.pos().getSquaredDistance(mc.player.getBlockPos()));
        });
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

        int placed = 0;
        int inspections = Math.max(128, placementsPerTick.get() * 32);
        for (int checked = 0; checked < inspections && roundRemaining > 0; checked++) {
            ProjectionScan.Target target = pending.removeFirst();
            roundRemaining--;
            BlockState actual = mc.world.getBlockState(target.pos());
            if (PrinterState.compatibleDuringBuild(target.state(), actual)) {
                awaiting.remove(target.pos());
                placementAttempts.remove(target.pos());
                roundProgress = true;
                continue;
            }

            pending.addLast(target);
            AwaitingPlacement wait = awaiting.get(target.pos());
            if (wait != null && totalTicks - wait.sentAt() <= actionTimeout.get()) continue;
            if (!isLoaded(target.pos())) {
                startPath(target.pos(), target, Stage.BUILDING, PathPurpose.BUILD, true);
                return;
            }

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
        roundRemaining = pending.size();
        roundProgress = false;
        missing.clear();
    }

    private void expirePlacements() {
        List<ProjectionScan.Target> rejected = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, AwaitingPlacement>> iterator = awaiting.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, AwaitingPlacement> entry = iterator.next();
            if (PrinterState.compatibleDuringBuild(entry.getValue().target().state(), mc.world.getBlockState(entry.getKey()))) {
                iterator.remove();
                placementAttempts.remove(entry.getKey());
                roundProgress = true;
            } else if (totalTicks - entry.getValue().sentAt() > actionTimeout.get()) {
                iterator.remove();
                if (entry.getValue().attempts() >= 3) rejected.add(entry.getValue().target());
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
        } else if (stageTicks > actionTimeout.get()) {
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
        if (stageTicks % 3 != 1) return;

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
        inaccessibleSupplyContainers++;
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
        submitNextPathGoal(loadOnly ? 8 : 1);
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
        if (!pathGoals.isEmpty() && pathAttempts < 6) {
            navigator.cancel();
            submitNextPathGoal(1);
            return;
        }

        navigator.cancel();
        if (pathPurpose == PathPurpose.CONTAINER) {
            markContainerInaccessible();
        } else if (pathPurpose == PathPurpose.SUPPLY_SCAN) {
            pauseError("error.wavexin.litematica_printer.supply_region_unreachable", "(highlight)(bold)The supply region could not be reached; restocking is paused.(default)");
        } else {
            skipTarget(pathBuildTarget, "PATH_FAILED");
            if (miningTarget == pathBuildTarget) miningTarget = null;
            setStage(Stage.BUILDING);
        }
    }

    private void startAudit() {
        navigator.cancel();
        inventory.releasePersistent();
        auditIndex = 0;
        exactAuditFailures = 0;
        setStage(Stage.AUDITING);
    }

    private void tickAudit() {
        int limit = Math.min(allTargets.size(), auditIndex + scanPositionsPerTick.get());
        while (auditIndex < limit) {
            ProjectionScan.Target target = allTargets.get(auditIndex++);
            if (!deferredPositions.contains(target.pos())
                && !PrinterState.exact(target.state(), mc.world.getBlockState(target.pos()))) {
                exactAuditFailures++;
            }
        }
        if (auditIndex < allTargets.size()) return;

        int skipped = deferred.size() + exactAuditFailures + entityCount + blockEntityCount;
        if (skipped == 0) {
            infoKey("message.wavexin.litematica_printer.completed", "Litematica Printer completed %d projected block states.", allTargets.size());
        } else {
            errorKey(
                "error.wavexin.litematica_printer.completed_with_skips",
                "(highlight)(bold)All supported blocks are complete. Skipped or mismatched: %d blocks, %d entities, %d block-entity data records.(default)",
                deferred.size() + exactAuditFailures, entityCount, blockEntityCount
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
        if (navigator != null) {
            if (restoreDependencies) navigator.restore();
            else navigator.cancel();
        }
        containerSession.reset();
        inventory.close();
        navigator = null;
        selection = null;
        projectionScan = null;
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
        pathGoals.clear();
        miningTarget = null;
        pathBuildTarget = null;
        pathActionTarget = null;
        currentSupplyContainer = null;
        stage = Stage.IDLE;
        totalTicks = 0;
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
        CONTAINER
    }

    private record AwaitingPlacement(ProjectionScan.Target target, int sentAt, int attempts) {
    }

    private record DeferredIssue(BlockPos pos, String reason) {
    }
}
