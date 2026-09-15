package me.waltom.wavexin.modules.basefinder;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinDataPaths;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StorageBlockListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

/** Shared WaveXin loaded-chunk container recorder for scan modules. */
public final class ContainerRecorder {
    private static final Path RECORD_PATH = WaveXinDataPaths.CONTAINER_DIRECTORY.resolve("container-records.txt");
    private static final DateTimeFormatter RECORD_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int RESCAN_INTERVAL_TICKS = 20;

    private final WaveXinModule owner;
    private final IntSupplier radiusSupplier;
    private final XaeroWaypointBridge xaero = new XaeroWaypointBridge();
    private final Set<Long> recordedChunks = new HashSet<>();
    private final Set<ChunkPos> checkedChunks = new HashSet<>();
    private final Set<UUID> recordedPearls = new HashSet<>();
    private final List<BlockPos> createdWaypointPositions = new ArrayList<>();
    private final Set<Long> warnedMissingChunks = new HashSet<>();

    private ClientWorld scannedWorld;
    private int scanTicks;
    private int settingsHash = Integer.MIN_VALUE;
    private int nextWaypointNumber = 1;
    private int nextPearlWaypointNumber = 1;
    private boolean warnedEmptyTypes;
    private boolean warnedUnavailable;
    private XaeroWaypointBridge.Status lastXaeroWarning;
    private long lastXaeroWarningAt;

    private final Setting<Integer> threshold;
    private final Setting<List<BlockEntityType<?>>> blocks;
    private final Setting<Boolean> detectPearls;
    private final Setting<Boolean> xaeroWaypoints;
    private final Setting<Boolean> recordPearls;
    private final Setting<BaseFinder.XaeroWaypointColor> waypointColor;
    private final Setting<Integer> waypointRadius;
    private final Setting<Integer> waypointsPerArea;
    private final Setting<String> waypointPrefix;
    private final Setting<String> waypointSuffix;

    public ContainerRecorder(WaveXinModule owner, SettingGroup group, IntSupplier radiusSupplier) {
        this.owner = owner;
        this.radiusSupplier = radiusSupplier;
        threshold = group.add(new IntSetting.Builder().name("Container Threshold")
            .description("Records the current chunk when it contains at least this many selected containers.")
            .defaultValue(10).min(2).max(200).sliderRange(2, 200).build());
        blocks = group.add(new StorageBlockListSetting.Builder().name("Container Blocks")
            .description("Container block entity types to count, matching Meteor Storage ESP defaults.")
            .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS).build());
        detectPearls = group.add(new BoolSetting.Builder().name("Detect Thrown Pearls")
            .description("Announces thrown ender pearls detected while the scan module is active.").defaultValue(false).build());
        xaeroWaypoints = group.add(new BoolSetting.Builder().name("Xaero Waypoints")
            .description("Creates a Xaero waypoint when a container chunk is recorded. Requires Xaero's Minimap at runtime.").defaultValue(false).build());
        recordPearls = group.add(new BoolSetting.Builder().name("Record Thrown Pearl")
            .description("Creates unlimited Xaero waypoints for detected thrown ender pearls, using Pearl names and P aliases.")
            .defaultValue(false).visible(xaeroWaypoints::get).build());
        waypointColor = group.add(new XaeroWaypointColorSetting.Builder().name("Waypoint Color")
            .description("Xaero waypoint color, or a random supported color for each waypoint.")
            .defaultValue(BaseFinder.XaeroWaypointColor.RANDOM).visible(xaeroWaypoints::get).build());
        waypointRadius = group.add(new IntSetting.Builder().name("Area Radius")
            .description("Chunk radius used to group nearby waypoints into one base area.")
            .defaultValue(5).range(1, 64).sliderRange(1, 32).visible(xaeroWaypoints::get).build());
        waypointsPerArea = group.add(new IntSetting.Builder().name("Waypoints per Area")
            .description("Maximum waypoints created within one base area during the current scan.")
            .defaultValue(3).range(1, 100).sliderRange(1, 20).visible(xaeroWaypoints::get).build());
        waypointPrefix = group.add(new StringSetting.Builder().name("Waypoint Prefix")
            .description("Text before the waypoint name.").defaultValue("Base ").visible(xaeroWaypoints::get).build());
        waypointSuffix = group.add(new StringSetting.Builder().name("Waypoint Suffix")
            .description("Text after the waypoint name.").defaultValue("").visible(xaeroWaypoints::get).build());
    }

    public void onActivate() {
        clearSession();
        validateXaeroSetting();
    }

    public void onDeactivate() {
        clearSession();
    }

    Setting<Integer> threshold() { return threshold; }
    Setting<List<BlockEntityType<?>>> blocks() { return blocks; }
    Setting<Boolean> detectPearls() { return detectPearls; }
    Setting<Boolean> xaeroWaypoints() { return xaeroWaypoints; }
    Setting<Boolean> recordPearls() { return recordPearls; }
    Setting<BaseFinder.XaeroWaypointColor> waypointColor() { return waypointColor; }
    Setting<Integer> waypointRadius() { return waypointRadius; }
    Setting<Integer> waypointsPerArea() { return waypointsPerArea; }
    Setting<String> waypointPrefix() { return waypointPrefix; }
    Setting<String> waypointSuffix() { return waypointSuffix; }

    public void scanNear(ChunkPos center) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                WaveXinAddon.LOG.warn("Skipped container scan because player or world is unavailable.");
            }
            return;
        }
        if (scannedWorld != mc.world) clearSession();
        scannedWorld = mc.world;
        detectPearls(mc);

        List<BlockEntityType<?>> selected = blocks.get();
        if (selected == null || selected.isEmpty()) {
            checkedChunks.clear();
            scanTicks = 0;
            if (!warnedEmptyTypes) {
                warnedEmptyTypes = true;
                WaveXinAddon.LOG.warn("Skipped container scan because no container block types are selected.");
            }
            return;
        }
        warnedEmptyTypes = false;

        int currentSettingsHash = 31 * threshold.get() + selected.hashCode();
        if (currentSettingsHash != settingsHash) {
            checkedChunks.clear();
            scanTicks = 0;
            settingsHash = currentSettingsHash;
        }
        if (++scanTicks >= RESCAN_INTERVAL_TICKS) {
            checkedChunks.clear();
            scanTicks = 0;
        }

        int radius = Math.min(Math.max(1, radiusSupplier.getAsInt()), Math.max(1, mc.options.getViewDistance().getValue()));
        checkedChunks.removeIf(chunk -> Math.abs((long) chunk.x - center.x) > radius || Math.abs((long) chunk.z - center.z) > radius
            || !mc.world.getChunkManager().isChunkLoaded(chunk.x, chunk.z));
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                if (mc.world.getChunkManager().isChunkLoaded(x, z)) recordChunk(mc, new ChunkPos(x, z), selected);
            }
        }
    }

    private void recordChunk(MinecraftClient mc, ChunkPos chunkPos, List<BlockEntityType<?>> selected) {
        long key = chunkPos.toLong();
        if (recordedChunks.contains(key) || checkedChunks.contains(chunkPos)) return;
        WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false);
        if (chunk == null) {
            if (warnedMissingChunks.add(key)) WaveXinAddon.LOG.warn("Loaded container candidate had no WorldChunk: {}", chunkPos);
            return;
        }
        int count = 0;
        BlockPos first = null;
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!selected.contains(blockEntity.getType())) continue;
            count++;
            if (first == null) first = blockEntity.getPos();
        }
        checkedChunks.add(chunkPos);
        if (count < threshold.get()) return;

        recordedChunks.add(key);
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos recordPos = first == null ? playerPos : first;
        appendRecord(chunkPos, recordPos, playerPos, count);
        createWaypoint(recordPos);
        owner.warning(WaveXinI18n.tr("warning.wavexin.base_finder.base_found",
            "(highlight)(bold)Base found! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default) | Containers: (highlight)%d(default)",
            chunkPos.x, chunkPos.z, recordPos.getX(), recordPos.getY(), recordPos.getZ(), count));
    }

    private void detectPearls(MinecraftClient mc) {
        if (!detectPearls.get()) return;
        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() != EntityType.ENDER_PEARL || !recordedPearls.add(entity.getUuid())) continue;
            BlockPos pos = entity.getBlockPos();
            ChunkPos chunk = new ChunkPos(pos);
            owner.warning(WaveXinI18n.tr("warning.wavexin.base_finder.pearl_found",
                "(highlight)(bold)Thrown pearl detected! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default)",
                chunk.x, chunk.z, pos.getX(), pos.getY(), pos.getZ()));
            if (recordPearls.get()) createWaypoint(pos, BaseFinderStateLogic.pearlWaypointName(nextPearlWaypointNumber), BaseFinderStateLogic.pearlWaypointAlias(nextPearlWaypointNumber), false);
        }
    }

    private void appendRecord(ChunkPos chunk, BlockPos record, BlockPos player, int count) {
        String line = "%s | chunk=(%d,%d) | first-container=(%d,%d,%d) | player=(%d,%d,%d) | count=%d%n".formatted(
            LocalDateTime.now().format(RECORD_TIME_FORMAT), chunk.x, chunk.z, record.getX(), record.getY(), record.getZ(),
            player.getX(), player.getY(), player.getZ(), count);
        try {
            Files.createDirectories(RECORD_PATH.getParent());
            Files.writeString(RECORD_PATH, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException error) {
            WaveXinAddon.LOG.error("Failed to save container chunk record.", error);
            owner.error(WaveXinI18n.tr("error.wavexin.base_finder.record_save_failed", "Failed to save container chunk record: %s", error.getMessage()));
        }
    }

    private void createWaypoint(BlockPos pos) {
        String name = waypointPrefix.get() + nextWaypointNumber + waypointSuffix.get();
        createWaypoint(pos, name, initials(name), true);
    }

    private void createWaypoint(BlockPos pos, String name, String initials, boolean limited) {
        if (!validateXaeroSetting() || (limited && hasReachedWaypointLimit(pos))) return;
        int colorId = waypointColor.get() == BaseFinder.XaeroWaypointColor.RANDOM ? ThreadLocalRandom.current().nextInt(16) : waypointColor.get().colorId();
        XaeroWaypointBridge.Result result = xaero.create(pos, name, initials, colorId);
        if (!result.created()) {
            warnXaeroFailure(result);
            return;
        }
        if (limited) {
            createdWaypointPositions.add(pos.toImmutable());
            nextWaypointNumber++;
        } else nextPearlWaypointNumber++;
        Color color = BaseFinder.XaeroWaypointColor.fromColorId(colorId).displayColor();
        int rgb = (color.r & 255) << 16 | (color.g & 255) << 8 | color.b & 255;
        ChatUtils.forceNextPrefixClass(owner.getClass());
        ChatUtils.sendMsg(Text.literal(WaveXinI18n.tr("message.wavexin.base_finder.xaero_created", "Created Xaero waypoint: %s", ""))
            .append(Text.literal(name).setStyle(Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(rgb)))));
    }

    private boolean hasReachedWaypointLimit(BlockPos candidate) {
        int radius = waypointRadius.get() * 16;
        int nearby = 0;
        for (BlockPos existing : createdWaypointPositions) {
            if (Math.abs(existing.getX() - candidate.getX()) <= radius && Math.abs(existing.getZ() - candidate.getZ()) <= radius
                && ++nearby >= waypointsPerArea.get()) {
                owner.info(WaveXinI18n.tr("message.wavexin.base_finder.xaero_area_limit", "Skipped Xaero waypoint near (%d, %d): area limit of %d reached.", candidate.getX(), candidate.getZ(), waypointsPerArea.get()));
                return true;
            }
        }
        return false;
    }

    private boolean validateXaeroSetting() {
        if (!xaeroWaypoints.get()) return false;
        if (xaero.isAvailable()) return true;
        xaeroWaypoints.set(false);
        owner.warning(WaveXinI18n.tr("warning.wavexin.base_finder.xaero_missing", "Xaero's Minimap was not detected. Xaero Waypoints has been disabled, but container recording will continue."));
        return false;
    }

    private void warnXaeroFailure(XaeroWaypointBridge.Result result) {
        long now = System.currentTimeMillis();
        if (result.status() == lastXaeroWarning && now - lastXaeroWarningAt < 10_000L) return;
        lastXaeroWarning = result.status();
        lastXaeroWarningAt = now;
        owner.warning(WaveXinI18n.tr("warning.wavexin.base_finder.xaero_create_failed", "Failed to create Xaero waypoint: %s", result.detail()));
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "B";
        StringBuilder result = new StringBuilder();
        for (String part : name.trim().split("\\s+")) if (!part.isEmpty() && result.length() < 2) result.append(Character.toUpperCase(part.charAt(0)));
        return result.isEmpty() ? "B" : result.toString();
    }

    private void clearSession() {
        recordedChunks.clear(); checkedChunks.clear(); recordedPearls.clear(); createdWaypointPositions.clear(); warnedMissingChunks.clear();
        scannedWorld = null; scanTicks = 0; settingsHash = Integer.MIN_VALUE; nextWaypointNumber = 1; nextPearlWaypointNumber = 1;
        warnedEmptyTypes = false; warnedUnavailable = false;
    }

    public static void migrateLegacyRecords() {
        Path legacy = MeteorClient.FOLDER.toPath().resolve("base-finder-xin").resolve("container-records.txt");
        if (Files.exists(RECORD_PATH) || !Files.exists(legacy)) return;
        try {
            Files.createDirectories(RECORD_PATH.getParent());
            Files.copy(legacy, RECORD_PATH);
            WaveXinAddon.LOG.info("Migrated container records to {}.", RECORD_PATH);
        } catch (IOException error) {
            WaveXinAddon.LOG.error("Could not migrate container records.", error);
        }
    }
}
