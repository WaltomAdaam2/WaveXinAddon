package me.waltom.wavexin.core;

import me.waltom.wavexin.WaveXinAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.nbt.StringNbtReader;
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

    private WaveXinSettingsStore() {
    }

    public static boolean loadEndGatewayFinderEnabled() {
        endGatewayFinderEnabled = false;
        if (!Files.exists(WaveXinDataPaths.SETTINGS_PATH)) return false;

        try {
            endGatewayFinderEnabled = endGatewayFeatureFromJson(Files.readString(WaveXinDataPaths.SETTINGS_PATH, StandardCharsets.UTF_8));
            return endGatewayFinderEnabled;
        } catch (IOException | JsonSyntaxException ignored) {
            return false;
        }
    }

    public static boolean isEndGatewayFinderEnabled() {
        return endGatewayFinderEnabled;
    }

    static boolean endGatewayFeatureFromJson(String json) {
        SettingsDocument document = GSON.fromJson(json, SettingsDocument.class);
        return document != null && document.features != null && document.features.endGatewayFinder;
    }

    public static void enableEndGatewayFinder(Iterable<Module> modules) {
        endGatewayFinderEnabled = true;
        save(modules);
    }

    static boolean restore(Iterable<Module> modules) {
        if (!Files.exists(WaveXinDataPaths.SETTINGS_PATH)) return false;

        try {
            SettingsDocument document = GSON.fromJson(Files.readString(WaveXinDataPaths.SETTINGS_PATH, StandardCharsets.UTF_8), SettingsDocument.class);
            if (document == null || document.modules == null) throw new JsonSyntaxException("Missing module settings");
            endGatewayFinderEnabled = document.features != null && document.features.endGatewayFinder;

            for (Module module : modules) {
                String serialized = document.modules.get(module.name);
                if (serialized == null) continue;
                module.fromTag(StringNbtReader.readCompound(serialized));
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
        int version = 2;
        Map<String, String> modules = new LinkedHashMap<>();
        FeatureFlags features = new FeatureFlags();
    }

    private static class FeatureFlags {
        boolean endGatewayFinder;
    }
}
