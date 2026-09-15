package me.waltom.wavexin.core;

import me.waltom.wavexin.WaveXinAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.nbt.NbtCompound;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WaveXinSettingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean endGatewayFinderEnabled;
    private static boolean updateCheckEnabled = true;

    private WaveXinSettingsStore() {
    }

    public static void loadFeatureFlags() {
        endGatewayFinderEnabled = false;
        updateCheckEnabled = true;
        if (!Files.exists(WaveXinDataPaths.SETTINGS_PATH)) return;

        try {
            FeatureFlags features = featureFlagsFromJson(Files.readString(WaveXinDataPaths.SETTINGS_PATH, StandardCharsets.UTF_8));
            endGatewayFinderEnabled = features != null && features.endGatewayFinder;
            updateCheckEnabled = features == null || features.updateCheck;
        } catch (IOException | JsonSyntaxException ignored) {
            // Keep safe legacy defaults when settings cannot be read.
        }
    }

    public static boolean isEndGatewayFinderEnabled() {
        return endGatewayFinderEnabled;
    }

    public static boolean isUpdateCheckEnabled() {
        return updateCheckEnabled;
    }

    static boolean endGatewayFeatureFromJson(String json) {
        FeatureFlags features = featureFlagsFromJson(json);
        return features != null && features.endGatewayFinder;
    }

    static boolean updateCheckFeatureFromJson(String json) {
        FeatureFlags features = featureFlagsFromJson(json);
        return features == null || features.updateCheck;
    }

    private static FeatureFlags featureFlagsFromJson(String json) {
        SettingsDocument document = GSON.fromJson(json, SettingsDocument.class);
        return document == null ? null : document.features;
    }

    public static void enableEndGatewayFinder(Iterable<Module> modules) {
        endGatewayFinderEnabled = true;
        save(modules);
    }

    public static void setUpdateCheckEnabled(boolean enabled, Iterable<Module> modules) {
        updateCheckEnabled = enabled;
        save(modules);
    }

    static boolean restore(Iterable<Module> modules) {
        if (!Files.exists(WaveXinDataPaths.SETTINGS_PATH)) return false;

        try {
            SettingsDocument document = GSON.fromJson(Files.readString(WaveXinDataPaths.SETTINGS_PATH, StandardCharsets.UTF_8), SettingsDocument.class);
            if (document == null || document.modules == null) throw new JsonSyntaxException("Missing module settings");
            endGatewayFinderEnabled = document.features != null && document.features.endGatewayFinder;
            updateCheckEnabled = document.features == null || document.features.updateCheck;

            List<Module> moduleList = new ArrayList<>();
            for (Module module : modules) moduleList.add(module);
            boolean migratedContainerRecorder = migrateContainerRecorder(document, moduleList);

            for (Module module : moduleList) {
                String serialized = document.modules.get(module.name);
                if (serialized == null) continue;
                module.fromTag(StringNbtReader.readCompound(serialized));
            }

            if (migratedContainerRecorder) {
                try {
                    writeAtomically(WaveXinDataPaths.SETTINGS_PATH, GSON.toJson(document));
                } catch (IOException error) {
                    WaveXinAddon.LOG.error("Could not persist migrated Container Recorder settings.", error);
                }
            }

            return true;
        } catch (IOException | JsonSyntaxException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            backupCorruptFile();
            WaveXinAddon.LOG.error("Could not restore WaveXin settings from {}.", WaveXinDataPaths.SETTINGS_PATH, e);
            return false;
        }
    }

    static void save(Iterable<Module> modules) {
        SettingsDocument document = new SettingsDocument();
        document.features.endGatewayFinder = endGatewayFinderEnabled;
        document.features.updateCheck = updateCheckEnabled;
        for (Module module : modules) {
            var tag = module.toTag();
            if (tag != null) document.modules.put(module.name, tag.toString());
        }

        try {
            writeAtomically(WaveXinDataPaths.SETTINGS_PATH, GSON.toJson(document));
        } catch (IOException e) {
            WaveXinAddon.LOG.error("Could not save WaveXin settings to {}.", WaveXinDataPaths.SETTINGS_PATH, e);
        }
    }

    public static void writeAtomically(java.nio.file.Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        java.nio.file.Path temporaryPath = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporaryPath, contents, StandardCharsets.UTF_8);

        try {
            Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void backupCorruptFile() {
        try {
            if (!Files.exists(WaveXinDataPaths.SETTINGS_PATH)) return;
            java.nio.file.Path backupPath = WaveXinDataPaths.SETTINGS_PATH.resolveSibling(
                "settings-corrupt-" + Instant.now().toEpochMilli() + ".json"
            );
            Files.move(WaveXinDataPaths.SETTINGS_PATH, backupPath, StandardCopyOption.REPLACE_EXISTING);
            WaveXinAddon.LOG.warn("Backed up invalid WaveXin settings to {}.", backupPath);
        } catch (IOException backupError) {
            WaveXinAddon.LOG.error("Could not back up invalid WaveXin settings.", backupError);
        }
    }

    private static class SettingsDocument {
        int version = 3;
        Map<String, String> modules = new LinkedHashMap<>();
        FeatureFlags features = new FeatureFlags();
    }

    private static boolean migrateContainerRecorder(SettingsDocument document, List<Module> modules) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (document.modules.containsKey("container-recorder")) return false;
        String legacy = document.modules.get("base-finder");
        if (legacy == null) return false;

        Module recorder = null;
        for (Module module : modules) if (module.name.equals("container-recorder")) { recorder = module; break; }
        if (recorder == null) return false;

        NbtCompound legacyTag = StringNbtReader.readCompound(legacy);
        NbtCompound recorderTag = recorder.toTag();
        if (recorderTag == null) return false;
        String migrated = migratedContainerRecorderTag(legacyTag, recorderTag);
        if (migrated == null) return false;
        document.modules.put("container-recorder", migrated);
        return true;
    }

    static String migratedContainerRecorderTag(NbtCompound legacyTag, NbtCompound recorderTag) {
        if (!legacyTag.contains("settings")) return null;
        NbtCompound legacySettings = legacyTag.getCompound("settings").orElseThrow();
        NbtCompound recorderSettings = recorderTag.getCompound("settings").orElseThrow();
        for (String key : recorderSettings.getKeys()) {
            var value = legacySettings.get(key);
            if (value != null) recorderSettings.put(key, value.copy());
        }
        return recorderTag.toString();
    }

    private static class FeatureFlags {
        boolean endGatewayFinder;
        boolean updateCheck = true;
    }
}
