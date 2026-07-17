package me.waltom.wavexin;

import meteordevelopment.meteorclient.MeteorClient;

import java.nio.file.Path;

final class WaveXinDataPaths {
    static final Path DIRECTORY = MeteorClient.FOLDER.toPath().resolve("wavexin");
    static final Path SETTINGS_PATH = DIRECTORY.resolve("settings.json");
    static final Path SCAN_PROGRESS_PATH = DIRECTORY.resolve("scan-progress.json");
    static final Path CONTAINER_DIRECTORY = DIRECTORY.resolve("container");

    private WaveXinDataPaths() {
    }
}
