package me.waltom.wavexin.core;

import me.waltom.wavexin.WaveXinAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

public final class WaveXinSettingsAutoSaver {
    public static final WaveXinSettingsAutoSaver INSTANCE = new WaveXinSettingsAutoSaver();
    private static final int CHECK_INTERVAL_TICKS = 20;
    private boolean initialized;
    private String lastSettingsSignature;
    private int ticksUntilCheck;

    private WaveXinSettingsAutoSaver() {}

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!initialized) {
            var modules = Modules.get().getGroup(WaveXinAddon.CATEGORY);
            boolean restored = WaveXinSettingsStore.restore(modules);
            initialized = true;
            if (restored) {
                Modules.get().save();
                lastSettingsSignature = createSignature(modules);
            } else flush();
            return;
        }

        if (++ticksUntilCheck >= CHECK_INTERVAL_TICKS) flush();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onGameLeft(GameLeftEvent event) {
        flush();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onScreenChanged(OpenScreenEvent event) {
        flush();
    }

    /** Also called before an orderly client shutdown, including shutdown from the title screen. */
    public void flush() {
        if (!initialized) return;
        ticksUntilCheck = 0;
        var modules = Modules.get().getGroup(WaveXinAddon.CATEGORY);
        String signature = createSignature(modules);
        if (!signature.equals(lastSettingsSignature) && WaveXinSettingsStore.save(modules)) {
            Modules.get().save();
            lastSettingsSignature = signature;
        }
    }

    private String createSignature(Iterable<Module> modules) {
        StringBuilder settingsSignature = new StringBuilder();
        for (Module module : modules) {
            var tag = module.toTag();
            if (tag != null) settingsSignature.append(tag);
        }

        return settingsSignature.toString();
    }
}
