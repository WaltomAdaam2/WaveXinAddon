package me.waltom.wavexin.modules.litematicaprinter;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BaritoneNavigator implements PrinterNavigator {
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private final Map<Settings.Setting<?>, Object> previousSettings = new LinkedHashMap<>();

    public BaritoneNavigator() {
    }

    @Override
    public void configure() {
        Settings settings = BaritoneAPI.getSettings();
        set(settings.allowBreak, false);
        set(settings.allowPlace, false);
        set(settings.allowInventory, false);
        set(settings.autoTool, false);
        set(settings.allowParkour, false);
        set(settings.allowParkourPlace, false);
        set(settings.allowWaterBucketFall, false);
        set(settings.rightClickContainerOnArrival, false);
        set(settings.cutoffAtLoadBoundary, true);
    }

    @Override
    public void goTo(NavigationPlan plan) {
        Goal goal = switch (plan.kind()) {
            case NEAR -> new GoalNear(plan.target(), plan.range());
            case BUILD -> plan.support() == null
                ? new GoalBlock(plan.target().up())
                : new BuilderAdjacentGoal(plan.target(), plan.support(), plan.allowSameLevel());
            case MINE -> new BreakGoal(plan.target());
        };
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
    }

    @Override
    public boolean isNavigating() {
        return baritone.getCustomGoalProcess().isActive()
            || baritone.getPathingBehavior().isPathing()
            || baritone.getPathingBehavior().getInProgress().isPresent();
    }

    @Override
    public void cancel() {
        baritone.getPathingBehavior().cancelEverything();
    }

    @Override
    public void restore() {
        cancel();
        previousSettings.forEach(BaritoneNavigator::restoreSetting);
        previousSettings.clear();
    }

    private <T> void set(Settings.Setting<T> setting, T value) {
        previousSettings.putIfAbsent(setting, setting.value);
        setting.value = value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restoreSetting(Settings.Setting setting, Object value) {
        setting.value = value;
    }

    private static final class BuilderAdjacentGoal implements Goal {
        private final GoalGetToBlock adjacent;
        private final BlockPos target;
        private final BlockPos support;
        private final boolean allowSameLevel;

        private BuilderAdjacentGoal(BlockPos target, BlockPos support, boolean allowSameLevel) {
            this.target = target.toImmutable();
            this.support = support.toImmutable();
            this.allowSameLevel = allowSameLevel;
            adjacent = new GoalGetToBlock(target);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (matches(target, x, y, z) || matches(support, x, y, z)) return false;
            if (y < target.getY() - 1 || !allowSameLevel && y == target.getY() - 1) return false;
            return adjacent.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return adjacent.heuristic(x, y, z);
        }
    }

    private static final class BreakGoal implements Goal {
        private final GoalGetToBlock adjacent;
        private final BlockPos target;

        private BreakGoal(BlockPos target) {
            this.target = target.toImmutable();
            adjacent = new GoalGetToBlock(target);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return y <= target.getY() && adjacent.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return adjacent.heuristic(x, y, z);
        }
    }

    private static boolean matches(BlockPos pos, int x, int y, int z) {
        return pos.getX() == x && pos.getY() == y && pos.getZ() == z;
    }
}
