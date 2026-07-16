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
        StringBuilder settingsSignature = new StringBuilder();
        for (Module module : Modules.get().getGroup(WaveXinAddon.CATEGORY)) {
            var tag = module.toTag();
            if (tag != null) settingsSignature.append(tag);
        }

        String signature = settingsSignature.toString();

        if (!initialized) {
            initialized = true;
            lastSettingsSignature = signature;
            return;
        }

        if (!signature.equals(lastSettingsSignature)) {
            lastSettingsSignature = signature;
            Modules.get().save();
        }
    }
}
