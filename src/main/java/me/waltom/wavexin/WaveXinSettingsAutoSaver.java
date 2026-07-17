package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

final class WaveXinSettingsAutoSaver {
    private boolean initialized;
    private String lastSettingsSignature;

    @EventHandler
    private void onTick(TickEvent.Post event) {
        var modules = Modules.get().getGroup(WaveXinAddon.CATEGORY);

        if (!initialized) {
            boolean restored = WaveXinSettingsStore.restore(modules);
            if (!restored) WaveXinSettingsStore.save(modules);
            else Modules.get().save();

            initialized = true;
            lastSettingsSignature = createSignature(modules);
            return;
        }

        String signature = createSignature(modules);
        if (!signature.equals(lastSettingsSignature)) {
            lastSettingsSignature = signature;
            WaveXinSettingsStore.save(modules);
            Modules.get().save();
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
