package me.waltom.wavexin.core;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;

/** Verifies first-upgrade migration copies settings without adopting Base Finder activation state. */
public final class WaveXinSettingsStoreBehaviorTest {
    public static void main(String[] args) throws Exception {
        NbtCompound legacy = new NbtCompound();
        legacy.putBoolean("active", true);
        NbtCompound legacySettings = new NbtCompound();
        legacySettings.putInt("Container Threshold", 17);
        legacySettings.putInt("Maximum Scan Rings", 50);
        legacy.put("settings", legacySettings);

        NbtCompound recorder = new NbtCompound();
        recorder.putBoolean("active", false);
        recorder.putString("bind", "none");
        NbtCompound recorderSettings = new NbtCompound();
        recorderSettings.putInt("Scan Radius", 4);
        recorderSettings.putInt("Container Threshold", 10);
        recorder.put("settings", recorderSettings);

        String migrated = WaveXinSettingsStore.migratedContainerRecorderTag(legacy, recorder);
        expect(migrated != null, "legacy settings should migrate");
        NbtCompound migratedTag = StringNbtReader.parse(migrated);
        expect(migratedTag.contains("active") && !migratedTag.getBoolean("active"), "migration must retain recorder activation state");
        expect("none".equals(migratedTag.getString("bind")), "migration must retain recorder bind");
        expect(migratedTag.getCompound("settings").getInt("Container Threshold") == 17,
            "migration must copy container settings");
        expect(!migratedTag.getCompound("settings").contains("Maximum Scan Rings"),
            "migration must not retain Base Finder scan settings");
        expect(WaveXinSettingsStore.languageFromJson("{\"features\":{\"language\":\"zh_cn\"}}").equals("zh_cn"),
            "simplified Chinese language setting");
        expect(WaveXinSettingsStore.languageFromJson("{\"features\":{\"language\":\"en_us\"}}").equals("en_us"),
            "English language setting");
        expect(WaveXinSettingsStore.languageFromJson("{\"features\":{\"language\":\"invalid\"}}") == null,
            "unsupported language setting ignored");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
