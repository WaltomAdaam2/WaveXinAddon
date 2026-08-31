package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.util.math.BlockPos;

import java.util.List;

record PrinterBatchPlan<C>(
    Action action,
    int layer,
    List<PlannedPlacement<C>> batch,
    ProjectionScan.Target target,
    BlockPos navigationSupport,
    boolean loadOnly,
    List<ProjectionScan.Target> planningWindow
) {
    PrinterBatchPlan {
        batch = List.copyOf(batch);
        navigationSupport = navigationSupport == null ? null : navigationSupport.toImmutable();
        planningWindow = List.copyOf(planningWindow);
    }

    static <C> PrinterBatchPlan<C> place(int layer, List<PlannedPlacement<C>> batch) {
        return new PrinterBatchPlan<>(Action.PLACE_BATCH, layer, batch, null, null, false, List.of());
    }

    static <C> PrinterBatchPlan<C> place(
        int layer,
        List<PlannedPlacement<C>> batch,
        List<ProjectionScan.Target> planningWindow
    ) {
        return new PrinterBatchPlan<>(Action.PLACE_BATCH, layer, batch, null, null, false, planningWindow);
    }

    static <C> PrinterBatchPlan<C> mine(int layer, ProjectionScan.Target target) {
        return new PrinterBatchPlan<>(Action.MINE, layer, List.of(), target, null, false, List.of());
    }

    static <C> PrinterBatchPlan<C> path(
        int layer,
        ProjectionScan.Target target,
        BlockPos support,
        boolean loadOnly
    ) {
        return new PrinterBatchPlan<>(Action.NEED_PATH, layer, List.of(), target, support, loadOnly, List.of());
    }

    static <C> PrinterBatchPlan<C> restock(
        int layer,
        ProjectionScan.Target target,
        List<ProjectionScan.Target> planningWindow
    ) {
        return new PrinterBatchPlan<>(Action.NEED_RESTOCK, layer, List.of(), target, null, false, planningWindow);
    }

    static <C> PrinterBatchPlan<C> noAction() {
        return new PrinterBatchPlan<>(Action.NO_ACTION, -1, List.of(), null, null, false, List.of());
    }

    boolean sameDecision(PrinterBatchPlan<?> other) {
        if (other == null || action != other.action || layer != other.layer || loadOnly != other.loadOnly) return false;
        if (!sameTarget(target, other.target) || !java.util.Objects.equals(navigationSupport, other.navigationSupport)) return false;
        if (batch.size() != other.batch.size() || planningWindow.size() != other.planningWindow.size()) return false;
        for (int i = 0; i < batch.size(); i++) {
            PlannedPlacement<C> first = batch.get(i);
            PlannedPlacement<?> second = other.batch.get(i);
            if (!sameTarget(first.target(), second.target())
                || !java.util.Objects.equals(first.support(), second.support())
                || first.supportSource() != second.supportSource()) return false;
        }
        for (int i = 0; i < planningWindow.size(); i++) {
            if (!sameTarget(planningWindow.get(i), other.planningWindow.get(i))) return false;
        }
        return true;
    }

    private static boolean sameTarget(ProjectionScan.Target first, ProjectionScan.Target second) {
        return first == second || first != null && second != null && first.pos().equals(second.pos());
    }

    enum Action {
        PLACE_BATCH,
        MINE,
        NEED_PATH,
        NEED_RESTOCK,
        NO_ACTION
    }

    enum SupportSource {
        REAL,
        BATCH,
        NONE
    }

    record PlannedPlacement<C>(
        ProjectionScan.Target target,
        C candidate,
        BlockPos support,
        SupportSource supportSource
    ) {
        PlannedPlacement {
            support = support == null ? null : support.toImmutable();
        }
    }
}
