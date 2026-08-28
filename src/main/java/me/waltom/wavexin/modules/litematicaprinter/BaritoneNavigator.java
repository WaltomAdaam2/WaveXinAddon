package me.waltom.wavexin.modules.litematicaprinter;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
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
}
