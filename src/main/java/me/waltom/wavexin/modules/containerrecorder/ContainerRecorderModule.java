package me.waltom.wavexin.modules.containerrecorder;

import me.waltom.wavexin.WaveXinAddon;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.waltom.wavexin.core.WaveXinDataPaths;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
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
    private final Setting<Boolean> debugMode = sgRecording.add(new BoolSetting.Builder().name("Debug Mode")
        .description("Logs detailed recorder diagnostics and coordinates to logs/latest.log. May produce large logs.")
        .defaultValue(false).onChanged(value -> WaveXinAddon.LOG.info("[ContainerRecorderDebug] Debug Mode={}", value)).build());
    private final XaeroWaypointBridge xaero = new XaeroWaypointBridge();
    private final ContainerRecorderClaimState scanRequests = new ContainerRecorderClaimState();
    private final LongOpenHashSet recordedChunks = new LongOpenHashSet();
    private final LongOpenHashSet checkedChunks = new LongOpenHashSet();
    private final Set<UUID> recordedPearls = new HashSet<>();
    private final Set<UUID> waypointPearls = new HashSet<>();
    private final List<PendingWaypoint> pendingWaypoints = new ArrayList<>();
    private final java.util.Map<Long, PendingRecord> pendingRecords = new java.util.LinkedHashMap<>();
    private long nextRetryAt;
    private long nextDebugSnapshotAt;
    private record PendingRecord(ChunkPos chunk, BlockPos record, BlockPos player, int count) {}
    private static final class PendingWaypoint {
        final BlockPos pos;
        final String name, initials;
        final int colorId;
        XaeroWaypointBridge.WaypointHandle handle;
        PendingWaypoint(BlockPos pos, String name, String initials, boolean limited, int colorId) {
            this.pos = pos.toImmutable(); this.name = name; this.initials = initials;
            this.colorId = colorId;
        }
    }
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
    private XaeroWaypointBridge.Status lastXaeroWarning;
    private long lastXaeroWarningAt;

    public ContainerRecorderModule() {
        super(WaveXinAddon.CATEGORY, "container-recorder", "Records loaded container chunks and optional Xaero waypoints.");
    }

    public void startForScan(WaveXinModule requester) {
        debug("scan-start requester={} active={}", requester.name, isActive());
        if (!scanRequests.request(requester, isActive())) return;
        toggle();
    }

    public void stopForScan(WaveXinModule requester) {
        debug("scan-stop requester={} active={}", requester.name, isActive());
        if (scanRequests.release(requester, isActive())) toggle();
    }

    @Override public void onActivate() {
        debug("activate world={} retained-records={} pending-records={} pending-waypoints={}", mc.world, recordedChunks.size(), pendingRecords.size(), pendingWaypoints.size());
        migrateLegacyRecords();
        ensureWorld();
        checkedChunks.clear();
        validateXaeroSetting();
    }

    @Override public void onDeactivate() {
        debug("deactivate world={} records={} pearls={} pending-records={} pending-waypoints={}", mc.world, recordedChunks.size(), recordedPearls.size(), pendingRecords.size(), pendingWaypoints.size());
        flushPending();
    }

    @EventHandler private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity.getType() == EntityType.ENDER_PEARL) debug("pearl-added uuid={} pos={} detect={} record={} xaero={}", event.entity.getUuid(), event.entity.getBlockPos(), detectPearls.get(), recordPearls.get(), xaeroWaypoints.get());
        if (mc.world == null) return;
        ensureWorld();
        detectPearl(event.entity);
    }

    @EventHandler private void onChunkData(ChunkDataEvent event) {
        ClientWorld world = mc.world;
        mc.execute(() -> {
            debug("chunk-data chunk={} active={} same-world={} player-ready={}", event.chunk().getPos(), isActive(), mc.world == world, mc.player != null);
            if (!isActive() || mc.world != world || mc.player == null || world == null) return;
            ensureWorld();
            ChunkPos pos = event.chunk().getPos();
            ChunkPos player = mc.player.getChunkPos();
            if (Math.abs((long) pos.x - player.x) > scanRadius.get() || Math.abs((long) pos.z - player.z) > scanRadius.get()) {
                debug("chunk-skip chunk={} reason=outside-radius player-chunk={} radius={}", pos, player, scanRadius.get());
                return;
            }
            debug("chunk-filter chunk={} recorded={} pending={} empty-types={}", pos, recordedChunks.contains(pos.toLong()), pendingRecords.containsKey(pos.toLong()), blocks.get().isEmpty());
            checkedChunks.remove(pos.toLong());
            if (!recordedChunks.contains(pos.toLong()) && !pendingRecords.containsKey(pos.toLong()) && !blocks.get().isEmpty()) recordChunk(mc, pos, blocks.get());
        });
    }

    private void ensureWorld() {
        if (scannedWorld != mc.world) {
            debug("world-change old={} new={}", scannedWorld, mc.world);
            clearSession();
        }
        scannedWorld = mc.world;
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
        ensureWorld();
        if (System.currentTimeMillis() >= nextRetryAt) {
            nextRetryAt = System.currentTimeMillis() + 1000;
            flushPending();
        }
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
        boolean snapshot = debugMode.get() && System.currentTimeMillis() >= nextDebugSnapshotAt;
        if (snapshot) {
            nextDebugSnapshotAt = System.currentTimeMillis() + 1000;
            debug("scan world={} dimension={} player={} radius={} effective-radius={} threshold={} types={} detect-pearls={} record-pearls={} xaero={} area-radius={} area-limit={} toast={} sound={} recorded={} pearls={} pending-records={} pending-waypoints={}",
                client.world, client.world.getRegistryKey().getValue(), client.player.getBlockPos(), scanRadius.get(), radius, threshold.get(), selected,
                detectPearls.get(), recordPearls.get(), xaeroWaypoints.get(), waypointRadius.get(), waypointsPerArea.get(), achievementToast.get(), toastSound.get(), recordedChunks.size(), recordedPearls.size(), pendingRecords.size(), pendingWaypoints.size());
        }
        int skippedRecorded = 0, skippedPending = 0, skippedChecked = 0, unloaded = 0, scanned = 0;
        var checked = checkedChunks.iterator();
        while (checked.hasNext()) {
            long key = checked.nextLong();
            int x = ChunkPos.getPackedX(key), z = ChunkPos.getPackedZ(key);
            if (Math.abs((long) x - center.x) > radius || Math.abs((long) z - center.z) > radius
                || !client.world.getChunkManager().isChunkLoaded(x, z)) checked.remove();
        }
        for (int x = center.x - radius; x <= center.x + radius; x++) for (int z = center.z - radius; z <= center.z + radius; z++) {
            long key = ChunkPos.toLong(x, z);
            if (recordedChunks.contains(key)) { skippedRecorded++; continue; }
            if (pendingRecords.containsKey(key)) { skippedPending++; continue; }
            if (checkedChunks.contains(key)) { skippedChecked++; continue; }
            if (client.world.getChunkManager().isChunkLoaded(x, z)) { scanned++; recordChunk(client, new ChunkPos(x, z), selected); }
            else unloaded++;
        }
        if (snapshot) debug("scan-summary center={} scanned={} already-recorded={} pending={} checked={} unloaded={}", center, scanned, skippedRecorded, skippedPending, skippedChecked, unloaded);
    }

    private void recordChunk(MinecraftClient client, ChunkPos chunkPos, List<BlockEntityType<?>> selected) {
        long key = chunkPos.toLong();
        WorldChunk chunk = client.world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false);
        if (chunk == null) { if (warnedMissingChunks.add(key)) WaveXinAddon.LOG.warn("Loaded container candidate had no WorldChunk: {}", chunkPos); return; }
        int count = 0; BlockPos first = null;
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            boolean matches = selected.contains(blockEntity.getType());
            if (debugMode.get()) debug("block-entity chunk={} pos={} type={} selected={}", chunkPos, blockEntity.getPos(), net.minecraft.registry.Registries.BLOCK_ENTITY_TYPE.getId(blockEntity.getType()), matches);
            if (matches) { count++; if (first == null) first = blockEntity.getPos(); }
        }
        debug("chunk-count chunk={} block-entities={} selected-count={} threshold={} accepted={}", chunkPos, chunk.getBlockEntities().size(), count, threshold.get(), count >= threshold.get());
        checkedChunks.add(key);
        if (count < threshold.get()) return;
        BlockPos playerPos = client.player.getBlockPos();
        BlockPos recordPos = first == null ? playerPos : first;
        if (!appendRecord(chunkPos, recordPos, playerPos, count)) {
            pendingRecords.put(key, new PendingRecord(chunkPos, recordPos.toImmutable(), playerPos.toImmutable(), count));
            debug("record-queued chunk={} count={}", chunkPos, count);
            return;
        }
        recordedChunks.add(key);
        createWaypoint(recordPos);
        showDiscoveryToast(client, chunkPos, count);
        warning(WaveXinI18n.tr("warning.wavexin.base_finder.base_found", "(highlight)(bold)Base found! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default) | Containers: (highlight)%d(default)", chunkPos.x, chunkPos.z, recordPos.getX(), recordPos.getY(), recordPos.getZ(), count));
    }

    private void showDiscoveryToast(MinecraftClient client, ChunkPos chunkPos, int count) {
        debug("notify chunk={} count={} toast={} sound={}", chunkPos, count, achievementToast.get(), toastSound.get());
        if (achievementToast.get()) SystemToast.show(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
            Text.literal(WaveXinI18n.tr("message.wavexin.container_recorder.toast_title", "Container Recorder")),
            Text.literal(WaveXinI18n.tr("message.wavexin.container_recorder.toast_description", "Recorded %d containers at %d, %d.", count, chunkPos.x, chunkPos.z)));
        if (toastSound.get() && client.player != null) client.player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
    }

    private void detectPearls(MinecraftClient client) {
        if (!detectPearls.get()) return;
        for (Entity entity : client.world.getEntities()) {
            detectPearl(entity);
        }
    }

    private void detectPearl(Entity entity) {
        if (!detectPearls.get()) return;
        if (entity.getType() != EntityType.ENDER_PEARL) return;
        BlockPos pos = entity.getBlockPos(); ChunkPos chunk = new ChunkPos(pos);
        if (!recordedPearls.contains(entity.getUuid())) debug("pearl-detected uuid={} pos={} record={} xaero={}", entity.getUuid(), pos, recordPearls.get(), xaeroWaypoints.get());
        if (recordedPearls.add(entity.getUuid())) warning(WaveXinI18n.tr("warning.wavexin.base_finder.pearl_found", "(highlight)(bold)Thrown pearl detected! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default)", chunk.x, chunk.z, pos.getX(), pos.getY(), pos.getZ()));
        if (recordPearls.get() && xaeroWaypoints.get() && waypointPearls.add(entity.getUuid())) createWaypoint(pos, "Pearl " + nextPearlWaypointNumber, "P" + nextPearlWaypointNumber, false);
    }

    private boolean appendRecord(ChunkPos chunk, BlockPos record, BlockPos player, int count) {
        String line = "%s | chunk=(%d,%d) | first-container=(%d,%d,%d) | player=(%d,%d,%d) | count=%d%n".formatted(LocalDateTime.now().format(RECORD_TIME_FORMAT), chunk.x, chunk.z, record.getX(), record.getY(), record.getZ(), player.getX(), player.getY(), player.getZ(), count);
        try {
            Files.createDirectories(RECORD_PATH.getParent());
            Files.writeString(RECORD_PATH, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            debug("record-saved path={} chunk={} pos={} count={}", RECORD_PATH, chunk, record, count);
            return true;
        } catch (IOException error) {
            debug("record-save-failed chunk={} path={} error={}", chunk, RECORD_PATH, error.toString());
            WaveXinAddon.LOG.error("Failed to save container chunk record.", error);
            error(WaveXinI18n.tr("error.wavexin.base_finder.record_save_failed", "Failed to save container chunk record: %s", error.getMessage()));
            return false;
        }
    }

    private void createWaypoint(BlockPos pos) { createWaypoint(pos, waypointPrefix.get() + nextWaypointNumber + waypointSuffix.get(), initials(waypointPrefix.get() + nextWaypointNumber + waypointSuffix.get()), true); }
    private void createWaypoint(BlockPos pos, String name, String initials, boolean limited) {
        if (!validateXaeroSetting()) { debug("waypoint-skip pos={} name={} reason=xaero-disabled-or-unavailable", pos, name); return; }
        if (limited && hasReachedWaypointLimit(pos)) { debug("waypoint-skip pos={} name={} reason=area-limit radius={} limit={}", pos, name, waypointRadius.get(), waypointsPerArea.get()); return; }
        int colorId = waypointColor.get() == XaeroWaypointColor.RANDOM ? ThreadLocalRandom.current().nextInt(16) : waypointColor.get().colorId();
        PendingWaypoint pending = new PendingWaypoint(pos, name, initials, limited, colorId);
        if (limited) { createdWaypointPositions.add(pos.toImmutable()); nextWaypointNumber++; } else nextPearlWaypointNumber++;
        if (!tryWaypoint(pending)) pendingWaypoints.add(pending);
    }

    private boolean tryWaypoint(PendingWaypoint pending) {
        debug("waypoint-attempt pos={} name={} color={} save-only={}", pending.pos, pending.name, pending.colorId, pending.handle != null);
        XaeroWaypointBridge.Result result = pending.handle == null
            ? xaero.create(pending.pos, pending.name, pending.initials, pending.colorId) : XaeroWaypointBridge.retrySave(pending.handle);
        pending.handle = result.handle();
        debug("waypoint-result pos={} name={} status={} detail={} retained-handle={}", pending.pos, pending.name, result.status(), result.detail(), pending.handle != null);
        if (!result.created()) { warnXaeroFailure(result); return false; }
        int colorId = pending.colorId;
        String name = pending.name;
        Color color = XaeroWaypointColor.fromColorId(colorId).displayColor();
        int rgb = (color.r & 255) << 16 | (color.g & 255) << 8 | color.b & 255;
        ChatUtils.forceNextPrefixClass(getClass());
        ChatUtils.sendMsg(Text.literal(WaveXinI18n.tr("message.wavexin.base_finder.xaero_created", "Created Xaero waypoint: %s", "")).append(Text.literal(name).setStyle(Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(rgb)))));
        return true;
    }

    private void flushPending() {
        if (!pendingRecords.isEmpty() || !pendingWaypoints.isEmpty()) debug("retry records={} waypoints={} same-world={} player-ready={} xaero={}", pendingRecords.size(), pendingWaypoints.size(), mc.world == scannedWorld, mc.player != null, xaeroWaypoints.get());
        if (mc.world == null || mc.world != scannedWorld || mc.player == null) return;
        // A queued location survives leaving its chunk or the pearl despawning.
        var records = pendingRecords.values().iterator();
        while (records.hasNext()) {
            PendingRecord record = records.next();
            if (!appendRecord(record.chunk, record.record, record.player, record.count)) break;
            recordedChunks.add(record.chunk.toLong());
            records.remove();
            createWaypoint(record.record);
            if (mc.world == scannedWorld && mc.player != null) showDiscoveryToast(mc, record.chunk, record.count);
        }
        if (xaeroWaypoints.get()) pendingWaypoints.removeIf(this::tryWaypoint);
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
        debug("session-clear recorded={} checked={} pearls={} area-points={} pending-records={} pending-waypoints={}", recordedChunks.size(), checkedChunks.size(), recordedPearls.size(), createdWaypointPositions.size(), pendingRecords.size(), pendingWaypoints.size());
        nextDebugSnapshotAt = 0;
        if (!pendingRecords.isEmpty() || !pendingWaypoints.isEmpty()) WaveXinAddon.LOG.warn("Container Recorder session ended with {} unsaved records and {} pending waypoints.", pendingRecords.size(), pendingWaypoints.size());
        pendingRecords.clear(); pendingWaypoints.clear(); nextRetryAt = 0;
        recordedChunks.clear(); checkedChunks.clear(); recordedPearls.clear(); waypointPearls.clear(); createdWaypointPositions.clear(); warnedMissingChunks.clear();
        scannedWorld = null; scanTicks = 0; settingsHash = Integer.MIN_VALUE; nextWaypointNumber = 1; nextPearlWaypointNumber = 1; warnedEmptyTypes = false; warnedUnavailable = false;
    }
    public static void migrateLegacyRecords() {
        Path legacy = MeteorClient.FOLDER.toPath().resolve("base-finder-xin").resolve("container-records.txt");
        if (Files.exists(RECORD_PATH) || !Files.exists(legacy)) return;
        try { Files.createDirectories(RECORD_PATH.getParent()); Files.copy(legacy, RECORD_PATH); WaveXinAddon.LOG.info("Migrated container records to {}.", RECORD_PATH); }
        catch (IOException error) { WaveXinAddon.LOG.error("Could not migrate container records.", error); }
    }

    private void debug(String message, Object... arguments) {
        if (debugMode.get()) WaveXinAddon.LOG.info("[ContainerRecorderDebug] " + message, arguments);
    }
}
