package me.waltom.wavexin.core;

import meteordevelopment.meteorclient.MeteorClient;
import java.nio.file.Path;

public final class WaveXinDataPaths {
    public static final Path DIRECTORY = MeteorClient.FOLDER.toPath().resolve("wavexin");
    public static final Path SETTINGS_PATH = DIRECTORY.resolve("settings.json");
    public static final Path LICENSE_PATH = DIRECTORY.resolve("license.dat");
    public static final Path KILL_AURA_LICENSE_PATH = DIRECTORY.resolve("killaura-license.dat");
    public static final Path KITS_DIRECTORY = DIRECTORY.resolve("kits");
    public static final Path SCAN_PROGRESS_PATH = DIRECTORY.resolve("scan-progress.json");
    public static final Path CONTAINER_DIRECTORY = DIRECTORY.resolve("container");

    private WaveXinDataPaths() {
    }
}
