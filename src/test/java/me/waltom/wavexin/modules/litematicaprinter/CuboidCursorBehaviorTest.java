package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.block.enums.ChestType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

public final class CuboidCursorBehaviorTest {
    private CuboidCursorBehaviorTest() {
    }

    public static void main(String[] args) {
        CuboidCursor cursor = new CuboidCursor(2, 5, 1, 1, 5, 0);
        List<CuboidCursor.Position> positions = new ArrayList<>();
        while (cursor.hasNext()) positions.add(cursor.next());

        check(cursor.volume() == 4, "reversed coordinates must form a four-block cuboid");
        check(positions.equals(List.of(
            new CuboidCursor.Position(1, 5, 0),
            new CuboidCursor.Position(2, 5, 0),
            new CuboidCursor.Position(1, 5, 1),
            new CuboidCursor.Position(2, 5, 1)
        )), "cursor order must be stable and inclusive");

        cursor.reset();
        check(cursor.hasNext(), "reset must make the cursor reusable");
        check(cursor.next().equals(new CuboidCursor.Position(1, 5, 0)), "reset must return to the minimum corner");

        verifyChestProvisionalState();
        verifyChestPairAndHopperGeometry();
        verifySupplyVolume();
        verifyLayeredRowOrder();
        verifyUnsupportedTargetsAreDeferred();
        verifyPlannerActionPriority();
        verifyDynamicActionableLayer();
        verifyDependencyAndBatchSupportChain();
        verifyAwaitingAndReplacementAreNotSupports();
        verifyPathAndRestockPlans();
        verifyPlacementOnlyPlanner();
        verifyInventoryAndWholeStackRules();
        verifyFullInventoryRestockPlan();
        verifyManualBuilderUsesAvailableMaterialsFirst();
        verifyLayerAndRetryRules();
        verifyCachedRestockKnowledge();
        verifyPlanReplacement();
        verifyDebugLog();
        verifyReleaseDefaults();
    }

    private static void verifyReleaseDefaults() {
        check(LitematicaPrinter.DEFAULT_MAXIMUM_PROJECTION_VOLUME == 10_000_000,
            "default projection volume must remain ten million blocks");
        check(LitematicaPrinter.MAXIMUM_PROJECTION_VOLUME == 500_000_000,
            "maximum projection volume must remain five hundred million blocks");
    }

    private static void verifyChestProvisionalState() {
        check(PrinterState.provisionalChest(ChestType.LEFT, ChestType.SINGLE, Direction.NORTH, Direction.NORTH),
            "first double-chest half may be provisional");
        check(PrinterState.provisionalChest(ChestType.RIGHT, ChestType.SINGLE, Direction.NORTH, Direction.NORTH),
            "either double-chest half may be provisional");
        check(!PrinterState.provisionalChest(ChestType.SINGLE, ChestType.LEFT, Direction.NORTH, Direction.NORTH),
            "an unwanted chest merge must not be provisional");
        check(!PrinterState.provisionalChest(ChestType.LEFT, ChestType.SINGLE, Direction.NORTH, Direction.SOUTH),
            "provisional chest facing must match");

        check(!PrinterState.confirmedChestProperties(
                ChestType.LEFT, ChestType.SINGLE, Direction.NORTH, Direction.NORTH),
            "a provisional single chest must not be removed from pending or cached as complete");
        check(PrinterState.confirmedChestProperties(
                ChestType.LEFT, ChestType.LEFT, Direction.NORTH, Direction.NORTH),
            "a double-chest half is complete only after its exact type and facing are present");
    }

    private static void verifyChestPairAndHopperGeometry() {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            Direction connection = PrinterState.chestConnection(facing, ChestType.LEFT);
            check(PrinterState.chestPair(ChestType.LEFT, ChestType.RIGHT, facing, connection),
                "matching left/right chest halves must be recognized for " + facing);
            check(!PrinterState.chestPair(ChestType.LEFT, ChestType.RIGHT, facing, connection.getOpposite()),
                "double-chest pair direction must be exact for " + facing);
        }

        BlockPos target = new BlockPos(4, 5, 6);
        for (Direction facing : Direction.values()) {
            if (facing == Direction.UP) {
                check(!PrinterPlacement.validHopperFacing(facing), "upward hoppers must be rejected");
                continue;
            }
            check(PrinterPlacement.validHopperFacing(facing), "downward and horizontal hopper facings must be legal");
            check(PrinterPlacement.hopperSupport(target, facing).equals(target.offset(facing)),
                "hopper support must lie in its output direction");
            check(PrinterPlacement.hopperClickedSide(facing) == facing.getOpposite(),
                "hopper must click the support face opposite its output direction");
        }
        check(PrinterPlacement.sameRotation(179.0F, -181.0F),
            "equivalent wrapped yaw must not trigger a temporary rotation packet");
    }

    private static void verifySupplyVolume() {
        check(LitematicaPrinter.supplyVolume(new BlockPos(1, 2, 3), new BlockPos(1, 2, 3)) == 1,
            "a one-block supply region must be rejected");
        check(LitematicaPrinter.supplyVolume(new BlockPos(1, 2, 3), new BlockPos(1, 2, 4)) == 2,
            "1x1x2 must be accepted");
        check(LitematicaPrinter.supplyVolume(new BlockPos(1, 2, 3), new BlockPos(2, 2, 3)) == 2,
            "2x1x1 must be accepted");
        check(LitematicaPrinter.supplyVolume(new BlockPos(1, 2, 3), new BlockPos(1, 3, 3)) == 2,
            "1x2x1 must be accepted");
        check(!LitematicaPrinter.validSupplySelection(false, true, BlockPos.ORIGIN, new BlockPos(0, 0, 1)),
            "one selected corner must not render or activate restocking");
        check(!LitematicaPrinter.validSupplySelection(true, true, BlockPos.ORIGIN, BlockPos.ORIGIN),
            "a persisted 1x1x1 restock selection must remain invalid");
        check(LitematicaPrinter.validSupplySelection(true, true, BlockPos.ORIGIN, new BlockPos(0, 0, 1)),
            "two persisted corners spanning 1x1x2 must activate restocking");
    }

    private static void verifyLayeredRowOrder() {
        List<ProjectionScan.Target> targets = new ArrayList<>();
        Set<BlockPos> supports = new HashSet<>();
        for (int z = 0; z <= 1; z++) {
            for (int x = 0; x <= 2; x++) {
                targets.add(target(x, 1, z));
                supports.add(new BlockPos(x, 0, z));
            }
        }
        targets.add(target(0, 2, 0));

        List<BlockPos> ordered = PrinterBuildOrder.order(
            targets,
            new BlockPos(0, 1, 0),
            supports::contains,
            true,
            1,
            PrinterBuildOrder.LayerOrder.BottomToTop,
            PrinterBuildOrder.RowAxis.Automatic
        ).stream().map(ProjectionScan.Target::pos).toList();

        check(ordered.equals(List.of(
            new BlockPos(0, 1, 0), new BlockPos(1, 1, 0), new BlockPos(2, 1, 0),
            new BlockPos(2, 1, 1), new BlockPos(1, 1, 1), new BlockPos(0, 1, 1),
            new BlockPos(0, 2, 0)
        )), "automatic X rows must build bottom-up and enter the next row from the nearest endpoint");
    }

    private static void verifyUnsupportedTargetsAreDeferred() {
        ProjectionScan.Target supported = target(0, 1, 0);
        ProjectionScan.Target chained = target(1, 1, 0);
        ProjectionScan.Target floating = target(10, 10, 10);
        List<ProjectionScan.Target> ordered = PrinterBuildOrder.order(
            List.of(floating, chained, supported),
            BlockPos.ORIGIN,
            Set.of(new BlockPos(0, 0, 0))::contains,
            false,
            1,
            PrinterBuildOrder.LayerOrder.BottomToTop,
            PrinterBuildOrder.RowAxis.X
        );
        check(ordered.equals(List.of(supported, chained, floating)),
            "supported and newly adjacent targets must precede floating targets with stable ordering");
    }

    private static void verifyPlannerActionPriority() {
        ProjectionScan.Target mine = target(20, 1, 0);
        ProjectionScan.Target near = target(0, 1, 0);
        ProjectionScan.Target far = target(10, 1, 0);
        PlannerEnvironment environment = new PlannerEnvironment()
            .support(new BlockPos(20, 0, 0))
            .support(new BlockPos(0, 0, 0))
            .support(new BlockPos(10, 0, 0))
            .unreachable(far.pos())
            .status(mine.pos(), PrinterBatchPlanner.TargetStatus.MINE);

        PrinterBatchPlan<BlockPos> plan = planner().plan(input(List.of(far, near, mine), Set.of(), 4), environment);
        check(plan.action() == PrinterBatchPlan.Action.MINE && plan.target().equals(mine),
            "a blocking wrong block must be mined before placement");

        plan = planner().plan(input(List.of(far, near), Set.of(), 4), environment);
        check(plan.action() == PrinterBatchPlan.Action.PLACE_BATCH,
            "a reachable placement batch must beat a distant path goal");
        check(plan.batch().size() == 1 && plan.batch().getFirst().target().equals(near),
            "only the reachable nearby target may enter the batch");
    }

    private static void verifyDynamicActionableLayer() {
        ProjectionScan.Target floating = target(0, 0, 0);
        ProjectionScan.Target supportedUpper = target(10, 1, 0);
        PlannerEnvironment environment = new PlannerEnvironment().support(new BlockPos(11, 1, 0));
        PrinterBatchPlan<BlockPos> plan = planner().plan(
            input(List.of(floating, supportedUpper), Set.of(), 4), environment
        );

        check(plan.action() == PrinterBatchPlan.Action.PLACE_BATCH && plan.layer() == 1,
            "a floating lower layer must not permanently lock an actionable upper layer");
        check(plan.batch().getFirst().target().equals(supportedUpper),
            "the lowest actionable layer must be selected dynamically");
    }

    private static void verifyDependencyAndBatchSupportChain() {
        ProjectionScan.Target first = target(0, 1, 0);
        ProjectionScan.Target second = target(0, 2, 0);
        ProjectionScan.Target third = target(0, 3, 0);
        PlannerEnvironment vertical = new PlannerEnvironment().support(new BlockPos(0, 0, 0));
        PrinterBatchPlan<BlockPos> plan = planner().plan(
            input(List.of(third, second, first), Set.of(), 3), vertical
        );
        check(plan.batch().size() == 1 && plan.batch().getFirst().target().equals(first),
            "layered building must delay upper and upper-two targets until the lower layer is confirmed");

        ProjectionScan.Target anchor = target(4, 1, 0);
        ProjectionScan.Target chained = target(5, 1, 0);
        ProjectionScan.Target chainedAgain = target(6, 1, 0);
        PlannerEnvironment horizontal = new PlannerEnvironment().support(new BlockPos(4, 0, 0));
        plan = planner().plan(input(List.of(chainedAgain, chained, anchor), Set.of(), 3), horizontal);
        check(plan.batch().size() == 3, "a real anchor may seed an optimistic same-tick support chain");
        check(plan.batch().get(0).supportSource() == PrinterBatchPlan.SupportSource.REAL,
            "the first batch target must use real stable support");
        check(plan.batch().get(1).supportSource() == PrinterBatchPlan.SupportSource.BATCH
                && plan.batch().get(2).supportSource() == PrinterBatchPlan.SupportSource.BATCH,
            "later batch targets may only chain through earlier selected targets");
    }

    private static void verifyAwaitingAndReplacementAreNotSupports() {
        ProjectionScan.Target awaitingAnchor = target(0, 1, 0);
        ProjectionScan.Target dependent = target(1, 1, 0);
        PlannerEnvironment environment = new PlannerEnvironment()
            .support(awaitingAnchor.pos())
            .support(new BlockPos(0, 0, 0))
            .status(awaitingAnchor.pos(), PrinterBatchPlanner.TargetStatus.CORRECT);
        PrinterBatchPlan<BlockPos> plan = planner().plan(
            input(List.of(awaitingAnchor, dependent), Set.of(awaitingAnchor.pos()), 4), environment
        );
        check(plan.action() == PrinterBatchPlan.Action.NO_ACTION,
            "an awaiting target must not become support for a future target");

        ProjectionScan.Target replacement = target(4, 1, 0);
        ProjectionScan.Target neighbor = target(5, 1, 0);
        environment = new PlannerEnvironment()
            .support(replacement.pos())
            .status(replacement.pos(), PrinterBatchPlanner.TargetStatus.MINE);
        plan = planner().plan(input(List.of(neighbor, replacement), Set.of(), 4), environment);
        check(plan.action() == PrinterBatchPlan.Action.MINE && plan.target().equals(replacement),
            "a real block scheduled for replacement must not anchor placement before it is mined");
    }

    private static void verifyPathAndRestockPlans() {
        ProjectionScan.Target pathTarget = target(0, 1, 0);
        PlannerEnvironment pathEnvironment = new PlannerEnvironment()
            .support(new BlockPos(0, 0, 0))
            .unreachable(pathTarget.pos());
        PrinterBatchPlan<BlockPos> plan = planner().plan(input(List.of(pathTarget), Set.of(), 4), pathEnvironment);
        check(plan.action() == PrinterBatchPlan.Action.NEED_PATH && plan.target().equals(pathTarget),
            "an otherwise placeable target outside eye reach must request pathing");

        ProjectionScan.Target floating = target(20, 0, 0);
        ProjectionScan.Target redstone = target(0, 1, 0);
        ProjectionScan.Target glass = target(1, 1, 0);
        PlannerEnvironment restockEnvironment = new PlannerEnvironment()
            .support(new BlockPos(0, 0, 0))
            .material(redstone.pos(), "redstone")
            .material(glass.pos(), "glass")
            .available("redstone", 0)
            .available("glass", 0);
        plan = planner().plan(input(List.of(floating, glass, redstone), Set.of(), 4), restockEnvironment);
        check(plan.action() == PrinterBatchPlan.Action.NEED_RESTOCK,
            "material must be the only remaining barrier before restock is requested");
        check(plan.planningWindow().stream().map(ProjectionScan.Target::pos).toList().equals(List.of(
            redstone.pos(), glass.pos()
        )), "restock demand must come from the actionable dry-run window, not the pending prefix");
    }

    private static void verifyInventoryAndWholeStackRules() {
        check(PrinterInventory.isUsableBuildSlot(8, false), "hotbar-only mode must include slot 8");
        check(!PrinterInventory.isUsableBuildSlot(9, false), "hotbar-only mode must exclude inventory slot 9");
        check(PrinterInventory.isUsableBuildSlot(35, true), "inventory pull must include slot 35");
        check(PrinterInventory.chooseHotbarDestination(
            List.of("sea-lantern", "stone", "diamond", "honey"),
            Set.of("sea-lantern", "stone", "redstone", "sponge")
        ) == 2, "the leftmost locally unnecessary hotbar stack must be replaced first");
        check(PrinterInventory.chooseHotbarDestination(
            List.of("a", "b", "c"), Set.of("a", "b", "c")
        ) == -1, "all locally required hotbar stacks must be preserved");
        List<String> withEmpty = new ArrayList<>(List.of("a", "b", "c"));
        withEmpty.set(1, null);
        check(PrinterInventory.chooseHotbarDestination(withEmpty, Set.of("a", "b", "c")) == 1,
            "an empty hotbar slot must beat replacement");
    }

    private static void verifyFullInventoryRestockPlan() {
        PrinterRestockPlanner.Plan<String> plan = PrinterRestockPlanner.plan(List.of(
            new PrinterRestockPlanner.Demand<>("glass", 3 * 64 + 12, 0, 0, 64, 12, 0, 0),
            new PrinterRestockPlanner.Demand<>("redstone", 16 * 64 + 11, 0, 0, 64, 30, 1, 0),
            new PrinterRestockPlanner.Demand<>("honey", 30 * 64, 0, 0, 64, 50, 2, 0)
        ), 36, 0);
        check(plan.targetCounts().equals(Map.of(
            "honey", 30 * 64,
            "redstone", 5 * 64,
            "glass", 64
        )), "restocking must reserve one full stack per required material, then fill remaining slots by layer volume");

        List<PrinterRestockPlanner.Demand<String>> manyTypes = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            manyTypes.add(new PrinterRestockPlanner.Demand<>("item-" + i, 64, 0, 0, 64, i, i, 0));
        }
        plan = PrinterRestockPlanner.plan(manyTypes, 30, 0);
        check(plan.targetCounts().size() == 30 && !plan.targetCounts().containsKey("item-30"),
            "when material kinds exceed capacity, the nearest unbuilt kinds must be selected first");

        plan = PrinterRestockPlanner.plan(List.of(
            new PrinterRestockPlanner.Demand<>("served-near", 640, 0, 0, 64, 1, 0, 1),
            new PrinterRestockPlanner.Demand<>("new-far", 64, 0, 0, 64, 70, 1, 0)
        ), 1, 0);
        check(plan.targetCounts().containsKey("new-far"),
            "a material served in the previous restock must yield to an unserved kind on the next constrained trip");

        plan = PrinterRestockPlanner.plan(List.of(
            new PrinterRestockPlanner.Demand<>("small", 64, 0, 0, 64, 12, 0, 0),
            new PrinterRestockPlanner.Demand<>("large", 640, 0, 0, 64, 12, 1, 0)
        ), 1, 0);
        check(plan.targetCounts().containsKey("large"),
            "equal-distance materials must prefer the kind used more often in the current layer");

        plan = PrinterRestockPlanner.plan(List.of(
            new PrinterRestockPlanner.Demand<>("needed", 64, 0, 0, 64, 1, 0, 0)
        ), 0, 36);
        check(plan.requiresCleanup(),
            "a full inventory occupied by materials outside the current layer must request manual cleanup");
    }

    private static void verifyManualBuilderUsesAvailableMaterialsFirst() {
        ProjectionScan.Target missingNear = target(0, 1, 0);
        ProjectionScan.Target availableFar = target(3, 1, 0);
        PlannerEnvironment environment = new PlannerEnvironment()
            .support(new BlockPos(0, 0, 0))
            .support(new BlockPos(3, 0, 0))
            .material(missingNear.pos(), "missing")
            .material(availableFar.pos(), "available")
            .available("missing", 0)
            .available("available", 64);

        PrinterBatchPlan<BlockPos> plan = planner().planManualBuilder(
            input(List.of(missingNear, availableFar), Set.of(), 2), environment
        );
        check(plan.action() == PrinterBatchPlan.Action.PLACE_BATCH,
            "a nearby missing material must not trigger restock while another reachable target is placeable");
        check(plan.batch().getFirst().target().equals(availableFar),
            "the manual builder must consume every currently available material before restocking");
    }

    private static void verifyLayerAndRetryRules() {
        LitematicaProjection.LayerFilter all = LitematicaProjection.LayerFilter.all();
        check(!all.enabled() && all.includes(new BlockPos(1, 2, 3)),
            "disabled Litematica layer mode must allow nearby targets at every height");
        LitematicaProjection.LayerFilter single = new LitematicaProjection.LayerFilter(
            true, "SINGLE|Y|7|7", pos -> pos.getY() == 7
        );
        check(single.includes(new BlockPos(0, 7, 0)) && !single.includes(new BlockPos(0, 8, 0)),
            "enabled Litematica layer mode must filter targets through the active range");
        check(LitematicaPrinter.DEFAULT_BLOCKS_PER_TICK == 3, "Blocks Per Tick must default to 3");
        check(LitematicaPrinter.millisToTicks(250) == 5 && LitematicaPrinter.millisToTicks(1) == 1,
            "millisecond delays must round up to whole game ticks");
        check(!LitematicaPrinter.retryAfterReentry(false, true, 20, 10),
            "a parked target must not retry until it has left range");
        check(LitematicaPrinter.retryAfterReentry(true, true, 20, 10),
            "a parked target must retry after leaving and re-entering range");
    }

    private static void verifyCachedRestockKnowledge() {
        Map<String, Integer> required = Map.of("minecraft:stone", 64);
        check(PrinterSessionCache.evaluateSupplyKnowledge(
            Map.of("minecraft:stone", 128), required, false
        ) == PrinterSessionCache.SupplyKnowledge.SUFFICIENT,
            "cached contents that cover demand must be sufficient even if other containers are unknown");
        check(PrinterSessionCache.evaluateSupplyKnowledge(
            Map.of("minecraft:stone", 32), required, false
        ) == PrinterSessionCache.SupplyKnowledge.UNKNOWN,
            "an incomplete container cache must wait instead of claiming a shortage");
        check(PrinterSessionCache.evaluateSupplyKnowledge(
            Map.of("minecraft:stone", 32), required, true
        ) == PrinterSessionCache.SupplyKnowledge.INSUFFICIENT,
            "a complete container cache may safely report missing materials");
    }

    private static void verifyPlacementOnlyPlanner() {
        ProjectionScan.Target blocked = target(20, 1, 0);
        ProjectionScan.Target reachable = target(0, 1, 0);
        PlannerEnvironment environment = new PlannerEnvironment()
            .status(blocked.pos(), PrinterBatchPlanner.TargetStatus.MINE)
            .support(new BlockPos(0, 0, 0));
        PrinterBatchPlan<BlockPos> plan = planner().planPlacementOnly(
            input(List.of(blocked, reachable), Set.of(), 4), environment
        );
        check(plan.action() == PrinterBatchPlan.Action.PLACE_BATCH
                && plan.batch().getFirst().target().equals(reachable),
            "placement-only planning must ignore mining targets and place reachable blocks");

        ProjectionScan.Target unreachable = target(2, 1, 0);
        environment = new PlannerEnvironment()
            .support(new BlockPos(2, 0, 0))
            .unreachable(unreachable.pos());
        plan = planner().planPlacementOnly(input(List.of(unreachable), Set.of(), 4), environment);
        check(plan.action() == PrinterBatchPlan.Action.NO_ACTION,
            "placement-only planning must wait for the player instead of requesting pathing");

        ProjectionScan.Target missing = target(3, 1, 0);
        environment = new PlannerEnvironment()
            .support(new BlockPos(3, 0, 0))
            .available("stone", 0);
        plan = planner().planPlacementOnly(input(List.of(missing), Set.of(), 4), environment);
        check(plan.action() == PrinterBatchPlan.Action.NO_ACTION,
            "placement-only planning must wait for inventory instead of requesting restock");
        plan = planner().planManualBuilder(input(List.of(missing), Set.of(), 4), environment);
        check(plan.action() == PrinterBatchPlan.Action.NEED_RESTOCK,
            "manual builder planning must keep restock without requesting navigation");
    }

    private static void verifyPlanReplacement() {
        ProjectionScan.Target first = target(0, 1, 0);
        ProjectionScan.Target second = target(1, 1, 0);
        PrinterBatchPlan<BlockPos> original = PrinterBatchPlan.place(0, List.of(
            new PrinterBatchPlan.PlannedPlacement<>(first, first.pos(), new BlockPos(0, 0, 0), PrinterBatchPlan.SupportSource.REAL)
        ));
        PrinterBatchPlan<BlockPos> changed = PrinterBatchPlan.place(0, List.of(
            new PrinterBatchPlan.PlannedPlacement<>(second, second.pos(), first.pos(), PrinterBatchPlan.SupportSource.BATCH)
        ));
        check(!LitematicaPrinter.shouldReplacePlan(original, original),
            "an unchanged highlighted batch may be executed on the next tick");
        check(LitematicaPrinter.shouldReplacePlan(original, changed),
            "a changed world decision must replace the old batch before execution");
    }

    private static void verifyDebugLog() {
        try {
            Path root = Files.createTempDirectory("wavexin-printer-log-test-");
            Path directory = root.resolve("meteor-client").resolve("wavexin").resolve("printer");
            PrinterDebugLog disabled = new PrinterDebugLog();
            disabled.open(false, root);
            check(!Files.exists(directory), "Debug Log=false must not create the printer log directory");

            PrinterDebugLog first = new PrinterDebugLog();
            first.open(true, root);
            Path firstFile = first.path();
            first.open(true, root);
            check(first.path().equals(firstFile),
                "reopening the module logger in one Minecraft session must reuse the same file");
            first.info("planner", "action", "PLACE_BATCH");
            first.info("planner", "action", "PLACE_BATCH");
            first.flush();
            first.close();
            check(firstFile.getFileName().toString().equals(LocalDate.now() + "-1.log"),
                "the first daily printer log must use sequence 1");
            check(Files.readString(firstFile).contains("[WaveXinPrinter]: planner action=PLACE_BATCH"),
                "printer logs must contain detailed event fields");
            check(Files.readString(firstFile).contains("repeated_event event=planner suppressed=1"),
                "explicit flush must emit a summary for deduplicated INFO events");

            PrinterDebugLog second = new PrinterDebugLog();
            second.open(true, root);
            Path secondFile = second.path();
            second.close();
            check(secondFile.getFileName().toString().equals(LocalDate.now() + "-2.log"),
                "printer log sequence must increment without gzip rotation");
            check(PrinterDebugLog.formatLine(LocalTime.of(7, 8, 9), "INFO", "path_goal", "target", "1,2,3")
                    .equals("[07:08:09] [Client thread/INFO] [WaveXinPrinter]: path_goal target=1,2,3"),
                "printer log lines must follow the requested Minecraft-like format");

            Files.deleteIfExists(secondFile);
            Files.deleteIfExists(firstFile);
            Files.deleteIfExists(directory);
            Files.deleteIfExists(directory.getParent());
            Files.deleteIfExists(directory.getParent().getParent());
            Files.deleteIfExists(root);
        } catch (Exception e) {
            throw new AssertionError("printer debug log behavior", e);
        }
    }

    private static PrinterBatchPlanner<BlockPos> planner() {
        return new PrinterBatchPlanner<>();
    }

    private static PrinterBatchPlanner.Input input(
        List<ProjectionScan.Target> targets,
        Set<BlockPos> awaiting,
        int batchSize
    ) {
        return new PrinterBatchPlanner.Input(
            targets,
            awaiting,
            BlockPos.ORIGIN,
            0,
            true,
            1,
            PrinterBuildOrder.LayerOrder.BottomToTop,
            PrinterBuildOrder.RowAxis.X,
            batchSize,
            32
        );
    }

    private static final class PlannerEnvironment implements PrinterBatchPlanner.Environment<BlockPos> {
        private final Set<BlockPos> supports = new HashSet<>();
        private final Set<BlockPos> unreachable = new HashSet<>();
        private final Map<BlockPos, PrinterBatchPlanner.TargetStatus> statuses = new HashMap<>();
        private final Map<BlockPos, String> materials = new HashMap<>();
        private final Map<String, Integer> available = new LinkedHashMap<>();

        private PlannerEnvironment support(BlockPos pos) {
            supports.add(pos);
            return this;
        }

        private PlannerEnvironment unreachable(BlockPos pos) {
            unreachable.add(pos);
            return this;
        }

        private PlannerEnvironment status(BlockPos pos, PrinterBatchPlanner.TargetStatus status) {
            statuses.put(pos, status);
            return this;
        }

        private PlannerEnvironment material(BlockPos pos, String material) {
            materials.put(pos, material);
            return this;
        }

        private PlannerEnvironment available(String material, int count) {
            available.put(material, count);
            return this;
        }

        @Override
        public PrinterBatchPlanner.TargetStatus status(ProjectionScan.Target target) {
            return statuses.getOrDefault(target.pos(), PrinterBatchPlanner.TargetStatus.PLACE);
        }

        @Override
        public boolean isSolidSupport(BlockPos pos) {
            return supports.contains(pos);
        }

        @Override
        public Object materialKey(ProjectionScan.Target target) {
            return materials.getOrDefault(target.pos(), "stone");
        }

        @Override
        public int availableMaterial(Object material) {
            return available.getOrDefault(String.valueOf(material), 64);
        }

        @Override
        public PrinterBatchPlanner.PlacementOption<BlockPos> findPlacement(
            ProjectionScan.Target target,
            Set<BlockPos> plannedSupports,
            java.util.function.Predicate<BlockPos> stableSupport,
            boolean requireReach,
            boolean requireMaterial
        ) {
            if (status(target) != PrinterBatchPlanner.TargetStatus.PLACE) return null;
            if (requireReach && unreachable.contains(target.pos())) return null;
            if (requireMaterial && availableMaterial(materialKey(target)) <= 0) return null;
            if (!target.supportRequired()) {
                return new PrinterBatchPlanner.PlacementOption<>(target.pos(), null, false);
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = target.pos().offset(direction);
                if (plannedSupports.contains(neighbor)) {
                    return new PrinterBatchPlanner.PlacementOption<>(target.pos(), neighbor, true);
                }
                if (stableSupport.test(neighbor)) {
                    return new PrinterBatchPlanner.PlacementOption<>(target.pos(), neighbor, false);
                }
            }
            return null;
        }
    }

    private static ProjectionScan.Target target(int x, int y, int z) {
        return new ProjectionScan.Target(new BlockPos(x, y, z), null, true);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
