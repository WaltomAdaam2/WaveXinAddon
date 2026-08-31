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
import java.util.function.Predicate;

final class PrinterBuildOrder {
    private PrinterBuildOrder() {
    }

    static List<ProjectionScan.Target> order(
        Collection<ProjectionScan.Target> targets,
        BlockPos start,
        Predicate<BlockPos> solidSupport,
        boolean buildInLayers,
        int layerHeight,
        LayerOrder layerOrder,
        RowAxis configuredAxis
    ) {
        if (targets.isEmpty()) return List.of();

        int minimumY = targets.stream().mapToInt(target -> target.pos().getY()).min().orElse(0);
        Map<Integer, List<ProjectionScan.Target>> layers = new HashMap<>();
        for (ProjectionScan.Target target : targets) {
            int layer = buildInLayers
                ? Math.floorDiv(target.pos().getY() - minimumY, Math.max(1, layerHeight))
                : 0;
            layers.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(target);
        }

        List<Integer> layerKeys = new ArrayList<>(layers.keySet());
        layerKeys.sort(layerOrder == LayerOrder.TopToBottom ? Comparator.reverseOrder() : Comparator.naturalOrder());

        List<ProjectionScan.Target> ordered = new ArrayList<>(targets.size());
        Set<BlockPos> planned = new HashSet<>();
        BlockPos cursor = start == null ? BlockPos.ORIGIN : start;
        for (int layer : layerKeys) {
            cursor = orderLayer(layers.get(layer), cursor, solidSupport, configuredAxis, planned, ordered);
        }
        return List.copyOf(ordered);
    }

    private static BlockPos orderLayer(
        List<ProjectionScan.Target> targets,
        BlockPos start,
        Predicate<BlockPos> solidSupport,
        RowAxis configuredAxis,
        Set<BlockPos> planned,
        List<ProjectionScan.Target> ordered
    ) {
        RowAxis axis = resolveAxis(targets, configuredAxis);
        Map<RowKey, List<ProjectionScan.Target>> rows = new LinkedHashMap<>();
        targets.stream().sorted(PrinterBuildOrder::compareCoordinates).forEach(target ->
            rows.computeIfAbsent(rowKey(target.pos(), axis), ignored -> new ArrayList<>()).add(target)
        );

        List<Map.Entry<RowKey, List<ProjectionScan.Target>>> rowList = new ArrayList<>(rows.entrySet());
        rowList.sort(Map.Entry.<RowKey, List<ProjectionScan.Target>>comparingByKey(
            Comparator.comparingInt(RowKey::y).thenComparingInt(RowKey::transverse)
        ));
        int firstRow = nearestRow(rowList, start, planned, solidSupport);
        int lower = firstRow - 1;
        int upper = firstRow + 1;
        BlockPos cursor = processRow(rowList.get(firstRow).getValue(), start, axis, solidSupport, planned, ordered);
        List<ProjectionScan.Target> deferred = new ArrayList<>();
        collectUnsupported(rowList.get(firstRow).getValue(), planned, deferred);

        while (lower >= 0 || upper < rowList.size()) {
            boolean useLower;
            if (lower < 0) useLower = false;
            else if (upper >= rowList.size()) useLower = true;
            else {
                int lowerSupport = rowEndpointSupport(rowList.get(lower).getValue(), planned, solidSupport);
                int upperSupport = rowEndpointSupport(rowList.get(upper).getValue(), planned, solidSupport);
                useLower = lowerSupport > upperSupport
                    || lowerSupport == upperSupport
                    && rowDistance(rowList.get(lower).getValue(), cursor)
                    <= rowDistance(rowList.get(upper).getValue(), cursor);
            }

            List<ProjectionScan.Target> row = rowList.get(useLower ? lower-- : upper++).getValue();
            BlockPos nextCursor = processRow(row, cursor, axis, solidSupport, planned, ordered);
            collectUnsupported(row, planned, deferred);
            if (nextCursor != null) cursor = nextCursor;
        }

        return resolveDeferred(deferred, cursor, solidSupport, planned, ordered);
    }

    private static BlockPos processRow(
        List<ProjectionScan.Target> row,
        BlockPos cursor,
        RowAxis axis,
        Predicate<BlockPos> solidSupport,
        Set<BlockPos> planned,
        List<ProjectionScan.Target> ordered
    ) {
        row.sort(Comparator.comparingInt(target -> alongCoordinate(target.pos(), axis)));
        ProjectionScan.Target first = row.getFirst();
        ProjectionScan.Target last = row.getLast();
        int firstSupport = supportCount(first.pos(), planned, solidSupport);
        int lastSupport = supportCount(last.pos(), planned, solidSupport);
        boolean reverse = lastSupport > firstSupport
            || lastSupport == firstSupport
            && last.pos().getSquaredDistance(cursor) < first.pos().getSquaredDistance(cursor);

        BlockPos result = cursor;
        for (int i = 0; i < row.size(); i++) {
            ProjectionScan.Target target = row.get(reverse ? row.size() - 1 - i : i);
            if (target.supportRequired() && supportCount(target.pos(), planned, solidSupport) == 0) continue;
            ordered.add(target);
            planned.add(target.pos());
            result = target.pos();
        }
        return result;
    }

    private static void collectUnsupported(
        List<ProjectionScan.Target> row,
        Set<BlockPos> planned,
        List<ProjectionScan.Target> deferred
    ) {
        for (ProjectionScan.Target target : row) {
            if (!planned.contains(target.pos())) deferred.add(target);
        }
    }

    private static BlockPos resolveDeferred(
        List<ProjectionScan.Target> deferred,
        BlockPos cursor,
        Predicate<BlockPos> solidSupport,
        Set<BlockPos> planned,
        List<ProjectionScan.Target> ordered
    ) {
        Map<BlockPos, ProjectionScan.Target> remaining = new HashMap<>();
        for (ProjectionScan.Target target : deferred) remaining.put(target.pos(), target);

        List<ProjectionScan.Target> ready = new ArrayList<>();
        for (ProjectionScan.Target target : deferred) {
            if (!target.supportRequired() || supportCount(target.pos(), planned, solidSupport) > 0) ready.add(target);
        }
        ready.sort(supportedComparator(cursor, planned, solidSupport));

        BlockPos result = cursor;
        for (int index = 0; index < ready.size(); index++) {
            ProjectionScan.Target target = ready.get(index);
            if (remaining.remove(target.pos()) == null) continue;
            ordered.add(target);
            planned.add(target.pos());
            result = target.pos();
            for (Direction direction : Direction.values()) {
                ProjectionScan.Target neighbor = remaining.get(target.pos().offset(direction));
                if (neighbor != null) ready.add(neighbor);
            }
        }

        BlockPos finalCursor = result;
        remaining.values().stream()
            .sorted(Comparator.comparingDouble((ProjectionScan.Target target) -> target.pos().getSquaredDistance(finalCursor))
                .thenComparing(PrinterBuildOrder::compareCoordinates))
            .forEach(ordered::add);
        return result;
    }

    private static Comparator<ProjectionScan.Target> supportedComparator(
        BlockPos cursor,
        Set<BlockPos> planned,
        Predicate<BlockPos> solidSupport
    ) {
        return Comparator.<ProjectionScan.Target>comparingInt(target -> -supportCount(target.pos(), planned, solidSupport))
            .thenComparingDouble(target -> target.pos().getSquaredDistance(cursor))
            .thenComparing(PrinterBuildOrder::compareCoordinates);
    }

    private static int nearestRow(
        List<Map.Entry<RowKey, List<ProjectionScan.Target>>> rows,
        BlockPos cursor,
        Set<BlockPos> planned,
        Predicate<BlockPos> solidSupport
    ) {
        int nearest = 0;
        int support = -1;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < rows.size(); i++) {
            int candidateSupport = rowEndpointSupport(rows.get(i).getValue(), planned, solidSupport);
            double candidate = rowDistance(rows.get(i).getValue(), cursor);
            if (candidateSupport > support || candidateSupport == support && candidate < distance) {
                support = candidateSupport;
                distance = candidate;
                nearest = i;
            }
        }
        return nearest;
    }

    private static int rowEndpointSupport(
        List<ProjectionScan.Target> row,
        Set<BlockPos> planned,
        Predicate<BlockPos> solidSupport
    ) {
        return Math.max(
            supportCount(row.getFirst().pos(), planned, solidSupport),
            supportCount(row.getLast().pos(), planned, solidSupport)
        );
    }

    private static double rowDistance(List<ProjectionScan.Target> row, BlockPos cursor) {
        return Math.min(
            row.getFirst().pos().getSquaredDistance(cursor),
            row.getLast().pos().getSquaredDistance(cursor)
        );
    }

    private static int supportCount(BlockPos pos, Set<BlockPos> planned, Predicate<BlockPos> solidSupport) {
        int count = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.offset(direction);
            if (planned.contains(neighbor) || solidSupport.test(neighbor)) count++;
        }
        return count;
    }

    private static RowAxis resolveAxis(List<ProjectionScan.Target> targets, RowAxis configured) {
        if (configured != RowAxis.Automatic || targets.isEmpty()) return configured == RowAxis.Automatic ? RowAxis.X : configured;
        int minX = targets.stream().mapToInt(target -> target.pos().getX()).min().orElse(0);
        int maxX = targets.stream().mapToInt(target -> target.pos().getX()).max().orElse(0);
        int minZ = targets.stream().mapToInt(target -> target.pos().getZ()).min().orElse(0);
        int maxZ = targets.stream().mapToInt(target -> target.pos().getZ()).max().orElse(0);
        return maxX - minX >= maxZ - minZ ? RowAxis.X : RowAxis.Z;
    }

    private static RowKey rowKey(BlockPos pos, RowAxis axis) {
        return new RowKey(pos.getY(), axis == RowAxis.Z ? pos.getX() : pos.getZ());
    }

    private static int alongCoordinate(BlockPos pos, RowAxis axis) {
        return axis == RowAxis.Z ? pos.getZ() : pos.getX();
    }

    private static int compareCoordinates(ProjectionScan.Target first, ProjectionScan.Target second) {
        int y = Integer.compare(first.pos().getY(), second.pos().getY());
        if (y != 0) return y;
        int z = Integer.compare(first.pos().getZ(), second.pos().getZ());
        return z != 0 ? z : Integer.compare(first.pos().getX(), second.pos().getX());
    }

    enum LayerOrder {
        BottomToTop,
        TopToBottom
    }

    enum RowAxis {
        Automatic,
        X,
        Z
    }

    private record RowKey(int y, int transverse) {
    }
}
