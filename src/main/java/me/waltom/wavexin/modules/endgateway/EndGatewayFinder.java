package me.waltom.wavexin.modules.endgateway;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinDataPaths;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.modules.basefinder.ContainerRecorder;
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
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.TheEndBiomeSource;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Locally predicts and visits vanilla End return gateways for the configured seed. */
public final class EndGatewayFinder extends WaveXinModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgContainerRecording = settings.createGroup("Container Recording");
    private final Setting<String> worldSeed = sgGeneral.add(new StringSetting.Builder().name("World Seed")
        .description("World seed used to predict End return gateways locally.").defaultValue("3763250021837776656").build());
    private final Setting<ScanShape> scanShape = sgGeneral.add(new EnumSetting.Builder<ScanShape>().name("Scan Shape")
        .description("Shape used to filter predicted gateway positions around the player.").defaultValue(ScanShape.CIRCLE).build());
    private final Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder().name("Search Radius")
        .description("Circular search radius in blocks.").defaultValue(2000).min(128).max(50000).sliderMax(50000)
        .visible(() -> scanShape.get() == ScanShape.CIRCLE).build());
    private final Setting<Integer> squareSearchRadius = sgGeneral.add(new IntSetting.Builder().name("Square Search Radius")
        .description("Square half-width in blocks.").defaultValue(2000).min(128).max(35000).sliderMax(35000)
        .visible(() -> scanShape.get() == ScanShape.SQUARE).build());
    private final Setting<Double> arrivalDistance = sgGeneral.add(new DoubleSetting.Builder().name("Arrival Distance")
        .defaultValue(16.0).min(1.0).max(128.0).build());
    private final Setting<Boolean> autoLook = sgGeneral.add(new BoolSetting.Builder().name("Auto Look").defaultValue(true).build());
    private final Setting<Boolean> stayAtGateway = sgGeneral.add(new BoolSetting.Builder().name("Stay At Gateway")
        .description("Stay at each gateway after arrival.").defaultValue(false).build());
    private final Setting<Integer> stayDuration = sgGeneral.add(new IntSetting.Builder().name("Stay Duration")
        .description("Seconds to stay at each gateway.").defaultValue(5).min(1).max(300).sliderMax(60).visible(stayAtGateway::get).build());
    private final Setting<PathAlgorithm> pathAlgorithm = sgGeneral.add(new EnumSetting.Builder<PathAlgorithm>().name("Path Algorithm")
        .description("Order used to visit unvisited gateways.").defaultValue(PathAlgorithm.NEAREST_NEIGHBOR).build());
    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder().name("Render Distance")
        .defaultValue(1024).min(64).max(1024).sliderMax(1024).build());
    private final Setting<SettingColor> targetColor = sgRender.add(color("Target Color", 255, 0, 0, 60).build());
    private final Setting<SettingColor> targetLine = sgRender.add(color("Target Line", 255, 0, 0, 220).build());
    private final Setting<SettingColor> gatewayColor = sgRender.add(color("Gateway Color", 255, 255, 255, 40).build());
    private final Setting<SettingColor> gatewayLine = sgRender.add(color("Gateway Line", 255, 255, 255, 180).build());
    private final Setting<SettingColor> visitedColor = sgRender.add(color("Visited Color", 0, 255, 0, 30).build());
    private final Setting<SettingColor> visitedLine = sgRender.add(color("Visited Line", 0, 255, 0, 80).build());
    private final Setting<ShapeMode> renderMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("Render Mode").defaultValue(ShapeMode.Both).build());
    private final ContainerRecorder containerRecorder = new ContainerRecorder(this, sgContainerRecording, () -> 4);

    private final List<Gateway> gateways = new ArrayList<>();
    private final Set<Long> visited = new HashSet<>();
    private List<Integer> route;
    private int routePosition = -1;
    private int target = -1;
    private int scanGeneration;
    private long stayUntil;
    private boolean ready;
    private boolean forcingForward;
    private Path visitedPath;

    public EndGatewayFinder() {
        super(WaveXinAddon.CATEGORY, "end-gateway-finder", "End Return Gateway Finder");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) return;
        if (!mc.world.getRegistryKey().equals(World.END)) {
            error("End Gateway Finder can only run in The End.");
            toggle();
            return;
        }
        ready = false;
        gateways.clear();
        visited.clear();
        route = null;
        routePosition = -1;
        target = -1;
        stayUntil = 0;
        scanGeneration++;
        containerRecorder.onActivate();

        long seed = parsedSeed(worldSeed.get());
        visitedPath = visitPath(seed);
        loadVisited();
        startScan(seed, scanGeneration);
    }

    @Override
    public void onDeactivate() {
        scanGeneration++;
        saveVisited();
        releaseForward();
        stayUntil = 0;
        ready = false;
        containerRecorder.onDeactivate();
    }

    private void startScan(long seed, int generation) {
        int centerX = (int) mc.player.getX();
        int centerZ = (int) mc.player.getZ();
        int radius = scanShape.get() == ScanShape.SQUARE ? squareSearchRadius.get() : searchRadius.get();
        int queryRadius = scanShape.get() == ScanShape.SQUARE ? (int) Math.ceil(radius * Math.sqrt(2.0)) : radius;
        ScanShape shape = scanShape.get();
        Thread thread = new Thread(() -> {
            try {
                List<Gateway> result = scan(seed, centerX, centerZ, radius, queryRadius, shape);
                mc.execute(() -> completeScan(generation, result, null));
            } catch (RuntimeException exception) {
                mc.execute(() -> completeScan(generation, List.of(), exception));
            }
        }, "wavexin-end-gateway-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void completeScan(int generation, List<Gateway> result, RuntimeException failure) {
        if (!isActive() || generation != scanGeneration) return;
        if (failure != null) {
            error("Gateway scan failed: %s", failure.getMessage());
            toggle();
            return;
        }
        gateways.clear();
        gateways.addAll(result);
        if (gateways.isEmpty()) {
            error("No gateways found in range. Try larger radius.");
            toggle();
            return;
        }
        long remaining = gateways.stream().filter(gateway -> !visited.contains(pack(gateway.x, gateway.z))).count();
        if (remaining == 0) {
            info("All gateways visited!");
            toggle();
            return;
        }
        info("Found %d return gateways, %d remaining", gateways.size(), remaining);
        ready = true;
        nextTarget();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!ready || mc.player == null || mc.world == null) return;
        containerRecorder.scanNear(mc.player.getChunkPos());
        if (stayUntil > 0) {
            releaseForward();
            if (System.currentTimeMillis() >= stayUntil) {
                stayUntil = 0;
                nextTarget();
            }
            return;
        }
        if (target >= 0 && target < gateways.size()) {
            Gateway gateway = gateways.get(target);
            double dx = gateway.x + 0.5 - mc.player.getX();
            double dz = gateway.z + 0.5 - mc.player.getZ();
            if (dx * dx + dz * dz <= arrivalDistance.get() * arrivalDistance.get()) {
                visited.add(pack(gateway.x, gateway.z));
                saveVisited();
                releaseForward();
                if (stayAtGateway.get()) {
                    stayUntil = System.currentTimeMillis() + stayDuration.get() * 1000L;
                    info("Arrived at #%d/%d, staying for %d seconds", routePosition + 1, route.size(), stayDuration.get());
                    return;
                }
                nextTarget();
            }
        }
        moveToTarget();
    }

    private void nextTarget() {
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < gateways.size(); i++) if (!visited.contains(pack(gateways.get(i).x, gateways.get(i).z))) remaining.add(i);
        route = route(gateways, remaining, mc.player == null ? 0 : mc.player.getX(), mc.player == null ? 0 : mc.player.getZ(), pathAlgorithm.get());
        routePosition = 0;
        if (route.isEmpty()) {
            target = -1;
            info("All gateways visited!");
            toggle();
            return;
        }
        target = route.getFirst();
        Gateway gateway = gateways.get(target);
        info("-> #1/%d (%d, %d)", route.size(), gateway.x, gateway.z);
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

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!ready || mc.player == null) return;
        double maximum = (double) renderDistance.get() * renderDistance.get();
        for (int i = 0; i < gateways.size(); i++) {
            Gateway gateway = gateways.get(i);
            double dx = gateway.x - mc.player.getX();
            double dz = gateway.z - mc.player.getZ();
            if (dx * dx + dz * dz > maximum) continue;
            boolean isTarget = i == target;
            boolean isVisited = visited.contains(pack(gateway.x, gateway.z));
            SettingColor side = isTarget ? targetColor.get() : isVisited ? visitedColor.get() : gatewayColor.get();
            SettingColor line = isTarget ? targetLine.get() : isVisited ? visitedLine.get() : gatewayLine.get();
            if (side.a <= 5 && line.a <= 5) continue;
            event.renderer.box(gateway.x, 0, gateway.z, gateway.x + 1, 384, gateway.z + 1, side, line, renderMode.get(), 0);
        }
    }

    static List<Gateway> scan(long seed, int centerX, int centerZ, int radius, int queryRadius, ScanShape shape) {
        var registries = BuiltinRegistries.createWrapperLookup();
        var biomeSource = TheEndBiomeSource.createVanilla(registries.getOrThrow(RegistryKeys.BIOME));
        var settings = registries.getOrThrow(RegistryKeys.CHUNK_GENERATOR_SETTINGS).getOrThrow(ChunkGeneratorSettings.END);
        var noiseConfig = NoiseConfig.create(registries, ChunkGeneratorSettings.END, seed);
        var generator = new NoiseChunkGenerator(biomeSource, settings);
        var sampler = noiseConfig.getMultiNoiseSampler();
        var biomeAccess = new BiomeAccess((x, y, z) -> biomeSource.getBiome(x, y, z, sampler), BiomeAccess.hashSeed(seed));
        var heightLimit = HeightLimitView.create(0, 256);
        var random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(0L));
        List<Gateway> result = new ArrayList<>();
        int minChunkX = (centerX - queryRadius) >> 4;
        int maxChunkX = (centerX + queryRadius) >> 4;
        int minChunkZ = (centerZ - queryRadius) >> 4;
        int maxChunkZ = (centerZ + queryRadius) >> 4;
        double queryRadiusSquared = (double) queryRadius * queryRadius;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            int blockX = chunkX << 4;
            int blockZ = chunkZ << 4;
            long populationSeed = random.setPopulationSeed(seed, blockX, blockZ);
            random.setDecoratorSeed(populationSeed, 0, 4);
            if (random.nextFloat() >= 1F / 700F) continue;
            int x = blockX + random.nextInt(16);
            int z = blockZ + random.nextInt(16);
            double dx = x - centerX;
            double dz = z - centerZ;
            if (dx * dx + dz * dz > queryRadiusSquared || shape == ScanShape.SQUARE && (Math.abs(dx) > radius || Math.abs(dz) > radius)) continue;
            int topY = generator.getHeight(x, z, Heightmap.Type.MOTION_BLOCKING, heightLimit, noiseConfig);
            if (!hasSurface(topY, heightLimit.getBottomY())) continue;
            int y = topY + random.nextBetween(3, 9);
            if (!biomeAccess.getBiome(new BlockPos(x, y, z)).matchesKey(BiomeKeys.END_HIGHLANDS)) continue;
            result.add(new Gateway(x, z));
        }
        return result;
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
        if (visitedPath == null || !Files.exists(visitedPath)) return;
        try {
            for (String line : Files.readAllLines(visitedPath, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] values = line.split(",", 2);
                if (values.length == 2) visited.add(pack(Integer.parseInt(values[0].trim()), Integer.parseInt(values[1].trim())));
            }
        } catch (IOException | NumberFormatException ignored) {
            WaveXinAddon.LOG.warn("Could not read End gateway visit history.");
        }
    }

    private void saveVisited() {
        if (visitedPath == null) return;
        StringBuilder output = new StringBuilder("# End Return Gateways\n");
        for (long gateway : visited) output.append((int) (gateway >> 32)).append(',').append((int) gateway).append('\n');
        try {
            Files.createDirectories(visitedPath.getParent());
            Files.writeString(visitedPath, output, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            WaveXinAddon.LOG.warn("Could not save End gateway visit history.");
        }
    }

    static Path visitPath(long seed) { return WaveXinDataPaths.DIRECTORY.resolve("end-gateways").resolve(visitFilename(seed)); }
    static String visitFilename(long seed) { return seed + ".dat"; }
    static boolean hasSurface(int topY, int bottomY) { return topY > bottomY; }
    static long pack(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }
    static long parsedSeed(String seed) { try { return Long.parseLong(seed); } catch (NumberFormatException ignored) { return seed.hashCode(); } }
    private static double squared(double x1, double z1, double x2, double z2) { double dx = x1 - x2; double dz = z1 - z2; return dx * dx + dz * dz; }
    private static double distance(Gateway first, Gateway second) { return Math.sqrt(squared(first.x, first.z, second.x, second.z)); }
    private static ColorSetting.Builder color(String name, int red, int green, int blue, int alpha) { return new ColorSetting.Builder().name(name).defaultValue(new SettingColor(red, green, blue, alpha)); }

    enum ScanShape { CIRCLE, SQUARE }
    enum PathAlgorithm { NEAREST_NEIGHBOR, TSP, SCAN_ORDER, RANDOM }
    record Gateway(int x, int z) {}
}
