package me.waltom.wavexin.modules.litematicaprinter;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.util.math.BlockPos;

public final class BaritoneNavigator implements PrinterNavigator {
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

    public BaritoneNavigator() {
    }

    @Override
    public void goTo(BlockPos pos, int range) {
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, range));
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
}
