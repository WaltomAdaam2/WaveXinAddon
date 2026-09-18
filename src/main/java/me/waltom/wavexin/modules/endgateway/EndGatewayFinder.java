package me.waltom.wavexin.modules.endgateway;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinDataPaths;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.i18n.WaveXinI18n;
import me.waltom.wavexin.modules.NavigationModuleControl;
import me.waltom.wavexin.modules.containerrecorder.ContainerRecorderModule;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.LocalRandom;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.TheEndBiomeSource;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

/** Locally predicts and visits vanilla End return gateways for the configured seed. */
public final class EndGatewayFinder extends WaveXinModule {
    static final int TILE_SIZE_CHUNKS = 32;
    static final int TILE_SIZE_BLOCKS = TILE_SIZE_CHUNKS * 16;
    static final int PREFETCH_DISTANCE_BLOCKS = 100;
    static final int DEFAULT_ROLLING_RADIUS_CHUNKS = 1000;
    private static final int BATCH_SIZE_TILES = 32;
    private static final int ROUTE_WINDOW_SIZE = 256;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 1000L;
    private static final SystemToast.Type SCAN_TOAST_TYPE = new SystemToast.Type(Long.MAX_VALUE);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final Setting<String> worldSeed = sgGeneral.add(new StringSetting.Builder().name("World Seed")
        .description("World seed used to predict End return gateways locally.").defaultValue("3763250021837776656").build());
    private final Setting<GenerationVersion> generationVersion = sgGeneral.add(new EnumSetting.Builder<GenerationVersion>().name("Generation Version")
        .description("Uses the selected version's End gateway random placement rules.").defaultValue(GenerationVersion.V1_12).build());
    private final Setting<Integer> rollingRadiusChunks = sgGeneral.add(new IntSetting.Builder().name("Rolling Radius (Chunks)")
        .description("Circular scan radius in chunks. Results are cached for this game session.")
        .defaultValue(DEFAULT_ROLLING_RADIUS_CHUNKS).min(8).max(100000).sliderMax(100000).build());
    private final Setting<Double> arrivalDistance = sgGeneral.add(new DoubleSetting.Builder().name("Arrival Distance")
        .defaultValue(16.0).min(1.0).max(128.0).build());
    private final Setting<Boolean> autoLook = sgGeneral.add(new BoolSetting.Builder().name("Auto Look").defaultValue(true).build());
    private final Setting<Boolean> stayAtGateway = sgGeneral.add(new BoolSetting.Builder().name("Stay At Gateway")
        .description("Stay at each gateway after arrival.").defaultValue(false).build());
    private final Setting<Integer> stayDuration = sgGeneral.add(new IntSetting.Builder().name("Stay Duration")
        .description("Seconds to stay at each gateway.").defaultValue(5).min(1).max(300).sliderMax(60).visible(stayAtGateway::get).build());
    private final Setting<Boolean> startContainerRecorder = sgGeneral.add(new BoolSetting.Builder().name("Start Container Recorder")
        .description("Starts Container Recorder after EndBaseFinder has begun.").defaultValue(true).build());
    private final Setting<PathAlgorithm> pathAlgorithm = sgGeneral.add(new EnumSetting.Builder<PathAlgorithm>().name("Path Algorithm")
        .description("Order used to visit unvisited gateways.").defaultValue(PathAlgorithm.NEAREST_NEIGHBOR).build());
    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder().name("Render Distance")
        .defaultValue(1024).min(64).max(1024).sliderMax(1024).build());
    private final Setting<SettingColor> targetColor = sgRender.add(color("Target Color", 255, 165, 0, 60).build());
    private final Setting<SettingColor> targetLine = sgRender.add(color("Target Line", 255, 165, 0, 220).build());
    private final Setting<SettingColor> legacyGatewayColor = sgRender.add(color("1.12 Gateway Color", 0, 255, 0, 40).build());
    private final Setting<SettingColor> legacyGatewayLine = sgRender.add(color("1.12 Gateway Line", 0, 255, 0, 180).build());
    private final Setting<SettingColor> modernGatewayColor = sgRender.add(color("1.20.4 Gateway Color", 255, 0, 0, 40).build());
    private final Setting<SettingColor> modernGatewayLine = sgRender.add(color("1.20.4 Gateway Line", 255, 0, 0, 180).build());
    private final Setting<SettingColor> visitedColor = sgRender.add(color("Visited Color", 0, 0, 255, 30).build());
    private final Setting<SettingColor> visitedLine = sgRender.add(color("Visited Line", 0, 0, 255, 80).build());
    private final Setting<ShapeMode> renderMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("Render Mode").defaultValue(ShapeMode.Both).build());
    private final ContainerRecorderModule containerRecorder;

    private final Object cacheLock = new Object();
    private final Set<Long> completedTiles = new HashSet<>();
    private final Set<Long> activeCompletedTiles = new HashSet<>();
    private final Map<Long, List<Gateway>> cachedGateways = new HashMap<>();
    private final List<Gateway> gateways = new ArrayList<>();
    private final Set<Gateway> activeGatewaySet = new HashSet<>();
    private final Set<Gateway> visited = new HashSet<>();
    private List<Integer> route = List.of();
    private int target = -1;
    private volatile int scanGeneration;
    private volatile int scanRevision;
    private volatile int rollingCenterX;
    private volatile int rollingCenterZ;
    private volatile int activeRadiusChunks;
    private volatile boolean scanning;
    private volatile int runningGeneration = -1;
    private long completedViewTiles;
    private long totalViewTiles;
    private long lastToastUpdate;
    private long stayUntil;
    private int arrivedCount;
    private int pendingArrivalNumber;
    private boolean forcingForward;
    private boolean containerRecorderRequested;
    private boolean activationRejected;
    private long activeSeed;
    private GenerationVersion activeGenerationVersion;
    private ClientWorld activeWorld;
    private ClientWorld cachedWorld;
    private long cachedSeed = Long.MIN_VALUE;
    private GenerationVersion cachedGenerationVersion;

    public EndGatewayFinder(ContainerRecorderModule containerRecorder) {
        super(WaveXinAddon.CATEGORY, "end-gateway-finder", "End Return Gateway Finder");
        this.containerRecorder = containerRecorder;
    }

    @Override
    public void onActivate() {
        if (NavigationModuleControl.reportConflictingActivation(this)) {
            activationRejected = true;
            toggle();
            return;
        }
        activationRejected = false;
        if (mc.player == null || mc.world == null) return;
        if (!mc.world.getRegistryKey().equals(World.END)) {
            error("EndBaseFinder can only run in The End.");
            toggle();
            return;
        }
        NavigationModuleControl.suppressMovementInput();

        gateways.clear();
        activeGatewaySet.clear();
        visited.clear();
        route = List.of();
        target = -1;
        stayUntil = 0;
        arrivedCount = 0;
        pendingArrivalNumber = 0;
        scanGeneration++;
        activeWorld = mc.world;
        activeSeed = parsedSeed(worldSeed.get());
        activeGenerationVersion = generationVersion.get();
        ensureSessionCache(activeWorld, activeSeed, activeGenerationVersion);
        loadVisited();

        containerRecorderRequested = startContainerRecorder.get();
        if (containerRecorderRequested) containerRecorder.startForScan(this);
        configureRollingView(mc.player.getBlockX(), mc.player.getBlockZ(), rollingRadiusChunks.get(), false);
    }

    @Override
    public void onDeactivate() {
        if (activationRejected) {
            activationRejected = false;
            return;
        }
        scanGeneration++;
        scanning = false;
        saveVisited();
        releaseForward();
        NavigationModuleControl.restoreMovementInput();
        stayUntil = 0;
        pendingArrivalNumber = 0;
        hideScanToast();
        releaseContainerRecorder();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.world.getRegistryKey().equals(World.END)) {
            error("EndBaseFinder can only run in The End.");
            toggle();
            return;
        }
        NavigationModuleControl.suppressMovementInput();

        refreshConfigurationAndView();
        if (scanning && runningGeneration != scanGeneration) startScanWorker(scanGeneration, activeSeed, activeGenerationVersion);

        if (stayUntil > 0) {
            releaseForward();
            if (System.currentTimeMillis() >= stayUntil) {
                stayUntil = 0;
                int arrivalNumber = pendingArrivalNumber;
                pendingArrivalNumber = 0;
                nextTarget(arrivalNumber);
            }
            return;
        }

        if (target >= 0 && target < gateways.size()) {
            Gateway gateway = gateways.get(target);
            double dx = gateway.x + 0.5 - mc.player.getX();
            double dz = gateway.z + 0.5 - mc.player.getZ();
            if (dx * dx + dz * dz <= arrivalDistance.get() * arrivalDistance.get()) {
                int arrivalNumber = 0;
                if (visited.add(gateway)) {
                    arrivalNumber = ++arrivedCount;
                    saveVisited();
                }
                releaseForward();
                target = -1;
                if (stayAtGateway.get()) {
                    pendingArrivalNumber = arrivalNumber;
                    stayUntil = System.currentTimeMillis() + stayDuration.get() * 1000L;
                    return;
                }
                nextTarget(arrivalNumber);
            }
        }
        moveToTarget();
    }

    private void refreshConfigurationAndView() {
        long seed = parsedSeed(worldSeed.get());
        GenerationVersion version = generationVersion.get();
        int radius = rollingRadiusChunks.get();
        boolean identityChanged = mc.world != activeWorld || seed != activeSeed || version != activeGenerationVersion;

        if (identityChanged) {
            saveVisited();
            scanGeneration++;
            activeWorld = mc.world;
            activeSeed = seed;
            activeGenerationVersion = version;
            ensureSessionCache(activeWorld, activeSeed, activeGenerationVersion);
            visited.clear();
            loadVisited();
            configureRollingView(mc.player.getBlockX(), mc.player.getBlockZ(), radius, false);
        } else if (radius != activeRadiusChunks) {
            configureRollingView(mc.player.getBlockX(), mc.player.getBlockZ(), radius, false);
        } else if (shouldAdvanceView(rollingCenterX, rollingCenterZ, activeRadiusChunks, mc.player.getBlockX(), mc.player.getBlockZ())) {
            configureRollingView(mc.player.getBlockX(), mc.player.getBlockZ(), radius, true);
        }
    }

    private void ensureSessionCache(ClientWorld world, long seed, GenerationVersion version) {
        synchronized (cacheLock) {
            if (cachedWorld == world && cachedSeed == seed && cachedGenerationVersion == version) return;
            completedTiles.clear();
            cachedGateways.clear();
            cachedWorld = world;
            cachedSeed = seed;
            cachedGenerationVersion = version;
        }
    }

    private void configureRollingView(int centerX, int centerZ, int radiusChunks, boolean nextArea) {
        rollingCenterX = centerX;
        rollingCenterZ = centerZ;
        activeRadiusChunks = radiusChunks;
        scanRevision++;
        totalViewTiles = countTilesInCircle(centerX, centerZ, radiusChunks);
        completedViewTiles = countCompletedTilesInView(centerX, centerZ, radiusChunks);
        rebuildActiveGateways();
        scanning = completedViewTiles < totalViewTiles;
        showScanToast(scanning ? nextArea ? ScanState.NEXT_AREA : ScanState.SCANNING : ScanState.COMPLETE, true);
        if (scanning) startScanWorker(scanGeneration, activeSeed, activeGenerationVersion);
    }

    private void startScanWorker(int generation, long seed, GenerationVersion version) {
        if (!scanning || runningGeneration == generation) return;
        runningGeneration = generation;
        Thread thread = new Thread(() -> runScanWorker(generation, seed, version), "wavexin-end-gateway-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void runScanWorker(int generation, long seed, GenerationVersion version) {
        try {
            ScanContext context = new ScanContext(seed);
            int revision = -1;
            TileScheduler scheduler = null;
            List<TileResult> batch = new ArrayList<>();
            long lastUpdate = System.currentTimeMillis();

            while (isActive() && generation == scanGeneration) {
                int currentRevision = scanRevision;
                if (revision != currentRevision) {
                    postBatch(generation, revision, batch, false, null);
                    batch = new ArrayList<>();
                    revision = currentRevision;
                    scheduler = new TileScheduler(rollingCenterX, rollingCenterZ, activeRadiusChunks);
                }

                Tile tile = scheduler.next(this::isTileCached);
                if (tile == null) {
                    postBatch(generation, revision, batch, true, null);
                    if (revision == scanRevision) scanning = false;
                    return;
                }

                List<Gateway> result = scanTile(seed, version, context, tile);
                if (!isActive() || generation != scanGeneration) return;
                long key = pack(tile.x, tile.z);
                boolean added;
                synchronized (cacheLock) {
                    added = completedTiles.add(key);
                    if (added && !result.isEmpty()) cachedGateways.put(key, result);
                }
                if (!added) continue;

                batch.add(new TileResult(tile, result));
                long now = System.currentTimeMillis();
                if (batch.size() >= BATCH_SIZE_TILES || now - lastUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                    postBatch(generation, revision, batch, false, null);
                    batch = new ArrayList<>();
                    lastUpdate = now;
                }
            }
        } catch (RuntimeException failure) {
            postBatch(generation, scanRevision, List.of(), false, failure);
        } finally {
            if (runningGeneration == generation) runningGeneration = -1;
        }
    }

    private void postBatch(int generation, int revision, List<TileResult> results, boolean complete, RuntimeException failure) {
        List<TileResult> copy = results.isEmpty() ? List.of() : List.copyOf(results);
        mc.execute(() -> applyBatch(generation, revision, copy, complete, failure));
    }

    private void applyBatch(int generation, int revision, List<TileResult> results, boolean complete, RuntimeException failure) {
        if (!isActive() || generation != scanGeneration) return;
        if (failure != null) {
            scanning = false;
            error("Gateway scan failed: %s", failure.getMessage());
            showScanToast(ScanState.FAILED, true);
            releaseContainerRecorder();
            toggle();
            return;
        }

        boolean addedGateway = false;
        for (TileResult result : results) {
            long tileKey = pack(result.tile.x, result.tile.z);
            if (tileIntersectsCircle(rollingCenterX, rollingCenterZ, activeRadiusChunks, result.tile.x, result.tile.z)
                && activeCompletedTiles.add(tileKey)) completedViewTiles = activeCompletedTiles.size();
            for (Gateway gateway : result.gateways) {
                if (!gatewayInsideView(gateway) || !activeGatewaySet.add(gateway)) continue;
                gateways.add(gateway);
                addedGateway = true;
            }
        }

        if (target < 0 && addedGateway && stayUntil == 0) nextTarget();
        if (complete && revision == scanRevision) {
            scanning = false;
            completedViewTiles = Math.min(completedViewTiles, totalViewTiles);
            showScanToast(ScanState.COMPLETE, true);
        } else if (!results.isEmpty()) {
            showScanToast(ScanState.SCANNING, false);
        }
    }

    private boolean isTileCached(long key) {
        synchronized (cacheLock) {
            return completedTiles.contains(key);
        }
    }

    private long countCompletedTilesInView(int centerX, int centerZ, int radiusChunks) {
        activeCompletedTiles.clear();
        synchronized (cacheLock) {
            for (long key : completedTiles) {
                if (tileIntersectsCircle(centerX, centerZ, radiusChunks, (int) (key >> 32), (int) key)) activeCompletedTiles.add(key);
            }
        }
        return activeCompletedTiles.size();
    }

    private void rebuildActiveGateways() {
        Gateway current = target >= 0 && target < gateways.size() ? gateways.get(target) : null;
        List<Gateway> rebuilt = new ArrayList<>();
        synchronized (cacheLock) {
            for (Map.Entry<Long, List<Gateway>> entry : cachedGateways.entrySet()) {
                long key = entry.getKey();
                if (!tileIntersectsCircle(rollingCenterX, rollingCenterZ, activeRadiusChunks, (int) (key >> 32), (int) key)) continue;
                for (Gateway gateway : entry.getValue()) if (gatewayInsideView(gateway)) rebuilt.add(gateway);
            }
        }
        rebuilt.sort(Comparator.comparingLong((Gateway gateway) -> squaredDistance(gateway.x, gateway.z, rollingCenterX, rollingCenterZ))
            .thenComparingInt(gateway -> gateway.z >> 4).thenComparingInt(gateway -> gateway.x >> 4).thenComparing(gateway -> gateway.version));
        gateways.clear();
        gateways.addAll(rebuilt);
        activeGatewaySet.clear();
        activeGatewaySet.addAll(rebuilt);
        target = current == null ? -1 : gateways.indexOf(current);
        if (stayUntil == 0 && (target < 0 || visited.contains(gateways.get(target)))) nextTarget();
    }

    private boolean gatewayInsideView(Gateway gateway) {
        long radius = (long) activeRadiusChunks * 16L;
        long dx = (long) gateway.x - rollingCenterX;
        long dz = (long) gateway.z - rollingCenterZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    private void nextTarget() {
        nextTarget(0);
    }

    private void nextTarget(int arrivalNumber) {
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < gateways.size(); i++) if (!visited.contains(gateways.get(i))) remaining.add(i);
        List<Integer> candidates = nearestWindow(gateways, remaining, mc.player == null ? 0 : mc.player.getX(), mc.player == null ? 0 : mc.player.getZ(), ROUTE_WINDOW_SIZE);
        route = route(gateways, candidates, mc.player == null ? 0 : mc.player.getX(), mc.player == null ? 0 : mc.player.getZ(), pathAlgorithm.get());
        if (route.isEmpty()) {
            target = -1;
            releaseForward();
            if (arrivalNumber > 0) info(arrivalMessage(arrivalNumber, null));
            return;
        }
        int next = route.getFirst();
        if (next == target) return;
        target = next;
        Gateway gateway = gateways.get(target);
        if (arrivalNumber > 0) info(arrivalMessage(arrivalNumber, gateway));
        else info("-> (%d, %d)", gateway.x, gateway.z);
    }

    static String arrivalMessage(int arrivalNumber, Gateway next) {
        return next == null
            ? "Arrived at #%d".formatted(arrivalNumber)
            : "Arrived at #%d -> (%d, %d)".formatted(arrivalNumber, next.x, next.z);
    }

    private void moveToTarget() {
        if (target < 0 || target >= gateways.size() || stayUntil > 0 || mc.player == null) {
            releaseForward();
            return;
        }
        Gateway gateway = gateways.get(target);
        double dx = gateway.x + 0.5 - mc.player.getX();
        double dz = gateway.z + 0.5 - mc.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.5) {
            releaseForward();
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYaw(yaw);
        mc.player.setHeadYaw(yaw);
        if (autoLook.get()) mc.player.setPitch((float) Math.max(-90, Math.min(90, Math.toDegrees(Math.atan2(-0.5, horizontal)))));
        mc.options.forwardKey.setPressed(true);
        forcingForward = true;
    }

    private void releaseForward() {
        if (forcingForward && mc.options != null) mc.options.forwardKey.setPressed(false);
        forcingForward = false;
    }

    private void releaseContainerRecorder() {
        if (!containerRecorderRequested) return;
        containerRecorderRequested = false;
        containerRecorder.stopForScan(this);
    }

    private void showScanToast(ScanState state, boolean force) {
        if (mc == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastToastUpdate < PROGRESS_UPDATE_INTERVAL_MS) return;
        lastToastUpdate = now;
        long completedChunks = Math.min(completedViewTiles, totalViewTiles) * TILE_SIZE_CHUNKS * TILE_SIZE_CHUNKS;
        long totalChunks = totalViewTiles * TILE_SIZE_CHUNKS * TILE_SIZE_CHUNKS;
        long percent = totalViewTiles == 0 ? 100 : Math.min(100, completedViewTiles * 100 / totalViewTiles);
        String stateText = switch (state) {
            case SCANNING -> WaveXinI18n.tr("status.wavexin.end_gateway_finder.scanning", "Scanning");
            case NEXT_AREA -> WaveXinI18n.tr("status.wavexin.end_gateway_finder.next_area", "Scanning next area");
            case COMPLETE -> WaveXinI18n.tr("status.wavexin.end_gateway_finder.complete", "Complete");
            case FAILED -> WaveXinI18n.tr("status.wavexin.end_gateway_finder.failed", "Failed");
        };
        SystemToast.show(mc.getToastManager(), SCAN_TOAST_TYPE,
            Text.literal(WaveXinI18n.tr("message.wavexin.end_gateway_finder.scan_toast_title", "End Gateway Scan - %s", stateText)),
            Text.literal(WaveXinI18n.tr("message.wavexin.end_gateway_finder.scan_toast_progress", "%d/%d chunks (%d%%)", completedChunks, totalChunks, percent)
                + "\n" + WaveXinI18n.tr("message.wavexin.end_gateway_finder.scan_toast_gateways", "Gateways: %d", gateways.size())));
    }

    private void hideScanToast() {
        if (mc != null) SystemToast.hide(mc.getToastManager(), SCAN_TOAST_TYPE);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        double maximum = (double) renderDistance.get() * renderDistance.get();
        for (int i = 0; i < gateways.size(); i++) {
            Gateway gateway = gateways.get(i);
            double dx = gateway.x - mc.player.getX();
            double dz = gateway.z - mc.player.getZ();
            if (dx * dx + dz * dz > maximum) continue;
            boolean isTarget = i == target;
            boolean isVisited = visited.contains(gateway);
            SettingColor side = isTarget ? targetColor.get() : isVisited ? visitedColor.get() : gateway.version == GenerationVersion.V1_12 ? legacyGatewayColor.get() : modernGatewayColor.get();
            SettingColor line = isTarget ? targetLine.get() : isVisited ? visitedLine.get() : gateway.version == GenerationVersion.V1_12 ? legacyGatewayLine.get() : modernGatewayLine.get();
            if (side.a <= 5 && line.a <= 5) continue;
            event.renderer.box(gateway.x, 0, gateway.z, gateway.x + 1, 384, gateway.z + 1, side, line, renderMode.get(), 0);
        }
    }

    private static List<Gateway> scanTile(long seed, GenerationVersion version, ScanContext context, Tile tile) {
        List<Gateway> result = new ArrayList<>();
        List<GenerationVersion> versions = scanVersions(version);
        int minChunkX = tile.x * TILE_SIZE_CHUNKS;
        int minChunkZ = tile.z * TILE_SIZE_CHUNKS;
        for (int chunkZ = minChunkZ; chunkZ < minChunkZ + TILE_SIZE_CHUNKS; chunkZ++) {
            for (int chunkX = minChunkX; chunkX < minChunkX + TILE_SIZE_CHUNKS; chunkX++) {
                scanChunk(seed, chunkX, chunkZ, versions, context, result::add);
            }
        }
        return result;
    }

    static List<Gateway> scan(long seed, int centerX, int centerZ, int radius, int queryRadius, ScanShape shape) {
        List<Gateway> result = new ArrayList<>();
        ScanContext context = new ScanContext(seed);
        int minChunkX = (centerX - queryRadius) >> 4;
        int maxChunkX = (centerX + queryRadius) >> 4;
        int minChunkZ = (centerZ - queryRadius) >> 4;
        int maxChunkZ = (centerZ + queryRadius) >> 4;
        double queryRadiusSquared = (double) queryRadius * queryRadius;
        visitChunksFromCenter(minChunkX, maxChunkX, minChunkZ, maxChunkZ, centerX >> 4, centerZ >> 4, (chunkX, chunkZ) -> {
            List<Gateway> chunk = new ArrayList<>();
            scanChunk(seed, chunkX, chunkZ, List.of(GenerationVersion.V1_20_4), context, chunk::add);
            for (Gateway gateway : chunk) {
                double dx = gateway.x - centerX;
                double dz = gateway.z - centerZ;
                if (dx * dx + dz * dz > queryRadiusSquared || shape == ScanShape.SQUARE && (Math.abs(dx) > radius || Math.abs(dz) > radius)) continue;
                result.add(gateway);
            }
        });
        return result;
    }

    private static void scanChunk(long seed, int chunkX, int chunkZ, List<GenerationVersion> versions, ScanContext context, Consumer<Gateway> consumer) {
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        for (GenerationVersion version : versions) {
            Gateway candidate = version == GenerationVersion.V1_12
                ? legacyCandidate(seed, chunkX, chunkZ, context.legacyX, context.legacyZ, context.legacyRandom)
                : modernCandidate(seed, blockX, blockZ, context.modernRandom);
            if (candidate == null) continue;
            int topY = context.generator.getHeight(candidate.x, candidate.z, Heightmap.Type.MOTION_BLOCKING, context.heightLimit, context.noiseConfig);
            if (!hasSurface(topY, context.heightLimit.getBottomY())) continue;
            int y = topY + (version == GenerationVersion.V1_12 ? context.legacyRandom.nextInt(7) + 3 : context.modernRandom.nextBetween(3, 9));
            if (!context.biomeAccess.getBiome(new BlockPos(candidate.x, y, candidate.z)).matchesKey(BiomeKeys.END_HIGHLANDS)) continue;
            consumer.accept(candidate);
        }
    }

    static void visitChunksFromCenter(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, int centerChunkX, int centerChunkZ, BiConsumer<Integer, Integer> consumer) {
        int maximumRing = Math.max(
            Math.max(Math.abs(minChunkX - centerChunkX), Math.abs(maxChunkX - centerChunkX)),
            Math.max(Math.abs(minChunkZ - centerChunkZ), Math.abs(maxChunkZ - centerChunkZ))
        );
        for (int ring = 0; ring <= maximumRing; ring++) {
            int minX = Math.max(minChunkX, centerChunkX - ring);
            int maxX = Math.min(maxChunkX, centerChunkX + ring);
            int minZ = Math.max(minChunkZ, centerChunkZ - ring);
            int maxZ = Math.min(maxChunkZ, centerChunkZ + ring);
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) for (int chunkX = minX; chunkX <= maxX; chunkX++) {
                if (Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ)) == ring) consumer.accept(chunkX, chunkZ);
            }
        }
    }

    static Gateway legacyCandidate(long seed, int chunkX, int chunkZ) {
        var random = new LocalRandom(seed);
        long xSeed = random.nextLong() / 2L * 2L + 1L;
        long zSeed = random.nextLong() / 2L * 2L + 1L;
        return legacyCandidate(seed, chunkX, chunkZ, xSeed, zSeed, random);
    }

    private static Gateway legacyCandidate(long seed, int chunkX, int chunkZ, long xSeed, long zSeed, LocalRandom random) {
        random.setSeed((long) chunkX * xSeed + (long) chunkZ * zSeed ^ seed);
        if (random.nextInt(700) != 0) return null;
        return new Gateway((chunkX << 4) + random.nextInt(16), (chunkZ << 4) + random.nextInt(16), GenerationVersion.V1_12);
    }

    private static Gateway modernCandidate(long seed, int blockX, int blockZ, ChunkRandom random) {
        long populationSeed = random.setPopulationSeed(seed, blockX, blockZ);
        random.setDecoratorSeed(populationSeed, 0, 4);
        if (random.nextFloat() >= 1F / 700F) return null;
        return new Gateway(blockX + random.nextInt(16), blockZ + random.nextInt(16), GenerationVersion.V1_20_4);
    }

    static List<Integer> nearestWindow(List<Gateway> gateways, List<Integer> candidates, double playerX, double playerZ, int maximum) {
        List<Integer> window = new ArrayList<>(candidates);
        window.sort(Comparator.comparingDouble(index -> squared(gateways.get(index).x + .5, gateways.get(index).z + .5, playerX, playerZ)));
        return window.size() <= maximum ? window : new ArrayList<>(window.subList(0, maximum));
    }

    static List<Integer> route(List<Gateway> gateways, List<Integer> candidates, double playerX, double playerZ, PathAlgorithm algorithm) {
        return switch (algorithm) {
            case NEAREST_NEIGHBOR -> nearest(gateways, candidates, playerX, playerZ);
            case TSP -> tsp(gateways, candidates, playerX, playerZ);
            case SCAN_ORDER -> new ArrayList<>(candidates);
            case RANDOM -> { List<Integer> route = new ArrayList<>(candidates); Collections.shuffle(route); yield route; }
        };
    }

    private static List<Integer> nearest(List<Gateway> gateways, List<Integer> candidates, double x, double z) {
        List<Integer> remaining = new ArrayList<>(candidates);
        List<Integer> result = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            int best = 0;
            double distance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                Gateway gateway = gateways.get(remaining.get(i));
                double candidateDistance = squared(gateway.x + .5, gateway.z + .5, x, z);
                if (candidateDistance < distance) { distance = candidateDistance; best = i; }
            }
            int index = remaining.remove(best);
            result.add(index);
            x = gateways.get(index).x + .5;
            z = gateways.get(index).z + .5;
        }
        return result;
    }

    private static List<Integer> tsp(List<Gateway> gateways, List<Integer> candidates, double playerX, double playerZ) {
        if (candidates.size() <= 2) return new ArrayList<>(candidates);
        List<Integer> result = new ArrayList<>();
        int first = candidates.getFirst();
        double furthest = -1;
        for (int candidate : candidates) {
            Gateway gateway = gateways.get(candidate);
            double distance = squared(gateway.x + .5, gateway.z + .5, playerX, playerZ);
            if (distance > furthest) { furthest = distance; first = candidate; }
        }
        result.add(first);
        while (result.size() < candidates.size()) {
            int selected = -1;
            double selectedDistance = -1;
            for (int candidate : candidates) {
                if (result.contains(candidate)) continue;
                double nearest = Double.POSITIVE_INFINITY;
                for (int current : result) nearest = Math.min(nearest, distance(gateways.get(candidate), gateways.get(current)));
                if (nearest > selectedDistance) { selectedDistance = nearest; selected = candidate; }
            }
            int insertion = 0;
            double increase = distance(gateways.get(selected), gateways.get(result.getFirst()));
            for (int i = 1; i < result.size(); i++) {
                double candidateIncrease = distance(gateways.get(result.get(i - 1)), gateways.get(selected)) + distance(gateways.get(selected), gateways.get(result.get(i))) - distance(gateways.get(result.get(i - 1)), gateways.get(result.get(i)));
                if (candidateIncrease < increase) { increase = candidateIncrease; insertion = i; }
            }
            if (distance(gateways.get(result.getLast()), gateways.get(selected)) < increase) insertion = result.size();
            result.add(insertion, selected);
        }
        return result;
    }

    private void loadVisited() {
        boolean rewrite = loadVisited(visitPath(activeSeed), GenerationVersion.V1_20_4, true);
        rewrite |= loadVisited(legacyVisitPath(activeSeed), GenerationVersion.V1_12, false);
        if (rewrite) saveVisited();
    }

    private boolean loadVisited(Path visitedPath, GenerationVersion fallbackVersion, boolean acceptUnifiedRows) {
        if (!Files.exists(visitedPath)) return false;
        boolean rewrite = false;
        int before = visited.size();
        try {
            for (String line : Files.readAllLines(visitedPath, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] values = line.split(",", 3);
                if (acceptUnifiedRows && values.length == 3) {
                    visited.add(new Gateway(Integer.parseInt(values[1].trim()), Integer.parseInt(values[2].trim()), GenerationVersion.valueOf(values[0].trim())));
                } else if (values.length == 2) {
                    visited.add(new Gateway(Integer.parseInt(values[0].trim()), Integer.parseInt(values[1].trim()), fallbackVersion));
                    rewrite = true;
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            WaveXinAddon.LOG.warn("Could not read End gateway visit history.");
        }
        return rewrite && visited.size() > before;
    }

    private void saveVisited() {
        if (activeGenerationVersion == null) return;
        Path visitedPath = visitPath(activeSeed);
        StringBuilder output = new StringBuilder("# End Return Gateways v2\n");
        for (Gateway gateway : visited) output.append(gateway.version.name()).append(',').append(gateway.x).append(',').append(gateway.z).append('\n');
        try {
            Files.createDirectories(visitedPath.getParent());
            Files.writeString(visitedPath, output, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            WaveXinAddon.LOG.warn("Could not save End gateway visit history.");
        }
    }

    static boolean shouldAdvanceView(int centerX, int centerZ, int radiusChunks, int playerX, int playerZ) {
        long threshold = Math.max(0L, (long) radiusChunks * 16L - PREFETCH_DISTANCE_BLOCKS);
        long dx = (long) playerX - centerX;
        long dz = (long) playerZ - centerZ;
        return dx * dx + dz * dz >= threshold * threshold;
    }

    static boolean tileIntersectsCircle(int centerX, int centerZ, int radiusChunks, int tileX, int tileZ) {
        long radius = (long) radiusChunks * 16L;
        long minX = (long) tileX * TILE_SIZE_BLOCKS;
        long minZ = (long) tileZ * TILE_SIZE_BLOCKS;
        long maxX = minX + TILE_SIZE_BLOCKS - 1L;
        long maxZ = minZ + TILE_SIZE_BLOCKS - 1L;
        long closestX = Math.max(minX, Math.min(maxX, centerX));
        long closestZ = Math.max(minZ, Math.min(maxZ, centerZ));
        long dx = (long) centerX - closestX;
        long dz = (long) centerZ - closestZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    static long countTilesInCircle(int centerX, int centerZ, int radiusChunks) {
        long radius = (long) radiusChunks * 16L;
        int minTileZ = (int) Math.floorDiv((long) centerZ - radius, TILE_SIZE_BLOCKS);
        int maxTileZ = (int) Math.floorDiv((long) centerZ + radius, TILE_SIZE_BLOCKS);
        long count = 0;
        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            long minZ = (long) tileZ * TILE_SIZE_BLOCKS;
            long maxZ = minZ + TILE_SIZE_BLOCKS - 1L;
            long closestZ = Math.max(minZ, Math.min(maxZ, centerZ));
            long dz = (long) centerZ - closestZ;
            long remaining = radius * radius - dz * dz;
            if (remaining < 0) continue;
            long extent = (long) Math.floor(Math.sqrt(remaining));
            int minTileX = (int) Math.floorDiv((long) centerX - extent, TILE_SIZE_BLOCKS);
            int maxTileX = (int) Math.floorDiv((long) centerX + extent, TILE_SIZE_BLOCKS);
            count += (long) maxTileX - minTileX + 1L;
        }
        return count;
    }

    static Path visitPath(long seed) { return WaveXinDataPaths.DIRECTORY.resolve("end-gateways").resolve(visitFilename(seed)); }
    static Path legacyVisitPath(long seed) { return WaveXinDataPaths.DIRECTORY.resolve("end-gateways").resolve(seed + "-1.12.dat"); }
    static Path visitPath(long seed, GenerationVersion version) { return visitPath(seed); }
    static String visitFilename(long seed) { return seed + ".dat"; }
    static String visitFilename(long seed, GenerationVersion version) { return visitFilename(seed); }
    static List<GenerationVersion> scanVersions(GenerationVersion version) { return version == GenerationVersion.BOTH ? List.of(GenerationVersion.V1_12, GenerationVersion.V1_20_4) : List.of(version); }
    static boolean hasSurface(int topY, int bottomY) { return topY > bottomY; }
    static long pack(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }
    static boolean shouldSelectTarget(boolean ready, int target, boolean candidateVisited) { return !ready || target < 0 && !candidateVisited; }
    static long parsedSeed(String seed) { try { return Long.parseLong(seed); } catch (NumberFormatException ignored) { return seed.hashCode(); } }
    private static double squared(double x1, double z1, double x2, double z2) { double dx = x1 - x2; double dz = z1 - z2; return dx * dx + dz * dz; }
    private static long squaredDistance(int x1, int z1, int x2, int z2) { long dx = (long) x1 - x2; long dz = (long) z1 - z2; return dx * dx + dz * dz; }
    private static double distance(Gateway first, Gateway second) { return Math.sqrt(squared(first.x, first.z, second.x, second.z)); }
    private static ColorSetting.Builder color(String name, int red, int green, int blue, int alpha) { return new ColorSetting.Builder().name(name).defaultValue(new SettingColor(red, green, blue, alpha)); }

    private static final class ScanContext {
        final NoiseConfig noiseConfig;
        final NoiseChunkGenerator generator;
        final BiomeAccess biomeAccess;
        final HeightLimitView heightLimit = HeightLimitView.create(0, 256);
        final ChunkRandom modernRandom = new ChunkRandom(new Xoroshiro128PlusPlusRandom(0L));
        final LocalRandom legacyRandom;
        final long legacyX;
        final long legacyZ;

        ScanContext(long seed) {
            var registries = BuiltinRegistries.createWrapperLookup();
            var biomeSource = TheEndBiomeSource.createVanilla(registries.getOrThrow(RegistryKeys.BIOME));
            var settings = registries.getOrThrow(RegistryKeys.CHUNK_GENERATOR_SETTINGS).getOrThrow(ChunkGeneratorSettings.END);
            noiseConfig = NoiseConfig.create(registries, ChunkGeneratorSettings.END, seed);
            generator = new NoiseChunkGenerator(biomeSource, settings);
            var sampler = noiseConfig.getMultiNoiseSampler();
            biomeAccess = new BiomeAccess((x, y, z) -> biomeSource.getBiome(x, y, z, sampler), BiomeAccess.hashSeed(seed));
            legacyRandom = new LocalRandom(seed);
            legacyX = legacyRandom.nextLong() / 2L * 2L + 1L;
            legacyZ = legacyRandom.nextLong() / 2L * 2L + 1L;
        }
    }

    static final class TileScheduler {
        private final int centerX;
        private final int centerZ;
        private final int centerTileX;
        private final int centerTileZ;
        private final int maximumRing;
        private final int radiusChunks;
        private int ring;
        private int position;
        private List<Tile> currentRing = List.of();

        TileScheduler(int centerX, int centerZ, int radiusChunks) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            centerTileX = Math.floorDiv(centerX, TILE_SIZE_BLOCKS);
            centerTileZ = Math.floorDiv(centerZ, TILE_SIZE_BLOCKS);
            maximumRing = radiusChunks / TILE_SIZE_CHUNKS + 2;
            this.radiusChunks = radiusChunks;
        }

        Tile next(LongPredicate cached) {
            while (ring <= maximumRing) {
                if (position >= currentRing.size()) {
                    currentRing = createRing(ring++);
                    position = 0;
                    if (currentRing.isEmpty()) continue;
                }
                Tile tile = currentRing.get(position++);
                if (!cached.test(pack(tile.x, tile.z))) return tile;
            }
            return null;
        }

        private List<Tile> createRing(int radius) {
            List<Tile> tasks = new ArrayList<>(radius == 0 ? 1 : radius * 8);
            if (radius == 0) {
                tasks.add(new Tile(centerTileX, centerTileZ));
            } else {
                for (int x = centerTileX - radius; x <= centerTileX + radius; x++) {
                    tasks.add(new Tile(x, centerTileZ - radius));
                    tasks.add(new Tile(x, centerTileZ + radius));
                }
                for (int z = centerTileZ - radius + 1; z < centerTileZ + radius; z++) {
                    tasks.add(new Tile(centerTileX - radius, z));
                    tasks.add(new Tile(centerTileX + radius, z));
                }
            }
            tasks.removeIf(tile -> !tileIntersectsCircle(centerX, centerZ, radiusChunks, tile.x, tile.z));
            tasks.sort(Comparator.comparingLong(this::distanceSquared));
            return tasks;
        }

        private long distanceSquared(Tile tile) {
            long dx = (long) tile.x * TILE_SIZE_BLOCKS + TILE_SIZE_BLOCKS / 2L - centerX;
            long dz = (long) tile.z * TILE_SIZE_BLOCKS + TILE_SIZE_BLOCKS / 2L - centerZ;
            return dx * dx + dz * dz;
        }
    }

    enum ScanShape { CIRCLE, SQUARE }
    enum GenerationVersion { V1_12, V1_20_4, BOTH }
    enum PathAlgorithm { NEAREST_NEIGHBOR, TSP, SCAN_ORDER, RANDOM }
    private enum ScanState { SCANNING, NEXT_AREA, COMPLETE, FAILED }
    record Gateway(int x, int z, GenerationVersion version) {
        Gateway(int x, int z) { this(x, z, GenerationVersion.V1_20_4); }
    }
    record Tile(int x, int z) {}
    private record TileResult(Tile tile, List<Gateway> gateways) {}
}
