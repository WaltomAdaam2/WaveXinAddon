package me.waltom.wavexin.modules.containerrecorder;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinDataPaths;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StorageBlockListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.RainbowColors;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.sound.SoundEvents;
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

/** Records loaded container chunks independently of the scan module that requested it. */
public final class ContainerRecorderModule extends WaveXinModule {
    public static final int DEFAULT_SCAN_RADIUS = 4;
    private static final Path RECORD_PATH = WaveXinDataPaths.CONTAINER_DIRECTORY.resolve("container-records.txt");
    private static final DateTimeFormatter RECORD_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int RESCAN_INTERVAL_TICKS = 20;

    public enum XaeroWaypointColor {
        RANDOM("Random", -1, 170, 170, 170), BLACK("Black", 0, 0, 0, 0), DARK_BLUE("Dark Blue", 1, 0, 0, 170),
        DARK_GREEN("Dark Green", 2, 0, 170, 0), DARK_AQUA("Dark Aqua", 3, 0, 170, 170), DARK_RED("Dark Red", 4, 170, 0, 0),
        DARK_PURPLE("Dark Purple", 5, 170, 0, 170), GOLD("Gold", 6, 255, 170, 0), GRAY("Gray", 7, 170, 170, 170),
        DARK_GRAY("Dark Gray", 8, 85, 85, 85), BLUE("Blue", 9, 85, 85, 255), GREEN("Green", 10, 85, 255, 85),
        AQUA("Aqua", 11, 85, 255, 255), RED("Red", 12, 255, 85, 85), PURPLE("Purple", 13, 255, 85, 255),
        YELLOW("Yellow", 14, 255, 255, 85), WHITE("White", 15, 255, 255, 255);

        private final String title;
        private final int colorId;
        private final Color displayColor;

        XaeroWaypointColor(String title, int colorId, int red, int green, int blue) {
            this.title = title;
            this.colorId = colorId;
            this.displayColor = new Color(red, green, blue);
        }

        public Color displayColor() { return this == RANDOM ? RainbowColors.GLOBAL : displayColor; }
        public int colorId() { return colorId; }
        public static XaeroWaypointColor fromColorId(int colorId) {
            for (XaeroWaypointColor color : values()) if (color.colorId == colorId) return color;
            return RANDOM;
        }
        @Override public String toString() { return title; }
    }

    private final SettingGroup sgRecording = settings.createGroup("Container Recording");
    private final XaeroWaypointBridge xaero = new XaeroWaypointBridge();
    private final ContainerRecorderClaimState scanRequests = new ContainerRecorderClaimState();
    private final Set<Long> recordedChunks = new HashSet<>();
    private final Set<ChunkPos> checkedChunks = new HashSet<>();
    private final Set<UUID> recordedPearls = new HashSet<>();
    private final List<BlockPos> createdWaypointPositions = new ArrayList<>();
    private final Set<Long> warnedMissingChunks = new HashSet<>();

    private final Setting<Integer> scanRadius = sgRecording.add(new IntSetting.Builder().name("Scan Radius")
        .description("Chunk radius checked around the player while Container Recorder is active.")
        .defaultValue(DEFAULT_SCAN_RADIUS).range(1, 30).sliderRange(1, 30).build());
    private final Setting<Integer> threshold = sgRecording.add(new IntSetting.Builder().name("Container Threshold")
        .description("Records the current chunk when it contains at least this many selected containers.")
        .defaultValue(10).min(2).max(200).sliderRange(2, 200).build());
    private final Setting<List<BlockEntityType<?>>> blocks = sgRecording.add(new StorageBlockListSetting.Builder().name("Container Blocks")
        .description("Container block entity types to count, matching Meteor Storage ESP defaults.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS).build());
    private final Setting<Boolean> detectPearls = sgRecording.add(new BoolSetting.Builder().name("Detect Thrown Pearls")
        .description("Announces thrown ender pearls detected while Container Recorder is active.").defaultValue(false).build());
    private final Setting<Boolean> achievementToast = sgRecording.add(new BoolSetting.Builder().name("Achievement Toast")
        .description("Shows a vanilla toast when a container chunk is recorded.").defaultValue(true).build());
    private final Setting<Boolean> toastSound = sgRecording.add(new BoolSetting.Builder().name("Toast Sound")
        .description("Plays the vanilla challenge-toast sound when a container chunk is recorded.").defaultValue(true).build());
    private final Setting<Boolean> xaeroWaypoints = sgRecording.add(new BoolSetting.Builder().name("Xaero Waypoints")
        .description("Creates a Xaero waypoint when a container chunk is recorded. Requires Xaero's Minimap at runtime.").defaultValue(false).build());
    private final Setting<Boolean> recordPearls = sgRecording.add(new BoolSetting.Builder().name("Record Thrown Pearl")
        .description("Creates unlimited Xaero waypoints for detected thrown ender pearls, using Pearl names and P aliases.")
        .defaultValue(false).visible(xaeroWaypoints::get).build());
    private final Setting<XaeroWaypointColor> waypointColor = sgRecording.add(new EnumSetting.Builder<XaeroWaypointColor>().name("Waypoint Color")
        .description("Xaero waypoint color, or a random supported color for each waypoint.")
        .defaultValue(XaeroWaypointColor.RANDOM).visible(xaeroWaypoints::get).build());
    private final Setting<Integer> waypointRadius = sgRecording.add(new IntSetting.Builder().name("Area Radius")
        .description("Chunk radius used to group nearby waypoints into one base area.")
        .defaultValue(5).range(1, 64).sliderRange(1, 32).visible(xaeroWaypoints::get).build());
    private final Setting<Integer> waypointsPerArea = sgRecording.add(new IntSetting.Builder().name("Waypoints per Area")
        .description("Maximum waypoints created within one base area during the current scan.")
        .defaultValue(3).range(1, 100).sliderRange(1, 20).visible(xaeroWaypoints::get).build());
    private final Setting<String> waypointPrefix = sgRecording.add(new StringSetting.Builder().name("Waypoint Prefix")
        .description("Text before the waypoint name.").defaultValue("Base ").visible(xaeroWaypoints::get).build());
    private final Setting<String> waypointSuffix = sgRecording.add(new StringSetting.Builder().name("Waypoint Suffix")
        .description("Text after the waypoint name.").defaultValue("").visible(xaeroWaypoints::get).build());

    private ClientWorld scannedWorld;
    private int scanTicks;
    private int settingsHash = Integer.MIN_VALUE;
    private int nextWaypointNumber = 1;
    private int nextPearlWaypointNumber = 1;
    private boolean warnedEmptyTypes;
    private boolean warnedUnavailable;
    private boolean enablingForScan;
    private XaeroWaypointBridge.Status lastXaeroWarning;
    private long lastXaeroWarningAt;

    public ContainerRecorderModule() {
        super(WaveXinAddon.CATEGORY, "container-recorder", "Records loaded container chunks and optional Xaero waypoints.");
    }

    public void startForScan(WaveXinModule requester) {
        if (!scanRequests.request(requester, isActive())) return;
        enablingForScan = true;
        toggle();
        enablingForScan = false;
    }

    public void stopForScan(WaveXinModule requester) {
        if (scanRequests.release(requester, isActive())) toggle();
    }

    @Override public void onActivate() {
        if (!enablingForScan) scanRequests.manuallyActivated();
        migrateLegacyRecords();
        clearSession();
        validateXaeroSetting();
    }

    @Override public void onDeactivate() {
        scanRequests.deactivated();
        clearSession();
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player != null) scanNear(mc.player.getChunkPos());
    }

    private void scanNear(ChunkPos center) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                WaveXinAddon.LOG.warn("Skipped container scan because player or world is unavailable.");
            }
            return;
        }
        if (scannedWorld != client.world) clearSession();
        scannedWorld = client.world;
        detectPearls(client);

        List<BlockEntityType<?>> selected = blocks.get();
        if (selected == null || selected.isEmpty()) {
            checkedChunks.clear(); scanTicks = 0;
            if (!warnedEmptyTypes) {
                warnedEmptyTypes = true;
                WaveXinAddon.LOG.warn("Skipped container scan because no container block types are selected.");
            }
            return;
        }
        warnedEmptyTypes = false;
        int currentSettingsHash = 31 * threshold.get() + selected.hashCode();
        if (currentSettingsHash != settingsHash) { checkedChunks.clear(); scanTicks = 0; settingsHash = currentSettingsHash; }
        if (++scanTicks >= RESCAN_INTERVAL_TICKS) { checkedChunks.clear(); scanTicks = 0; }

        int radius = Math.min(scanRadius.get(), Math.max(1, client.options.getViewDistance().getValue()));
        checkedChunks.removeIf(chunk -> Math.abs((long) chunk.x - center.x) > radius || Math.abs((long) chunk.z - center.z) > radius
            || !client.world.getChunkManager().isChunkLoaded(chunk.x, chunk.z));
        for (int x = center.x - radius; x <= center.x + radius; x++) for (int z = center.z - radius; z <= center.z + radius; z++)
            if (client.world.getChunkManager().isChunkLoaded(x, z)) recordChunk(client, new ChunkPos(x, z), selected);
    }

    private void recordChunk(MinecraftClient client, ChunkPos chunkPos, List<BlockEntityType<?>> selected) {
        long key = chunkPos.toLong();
        if (recordedChunks.contains(key) || checkedChunks.contains(chunkPos)) return;
        WorldChunk chunk = client.world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false);
        if (chunk == null) { if (warnedMissingChunks.add(key)) WaveXinAddon.LOG.warn("Loaded container candidate had no WorldChunk: {}", chunkPos); return; }
        int count = 0; BlockPos first = null;
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) if (selected.contains(blockEntity.getType())) { count++; if (first == null) first = blockEntity.getPos(); }
        checkedChunks.add(chunkPos);
        if (count < threshold.get()) return;
        recordedChunks.add(key);
        BlockPos playerPos = client.player.getBlockPos();
        BlockPos recordPos = first == null ? playerPos : first;
        appendRecord(chunkPos, recordPos, playerPos, count);
        createWaypoint(recordPos);
        showDiscoveryToast(client, chunkPos, count);
        warning(WaveXinI18n.tr("warning.wavexin.base_finder.base_found", "(highlight)(bold)Base found! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default) | Containers: (highlight)%d(default)", chunkPos.x, chunkPos.z, recordPos.getX(), recordPos.getY(), recordPos.getZ(), count));
    }

    private void showDiscoveryToast(MinecraftClient client, ChunkPos chunkPos, int count) {
        if (achievementToast.get()) SystemToast.show(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
            Text.literal(WaveXinI18n.tr("message.wavexin.container_recorder.toast_title", "Container Recorder")),
            Text.literal(WaveXinI18n.tr("message.wavexin.container_recorder.toast_description", "Recorded %d containers at %d, %d.", count, chunkPos.x, chunkPos.z)));
        if (toastSound.get() && client.player != null) client.player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
    }

    private void detectPearls(MinecraftClient client) {
        if (!detectPearls.get()) return;
        for (Entity entity : client.world.getEntities()) {
            if (entity.getType() != EntityType.ENDER_PEARL || !recordedPearls.add(entity.getUuid())) continue;
            BlockPos pos = entity.getBlockPos(); ChunkPos chunk = new ChunkPos(pos);
            warning(WaveXinI18n.tr("warning.wavexin.base_finder.pearl_found", "(highlight)(bold)Thrown pearl detected! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default)", chunk.x, chunk.z, pos.getX(), pos.getY(), pos.getZ()));
            if (recordPearls.get()) createWaypoint(pos, "Pearl " + nextPearlWaypointNumber, "P" + nextPearlWaypointNumber, false);
        }
    }

    private void appendRecord(ChunkPos chunk, BlockPos record, BlockPos player, int count) {
        String line = "%s | chunk=(%d,%d) | first-container=(%d,%d,%d) | player=(%d,%d,%d) | count=%d%n".formatted(LocalDateTime.now().format(RECORD_TIME_FORMAT), chunk.x, chunk.z, record.getX(), record.getY(), record.getZ(), player.getX(), player.getY(), player.getZ(), count);
        try {
            Files.createDirectories(RECORD_PATH.getParent());
            Files.writeString(RECORD_PATH, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException error) {
            WaveXinAddon.LOG.error("Failed to save container chunk record.", error);
            error(WaveXinI18n.tr("error.wavexin.base_finder.record_save_failed", "Failed to save container chunk record: %s", error.getMessage()));
        }
    }

    private void createWaypoint(BlockPos pos) { createWaypoint(pos, waypointPrefix.get() + nextWaypointNumber + waypointSuffix.get(), initials(waypointPrefix.get() + nextWaypointNumber + waypointSuffix.get()), true); }
    private void createWaypoint(BlockPos pos, String name, String initials, boolean limited) {
        if (!validateXaeroSetting() || (limited && hasReachedWaypointLimit(pos))) return;
        int colorId = waypointColor.get() == XaeroWaypointColor.RANDOM ? ThreadLocalRandom.current().nextInt(16) : waypointColor.get().colorId();
        XaeroWaypointBridge.Result result = xaero.create(pos, name, initials, colorId);
        if (!result.created()) { warnXaeroFailure(result); return; }
        if (limited) { createdWaypointPositions.add(pos.toImmutable()); nextWaypointNumber++; } else nextPearlWaypointNumber++;
        Color color = XaeroWaypointColor.fromColorId(colorId).displayColor();
        int rgb = (color.r & 255) << 16 | (color.g & 255) << 8 | color.b & 255;
        ChatUtils.forceNextPrefixClass(getClass());
        ChatUtils.sendMsg(Text.literal(WaveXinI18n.tr("message.wavexin.base_finder.xaero_created", "Created Xaero waypoint: %s", "")).append(Text.literal(name).setStyle(Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(rgb)))));
    }
    private boolean hasReachedWaypointLimit(BlockPos candidate) {
        int radius = waypointRadius.get() * 16, nearby = 0;
        for (BlockPos existing : createdWaypointPositions) if (Math.abs(existing.getX() - candidate.getX()) <= radius && Math.abs(existing.getZ() - candidate.getZ()) <= radius && ++nearby >= waypointsPerArea.get()) {
            info(WaveXinI18n.tr("message.wavexin.base_finder.xaero_area_limit", "Skipped Xaero waypoint near (%d, %d): area limit of %d reached.", candidate.getX(), candidate.getZ(), waypointsPerArea.get()));
            return true;
        }
        return false;
    }
    private boolean validateXaeroSetting() {
        if (!xaeroWaypoints.get()) return false;
        if (xaero.isAvailable()) return true;
        xaeroWaypoints.set(false);
        warning(WaveXinI18n.tr("warning.wavexin.base_finder.xaero_missing", "Xaero's Minimap was not detected. Xaero Waypoints has been disabled, but container recording will continue."));
        return false;
    }
    private void warnXaeroFailure(XaeroWaypointBridge.Result result) {
        long now = System.currentTimeMillis();
        if (result.status() == lastXaeroWarning && now - lastXaeroWarningAt < 10_000L) return;
        lastXaeroWarning = result.status(); lastXaeroWarningAt = now;
        warning(WaveXinI18n.tr("warning.wavexin.base_finder.xaero_create_failed", "Failed to create Xaero waypoint: %s", result.detail()));
    }
    private static String initials(String name) {
        if (name == null || name.isBlank()) return "B";
        StringBuilder result = new StringBuilder();
        for (String part : name.trim().split("\\s+")) if (!part.isEmpty() && result.length() < 2) result.append(Character.toUpperCase(part.charAt(0)));
        return result.isEmpty() ? "B" : result.toString();
    }
    private void clearSession() {
        recordedChunks.clear(); checkedChunks.clear(); recordedPearls.clear(); createdWaypointPositions.clear(); warnedMissingChunks.clear();
        scannedWorld = null; scanTicks = 0; settingsHash = Integer.MIN_VALUE; nextWaypointNumber = 1; nextPearlWaypointNumber = 1; warnedEmptyTypes = false; warnedUnavailable = false;
    }
    public static void migrateLegacyRecords() {
        Path legacy = MeteorClient.FOLDER.toPath().resolve("base-finder-xin").resolve("container-records.txt");
        if (Files.exists(RECORD_PATH) || !Files.exists(legacy)) return;
        try { Files.createDirectories(RECORD_PATH.getParent()); Files.copy(legacy, RECORD_PATH); WaveXinAddon.LOG.info("Migrated container records to {}.", RECORD_PATH); }
        catch (IOException error) { WaveXinAddon.LOG.error("Could not migrate container records.", error); }
    }
}
