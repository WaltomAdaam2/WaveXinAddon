package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

final class PrinterBatchPlanner<C> {
    private final Map<BlockPos, ProjectionScan.Target> indexedTargets = new LinkedHashMap<>();
    private final TreeMap<Integer, LinkedHashMap<BlockPos, ProjectionScan.Target>> targetsByY = new TreeMap<>();

    void reset(Collection<ProjectionScan.Target> targets) {
        clear();
        for (ProjectionScan.Target target : targets) {
            indexedTargets.put(target.pos(), target);
            targetsByY.computeIfAbsent(target.pos().getY(), ignored -> new LinkedHashMap<>())
                .put(target.pos(), target);
        }
    }

    void remove(BlockPos pos) {
        ProjectionScan.Target removed = indexedTargets.remove(pos);
        if (removed == null) return;
        Map<BlockPos, ProjectionScan.Target> yTargets = targetsByY.get(removed.pos().getY());
        yTargets.remove(removed.pos());
        if (yTargets.isEmpty()) targetsByY.remove(removed.pos().getY());
    }

    void clear() {
        indexedTargets.clear();
        targetsByY.clear();
    }

    PrinterBatchPlan<C> plan(Input input, Environment<C> environment) {
        if (input.targets().isEmpty()) return PrinterBatchPlan.noAction();
        if (indexedTargets.isEmpty()) reset(input.targets());

        Predicate<BlockPos> stableSupport = pos -> {
            ProjectionScan.Target indexed = indexedTargets.get(pos);
            return !input.awaiting().contains(pos)
                && (indexed == null || environment.status(indexed) == TargetStatus.CORRECT)
                && environment.isSolidSupport(pos);
        };

        for (int layer : layerKeys(input)) {
            List<ProjectionScan.Target> layerTargets = targetsForLayer(layer, input);
            List<ProjectionScan.Target> active = new ArrayList<>(layerTargets.size());
            for (ProjectionScan.Target target : layerTargets) {
                if (input.awaiting().contains(target.pos())) continue;
                if (environment.status(target) == TargetStatus.CORRECT) {
                    remove(target.pos());
                    environment.confirmed(target);
                } else {
                    active.add(target);
                }
            }
            if (active.isEmpty()) continue;

            ProjectionScan.Target mine = active.stream()
                .filter(target -> environment.status(target) == TargetStatus.MINE)
                .min(targetComparator(input.cursor(), input.rowAxis(), active))
                .orElse(null);
            if (mine != null) return PrinterBatchPlan.mine(layer, mine);

            List<PrinterBatchPlan.PlannedPlacement<C>> reachable = selectPlacements(
                active, indexedTargets, input, environment, stableSupport, Set.of(), true, false, input.batchSize()
            );
            if (!reachable.isEmpty()) return PrinterBatchPlan.place(layer, reachable);

            List<PrinterBatchPlan.PlannedPlacement<C>> pathable = selectPlacements(
                active, indexedTargets, input, environment, stableSupport, Set.of(), false, false, 1
            );
            if (!pathable.isEmpty()) {
                PrinterBatchPlan.PlannedPlacement<C> placement = pathable.getFirst();
                return PrinterBatchPlan.path(layer, placement.target(), placement.support(), false);
            }

            ProjectionScan.Target unloaded = active.stream()
                .filter(target -> environment.status(target) == TargetStatus.UNLOADED)
                .min(targetComparator(input.cursor(), input.rowAxis(), active))
                .orElse(null);
            if (unloaded != null) return PrinterBatchPlan.path(layer, unloaded, null, true);

            List<PrinterBatchPlan.PlannedPlacement<C>> missing = selectPlacements(
                active, indexedTargets, input, environment, stableSupport, Set.of(), false, true, 1
            );
            if (!missing.isEmpty()) {
                List<ProjectionScan.Target> window = planningWindow(input, environment, stableSupport);
                return PrinterBatchPlan.restock(layer, missing.getFirst().target(), window);
            }
        }
        return PrinterBatchPlan.noAction();
    }

    PrinterBatchPlan<C> planPlacementOnly(Input input, Environment<C> environment) {
        if (input.targets().isEmpty()) return PrinterBatchPlan.noAction();
        reset(input.targets());

        Predicate<BlockPos> stableSupport = pos -> {
            ProjectionScan.Target indexed = indexedTargets.get(pos);
            return !input.awaiting().contains(pos)
                && (indexed == null || environment.status(indexed) == TargetStatus.CORRECT)
                && environment.isSolidSupport(pos);
        };

        for (int layer : layerKeys(input)) {
            List<ProjectionScan.Target> layerTargets = targetsForLayer(layer, input);
            List<ProjectionScan.Target> active = new ArrayList<>(layerTargets.size());
            for (ProjectionScan.Target target : layerTargets) {
                if (input.awaiting().contains(target.pos())) continue;
                if (environment.status(target) == TargetStatus.CORRECT) {
                    remove(target.pos());
                    environment.confirmed(target);
                } else {
                    active.add(target);
                }
            }
            if (active.isEmpty()) continue;

            List<PrinterBatchPlan.PlannedPlacement<C>> reachable = selectPlacements(
                active, indexedTargets, input, environment, stableSupport, Set.of(), true, false, input.batchSize()
            );
            if (!reachable.isEmpty()) return PrinterBatchPlan.place(layer, reachable);
        }
        return PrinterBatchPlan.noAction();
    }

    PrinterBatchPlan<C> planManualBuilder(Input input, Environment<C> environment) {
        if (input.targets().isEmpty()) return PrinterBatchPlan.noAction();
        reset(input.targets());

        Predicate<BlockPos> stableSupport = pos -> {
            ProjectionScan.Target indexed = indexedTargets.get(pos);
            return !input.awaiting().contains(pos)
                && (indexed == null || environment.status(indexed) == TargetStatus.CORRECT)
                && environment.isSolidSupport(pos);
        };

        for (int layer : layerKeys(input)) {
            List<ProjectionScan.Target> layerTargets = targetsForLayer(layer, input);
            List<ProjectionScan.Target> active = new ArrayList<>(layerTargets.size());
            for (ProjectionScan.Target target : layerTargets) {
                if (input.awaiting().contains(target.pos())) continue;
                if (environment.status(target) == TargetStatus.CORRECT) {
                    remove(target.pos());
                    environment.confirmed(target);
                } else {
                    active.add(target);
                }
            }
            if (active.isEmpty()) continue;

            List<PrinterBatchPlan.PlannedPlacement<C>> workSet = selectPlacements(
                active, indexedTargets, input, environment, stableSupport, Set.of(), true, true, input.planningWindow()
            );
            List<PrinterBatchPlan.PlannedPlacement<C>> reachable = selectPlacements(
                active, indexedTargets, input, environment, stableSupport, Set.of(), true, false, input.batchSize()
            );
            if (!reachable.isEmpty()) {
                return PrinterBatchPlan.place(
                    layer,
                    reachable,
                    workSet.stream().map(PrinterBatchPlan.PlannedPlacement::target).toList()
                );
            }

            if (!workSet.isEmpty()) {
                return PrinterBatchPlan.restock(
                    layer,
                    workSet.getFirst().target(),
                    workSet.stream().map(PrinterBatchPlan.PlannedPlacement::target).toList()
                );
            }
        }
        return PrinterBatchPlan.noAction();
    }

    private List<ProjectionScan.Target> planningWindow(
        Input input,
        Environment<C> environment,
        Predicate<BlockPos> stableSupport
    ) {
        List<ProjectionScan.Target> result = new ArrayList<>(input.planningWindow());
        Set<BlockPos> virtualSupports = new HashSet<>();
        for (int layer : layerKeys(input)) {
            if (result.size() >= input.planningWindow()) break;
            List<ProjectionScan.Target> active = targetsForLayer(layer, input).stream()
                .filter(target -> !input.awaiting().contains(target.pos()))
                .filter(target -> environment.status(target) == TargetStatus.PLACE)
                .toList();
            if (active.isEmpty()) continue;

            List<PrinterBatchPlan.PlannedPlacement<C>> selected = selectPlacements(
                active,
                indexedTargets,
                input,
                environment,
                stableSupport,
                virtualSupports,
                false,
                true,
                input.planningWindow() - result.size()
            );
            for (PrinterBatchPlan.PlannedPlacement<C> placement : selected) {
                result.add(placement.target());
                virtualSupports.add(placement.target().pos());
            }
        }
        return List.copyOf(result);
    }

    private List<PrinterBatchPlan.PlannedPlacement<C>> selectPlacements(
        List<ProjectionScan.Target> candidates,
        Map<BlockPos, ProjectionScan.Target> byPosition,
        Input input,
        Environment<C> environment,
        Predicate<BlockPos> stableSupport,
        Set<BlockPos> initialPlannedSupports,
        boolean requireReach,
        boolean ignoreMaterials,
        int limit
    ) {
        List<PrinterBatchPlan.PlannedPlacement<C>> selected = new ArrayList<>(Math.min(limit, candidates.size()));
        Set<BlockPos> selectedPositions = new HashSet<>(initialPlannedSupports);
        Set<BlockPos> remaining = new HashSet<>();
        for (ProjectionScan.Target target : candidates) {
            if (environment.status(target) == TargetStatus.PLACE) remaining.add(target.pos());
        }
        Map<Object, Integer> reservedMaterials = new HashMap<>();
        BlockPos cursor = input.cursor();

        while (selected.size() < limit) {
            CandidateChoice<C> best = null;
            for (ProjectionScan.Target target : candidates) {
                if (!remaining.contains(target.pos())) continue;
                if (!dependenciesSatisfied(target, byPosition, environment, selectedPositions)) continue;

                Object material = environment.materialKey(target);
                if (!ignoreMaterials
                    && reservedMaterials.getOrDefault(material, 0) >= environment.availableMaterial(material)) continue;

                PlacementOption<C> option = environment.findPlacement(
                    target, Set.copyOf(selectedPositions), stableSupport, requireReach, !ignoreMaterials
                );
                if (option == null) continue;

                int supportCount = supportCount(target.pos(), selectedPositions, stableSupport);
                CandidateChoice<C> choice = new CandidateChoice<>(target, option, supportCount);
                if (best == null || compare(choice, best, cursor, input.rowAxis(), candidates) < 0) best = choice;
            }
            if (best == null) break;

            PrinterBatchPlan.SupportSource source = best.option().plannedSupport()
                ? PrinterBatchPlan.SupportSource.BATCH
                : best.option().support() == null
                ? PrinterBatchPlan.SupportSource.NONE
                : PrinterBatchPlan.SupportSource.REAL;
            selected.add(new PrinterBatchPlan.PlannedPlacement<>(
                best.target(), best.option().candidate(), best.option().support(), source
            ));
            remaining.remove(best.target().pos());
            selectedPositions.add(best.target().pos());
            reservedMaterials.merge(environment.materialKey(best.target()), 1, Integer::sum);
            cursor = best.target().pos();
        }
        return List.copyOf(selected);
    }

    private static boolean dependenciesSatisfied(
        ProjectionScan.Target target,
        Map<BlockPos, ProjectionScan.Target> byPosition,
        Environment<?> environment,
        Set<BlockPos> selectedPositions
    ) {
        for (int distance = 1; distance <= 2; distance++) {
            ProjectionScan.Target below = byPosition.get(target.pos().down(distance));
            if (below == null || !below.supportRequired() || environment.status(below) == TargetStatus.CORRECT) continue;
            if (!selectedPositions.contains(below.pos())) return false;
        }
        return true;
    }

    private static int supportCount(BlockPos pos, Set<BlockPos> planned, Predicate<BlockPos> stableSupport) {
        int result = 0;
        for (Direction direction : Direction.values()) {
            BlockPos support = pos.offset(direction);
            if (planned.contains(support) || stableSupport.test(support)) result++;
        }
        return result;
    }

    private static <C> int compare(
        CandidateChoice<C> first,
        CandidateChoice<C> second,
        BlockPos cursor,
        PrinterBuildOrder.RowAxis rowAxis,
        List<ProjectionScan.Target> layer
    ) {
        int planned = Boolean.compare(second.option().plannedSupport(), first.option().plannedSupport());
        if (planned != 0) return planned;
        int support = Integer.compare(second.supportCount(), first.supportCount());
        if (support != 0) return support;
        int distance = Double.compare(first.target().pos().getSquaredDistance(cursor), second.target().pos().getSquaredDistance(cursor));
        if (distance != 0) return distance;
        return coordinateComparator(resolveAxis(rowAxis, layer)).compare(first.target(), second.target());
    }

    private static Comparator<ProjectionScan.Target> targetComparator(
        BlockPos cursor,
        PrinterBuildOrder.RowAxis rowAxis,
        List<ProjectionScan.Target> layer
    ) {
        Comparator<ProjectionScan.Target> coordinates = coordinateComparator(resolveAxis(rowAxis, layer));
        return Comparator.comparingDouble((ProjectionScan.Target target) -> target.pos().getSquaredDistance(cursor))
            .thenComparing(coordinates);
    }

    private static Comparator<ProjectionScan.Target> coordinateComparator(PrinterBuildOrder.RowAxis axis) {
        return axis == PrinterBuildOrder.RowAxis.Z
            ? Comparator.comparingInt((ProjectionScan.Target target) -> target.pos().getY())
                .thenComparingInt(target -> target.pos().getX())
                .thenComparingInt(target -> target.pos().getZ())
            : Comparator.comparingInt((ProjectionScan.Target target) -> target.pos().getY())
                .thenComparingInt(target -> target.pos().getZ())
                .thenComparingInt(target -> target.pos().getX());
    }

    private static PrinterBuildOrder.RowAxis resolveAxis(
        PrinterBuildOrder.RowAxis configured,
        Collection<ProjectionScan.Target> targets
    ) {
        if (configured != PrinterBuildOrder.RowAxis.Automatic) return configured;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ProjectionScan.Target target : targets) {
            minX = Math.min(minX, target.pos().getX());
            maxX = Math.max(maxX, target.pos().getX());
            minZ = Math.min(minZ, target.pos().getZ());
            maxZ = Math.max(maxZ, target.pos().getZ());
        }
        return maxX - minX >= maxZ - minZ ? PrinterBuildOrder.RowAxis.X : PrinterBuildOrder.RowAxis.Z;
    }

    private List<Integer> layerKeys(Input input) {
        if (!input.buildInLayers()) return indexedTargets.isEmpty() ? List.of() : List.of(0);
        int height = Math.max(1, input.layerHeight());
        TreeSet<Integer> layers = new TreeSet<>();
        for (int y : targetsByY.keySet()) layers.add(Math.floorDiv(y - input.baseY(), height));
        return input.layerOrder() == PrinterBuildOrder.LayerOrder.TopToBottom
            ? List.copyOf(layers.descendingSet())
            : List.copyOf(layers);
    }

    private List<ProjectionScan.Target> targetsForLayer(int layer, Input input) {
        if (!input.buildInLayers()) return List.copyOf(indexedTargets.values());
        int height = Math.max(1, input.layerHeight());
        int minY = input.baseY() + layer * height;
        int maxY = minY + height - 1;
        List<ProjectionScan.Target> result = new ArrayList<>();
        for (Map<BlockPos, ProjectionScan.Target> targets : targetsByY.subMap(minY, true, maxY, true).values()) {
            result.addAll(targets.values());
        }
        return result;
    }

    interface Environment<C> {
        TargetStatus status(ProjectionScan.Target target);

        boolean isSolidSupport(BlockPos pos);

        Object materialKey(ProjectionScan.Target target);

        int availableMaterial(Object material);

        PlacementOption<C> findPlacement(
            ProjectionScan.Target target,
            Set<BlockPos> plannedSupports,
            Predicate<BlockPos> stableSupport,
            boolean requireReach,
            boolean requireMaterial
        );

        default void confirmed(ProjectionScan.Target target) {
        }
    }

    enum TargetStatus {
        CORRECT,
        MINE,
        PLACE,
        UNLOADED
    }

    record PlacementOption<C>(C candidate, BlockPos support, boolean plannedSupport) {
        PlacementOption {
            support = support == null ? null : support.toImmutable();
        }
    }

    record Input(
        Collection<ProjectionScan.Target> targets,
        Set<BlockPos> awaiting,
        BlockPos cursor,
        int baseY,
        boolean buildInLayers,
        int layerHeight,
        PrinterBuildOrder.LayerOrder layerOrder,
        PrinterBuildOrder.RowAxis rowAxis,
        int batchSize,
        int planningWindow
    ) {
        Input {
            awaiting = Set.copyOf(awaiting);
            cursor = cursor == null ? BlockPos.ORIGIN : cursor.toImmutable();
            batchSize = Math.max(1, batchSize);
            planningWindow = Math.max(batchSize, planningWindow);
        }
    }

    private record CandidateChoice<C>(
        ProjectionScan.Target target,
        PlacementOption<C> option,
        int supportCount
    ) {
    }
}
