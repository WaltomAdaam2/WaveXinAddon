package me.waltom.wavexin;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.RainbowColors;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class BaseFinderXin extends WaveXinModule {
    private static final Path CONTAINER_RECORD_PATH = MeteorClient.FOLDER.toPath().resolve("base-finder-xin").resolve("container-records.txt");
    private static final DateTimeFormatter RECORD_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public enum XaeroWaypointColor {
        RANDOM("Random", -1, 170, 170, 170),
        RED("Red", 0, 255, 85, 85),
        ORANGE("Orange", 1, 255, 170, 0),
        YELLOW("Yellow", 2, 255, 255, 85),
        LIME("Lime", 3, 85, 255, 85),
        GREEN("Green", 4, 0, 170, 0),
        CYAN("Cyan", 5, 0, 170, 170),
        LIGHT_BLUE("Light Blue", 6, 85, 255, 255),
        BLUE("Blue", 7, 85, 85, 255),
        PURPLE("Purple", 8, 170, 0, 170),
        MAGENTA("Magenta", 9, 255, 85, 255),
        PINK("Pink", 10, 255, 85, 170),
        WHITE("White", 11, 255, 255, 255),
        LIGHT_GRAY("Light Gray", 12, 170, 170, 170),
        GRAY("Gray", 13, 85, 85, 85),
        BROWN("Brown", 14, 170, 85, 0),
        BLACK("Black", 15, 0, 0, 0);

        private final String title;
        private final int colorId;
        private final Color displayColor;

        XaeroWaypointColor(String title, int colorId, int red, int green, int blue) {
            this.title = title;
            this.colorId = colorId;
            this.displayColor = new Color(red, green, blue);
        }

        public Color displayColor() {
            return this == RANDOM ? RainbowColors.GLOBAL : displayColor;
        }

        @Override
        public String toString() {
            return title;
        }
    }
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgRestart = settings.createGroup("Restart");
    private final SettingGroup sgXaeroWaypoints = settings.createGroup("Xaero Waypoints");
    private ChunkPos originChunk;
    private ChunkPos targetChunk;
    private int currentCircle;
    private SweepRoute currentPath;
    private boolean isBack;
    private int turnDelayTimer;
    private float targetYaw;

    private boolean forcingForward;

    // 存储所有目标区块
    private final Set<ChunkPos> targetChunks = Collections.synchronizedSet(new HashSet<>());
    private final Set<ChunkPos> visitedChunks = Collections.synchronizedSet(new HashSet<>());
    private final Set<ChunkPos> currentPathChunks = Collections.synchronizedSet(new HashSet<>());

    private final Set<Long> recordedContainerChunks = new HashSet<>();
    private final List<BlockPos> createdWaypointPositions = new ArrayList<>();
    private int nextWaypointNumber = 1;

    // 设置玩家当前世界的加载的区块范围
    public final Setting<Integer> chunkLoadRadius = sgGeneral.add(new IntSetting.Builder()
            .name("区块加载范围")
            .description("当前世界加载的区块范围")
            .defaultValue(5)
            .min(2)
            .max(10)
            .sliderMin(2)
            .sliderMax(10)
            .build());

    // 设置搜索圈数
    public final Setting<Integer> circleLimit = sgGeneral.add(new IntSetting.Builder()
            .name("搜索圈数限制")
            .description("到达搜索圈数限制停止")
            .defaultValue(50)
            .min(2)
            .max(Integer.MAX_VALUE)
            .sliderMin(2)
            .sliderMax(100)
            .build());

    // 移动速度设置
    private final Setting<Double> moveSpeed = sgGeneral.add(new DoubleSetting.Builder()
            .name("移动速度")
            .description("移动速度")
            .defaultValue(3.0)
            .min(0.01)
            .max(3.0)
            .sliderMin(0.01)
            .sliderMax(3.0)
            .build());

    // 转向延迟tick
    private final Setting<Integer> turnDelay = sgGeneral.add(new IntSetting.Builder()
            .name("转向延迟")
            .description("转向的延迟确保区块加载")
            .defaultValue(40)
            .min(1)
            .max(100)
            .sliderMin(1)
            .sliderMax(100)
            .build());

    // 修复卡顿
    private final Setting<Boolean> waitChunkLoad = sgGeneral.add(new BoolSetting.Builder()
            .name("等待区块加载")
            .description("等待区块加载确保搜索到的区块加载")
            .defaultValue(true)
            .build());

    // 是否从上次开始
    private final Setting<Integer> containerThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("Container Threshold")
        .description("Records the current chunk when it contains at least this many selected containers.")
        .defaultValue(10)
        .min(2)
        .max(200)
        .sliderRange(2, 200)
        .build()
    );

    private final Setting<List<BlockEntityType<?>>> containerBlocks = sgGeneral.add(new StorageBlockListSetting.Builder()
        .name("Container Blocks")
        .description("Container block entity types to count, matching Meteor Storage ESP defaults.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    private final Setting<Boolean> xaeroWaypoints = sgXaeroWaypoints.add(new BoolSetting.Builder()
        .name("Xaero Waypoints")
        .description("Creates a Xaero waypoint when a container chunk is recorded. Requires Xaero's Minimap at runtime.")
        .defaultValue(false)
        .build()
    );

    private final Setting<XaeroWaypointColor> xaeroWaypointColor = sgXaeroWaypoints.add(new XaeroWaypointColorSetting.Builder()
        .name("Waypoint Color").description("Xaero waypoint color, or a random supported color for each waypoint.").defaultValue(XaeroWaypointColor.RANDOM).visible(xaeroWaypoints::get).build()
    );
    private final Setting<Integer> waypointLimitRadius = sgXaeroWaypoints.add(new IntSetting.Builder()
        .name("Area Radius").description("Chunk radius used to group nearby waypoints into one base area.").defaultValue(8).range(1, 64).sliderRange(1, 32).visible(xaeroWaypoints::get).build()
    );
    private final Setting<Integer> maximumWaypointsPerArea = sgXaeroWaypoints.add(new IntSetting.Builder()
        .name("Waypoints per Area").description("Maximum waypoints created within one base area during the current scan.").defaultValue(3).range(1, 100).sliderRange(1, 20).visible(xaeroWaypoints::get).build()
    );
private final Setting<String> xaeroWaypointPrefix = sgXaeroWaypoints.add(new StringSetting.Builder()
        .name("Waypoint Prefix")
        .description("Text before the waypoint name.")
        .defaultValue("Base ")
        .visible(xaeroWaypoints::get)
        .build()
    );

    private final Setting<String> xaeroWaypointSuffix = sgXaeroWaypoints.add(new StringSetting.Builder()
        .name("Waypoint Suffix")
        .description("Text after the waypoint name.")
        .defaultValue("")
        .visible(xaeroWaypoints::get)
        .build()
    );


    private final Setting<Boolean> lastBegin = sgRestart.add(new BoolSetting.Builder()
            .name("从上次开始")
            .description("是否使用进度")
            .defaultValue(false)
            .build());

    // 上次的圈数
    private final Setting<Integer> lastCircle = sgRestart.add(new IntSetting.Builder()
            .name("上次圈数")
            .description("上次圈数")
            .visible(lastBegin::get)
            .defaultValue(0)
            .min(0)
            .max(1000)
            .sliderMin(0)
            .sliderMax(100)
            .build());

    // 上次暂停的区块X
    private final Setting<Integer> lastChunkX = sgRestart.add(new IntSetting.Builder()
            .name("上次暂停的区块X")
            .description("上次暂停的区块X")
            .visible(lastBegin::get)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    // 上次暂停的区块Z
    private final Setting<Integer> lastChunkZ = sgRestart.add(new IntSetting.Builder()
            .name("上次暂停的区块Z")
            .description("上次暂停的区块Z")
            .visible(lastBegin::get)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    // 上次到哪个方向了
    private final Setting<SweepRoute> lastPath = sgRestart.add(new EnumSetting.Builder<SweepRoute>()
            .name("上次的路径点")
            .description("上次的路径点")
            .visible(lastBegin::get)
            .defaultValue(SweepRoute.NEXT_CIRCLE)
            .build());

    // 上次开始的原点区块X
    private final Setting<Integer> lastOriginX = sgRestart.add(new IntSetting.Builder()
            .name("上次开始的原点区块X")
            .description("上次开始的原点区块X")
            .visible(lastBegin::get)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    // 上次开始的原点区块Z
    private final Setting<Integer> lastOriginZ = sgRestart.add(new IntSetting.Builder()
            .name("上次开始的原点区块Z")
            .description("上次开始的原点区块Z")
            .visible(lastBegin::get)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    // 渲染距离设置
    public final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
            .name("渲染距离")
            .description("渲染距离（区块）")
            .defaultValue(32)
            .min(6)
            .max(128)
            .sliderMin(6)
            .sliderMax(128)
            .build());

    // 渲染高度设置
    public final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
            .name("渲染高度")
            .description("渲染高度")
            .defaultValue(0)
            .min(-64)
            .max(320)
            .sliderMin(-64)
            .sliderMax(320)
            .build());

    // 形状模式设置
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("渲染模式")
            .description("渲染形状模式")
            .defaultValue(ShapeMode.Both)
            .build());

    // 预加载圈数设置
    private final Setting<Integer> preloadCircles = sgRender.add(new IntSetting.Builder()
            .name("预加载圈数")
            .description("预加载的圈数")
            .defaultValue(3)
            .min(1)
            .max(10)
            .sliderMin(1)
            .sliderMax(10)
            .build());

    // 目标区块颜色设置
    private final Setting<SettingColor> targetChunksSideColor = sgRender.add(new ColorSetting.Builder()
            .name("目标区块面颜色")
            .description("目标区块面颜色")
            .defaultValue(new SettingColor(255, 0, 0, 95))
            .visible(() -> (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
            .build());

    private final Setting<SettingColor> targetChunksLineColor = sgRender.add(new ColorSetting.Builder()
            .name("目标区块线颜色")
            .description("目标区块线条颜色")
            .defaultValue(new SettingColor(255, 0, 0, 205))
            .visible(() -> (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both))
            .build());

    // 已访问区块颜色设置
    private final Setting<SettingColor> visitedChunksSideColor = sgRender.add(new ColorSetting.Builder()
            .name("已访问区块面颜色")
            .description("已访问区块面颜色")
            .defaultValue(new SettingColor(0, 255, 0, 40))
            .visible(() -> (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
            .build());

    private final Setting<SettingColor> visitedChunksLineColor = sgRender.add(new ColorSetting.Builder()
            .name("已访问区块线颜色")
            .description("已访问区块线颜色")
            .defaultValue(new SettingColor(0, 255, 0, 80))
            .visible(() -> (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both))
            .build());

    // 当前路径区块颜色设置
    private final Setting<SettingColor> currentPathSideColor = sgRender.add(new ColorSetting.Builder()
            .name("当前路径区块面颜色")
            .description("当前路径区块面颜色")
            .defaultValue(new SettingColor(255, 255, 0, 60))
            .visible(() -> (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
            .build());

    private final Setting<SettingColor> currentPathLineColor = sgRender.add(new ColorSetting.Builder()
            .name("当前路径区块线颜色")
            .description("当前路径区块线颜色")
            .defaultValue(new SettingColor(255, 255, 0, 100))
            .visible(() -> (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both))
            .build());

    public BaseFinderXin() {
        super(WaveXinAddon.CATEGORY, "base-finder", "Outward map scanner with chunk-loading pauses.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null)
            return;
        recordedContainerChunks.clear();
        createdWaypointPositions.clear();
        nextWaypointNumber = 1;
        validateXaeroWaypointSetting();

        // 检查是否从上次开始,加载参数
        if (lastBegin.get()) {
            // 从上次开始,加载参数
            currentCircle = lastCircle.get();
            currentPath = lastPath.get();
            originChunk = new ChunkPos(lastOriginX.get(), lastOriginZ.get());
        } else {
            // 从当前位置开始
            currentCircle = 0;
            currentPath = SweepRoute.NEXT_CIRCLE;
            originChunk = mc.player.getChunkPos();
        }

        // 清空之前的区块数据
        targetChunks.clear();
        visitedChunks.clear();
        currentPathChunks.clear();

        // 预先计算目标区块
        primeScanTargets();

        // 如果设置了从上次开始，设置目标区块 targetChunk
        isBack = !lastBegin.get();
        if (!isBack) {
            targetChunk = new ChunkPos(lastChunkX.get(), lastChunkZ.get());
        }
    }

    // 预先计算目标区块的方法
    private void primeScanTargets() {
        targetChunks.clear();

        // 预加载当前圈数+预加载圈数的区块
        int maxPreloadCircle = Math.min(currentCircle + preloadCircles.get(), circleLimit.get());
        for (int circle = currentCircle; circle <= maxPreloadCircle; circle++) {
            appendRingTargets(circle);
        }
    }

    // 添加指定圈数的所有区块
    private void appendRingTargets(int circle) {
        int radius = chunkLoadRadius.get() * circle * 2;

        // 从中心到正左
        ChunkPos centerLeft = new ChunkPos(originChunk.x - radius, originChunk.z);
        targetChunks.add(centerLeft);

        // 正左到左上
        ChunkPos upLeft = new ChunkPos(originChunk.x - radius, originChunk.z - radius);
        appendPathTargets(centerLeft, upLeft);

        // 左上到右上
        ChunkPos upRight = new ChunkPos(originChunk.x + radius, originChunk.z - radius);
        appendPathTargets(upLeft, upRight);

        // 右上到右下
        ChunkPos downRight = new ChunkPos(originChunk.x + radius, originChunk.z + radius);
        appendPathTargets(upRight, downRight);

        // 右下到左下
        ChunkPos downLeft = new ChunkPos(originChunk.x - radius, originChunk.z + radius);
        appendPathTargets(downRight, downLeft);

        // 左下到正左（完成一圈）
        appendPathTargets(downLeft, centerLeft);
    }

    // 添加两点之间路径上的所有区块
    private void appendPathTargets(ChunkPos from, ChunkPos to) {
        int deltaX = to.x - from.x;
        int deltaZ = to.z - from.z;

        int steps = Math.max(Math.abs(deltaX), Math.abs(deltaZ));

        // 如果起点和终点相同，直接添加该区块
        if (steps == 0) {
            targetChunks.add(from);
            return;
        }

        for (int i = 0; i <= steps; i++) {
            int x = from.x + (deltaX * i) / steps;
            int z = from.z + (deltaZ * i) / steps;
            targetChunks.add(new ChunkPos(x, z));
        }
    }

    // 更新当前路径区块
    private void refreshActivePathChunks() {
        currentPathChunks.clear();

        if (originChunk == null || currentPath == null)
            return;

        int radius = chunkLoadRadius.get() * currentCircle * 2;

        switch (currentPath) {
            case CENTER_TO_LEFT -> {
                ChunkPos from = originChunk;
                ChunkPos to = new ChunkPos(originChunk.x - radius, originChunk.z);
                appendActivePathChunks(from, to);
            }
            case CENTER_LEFT_TO_UP_LEFT -> {
                ChunkPos from = new ChunkPos(originChunk.x - radius, originChunk.z);
                ChunkPos to = new ChunkPos(originChunk.x - radius, originChunk.z - radius);
                appendActivePathChunks(from, to);
            }
            case UP_LEFT_TO_UP_RIGHT -> {
                ChunkPos from = new ChunkPos(originChunk.x - radius, originChunk.z - radius);
                ChunkPos to = new ChunkPos(originChunk.x + radius, originChunk.z - radius);
                appendActivePathChunks(from, to);
            }
            case UP_RIGHT_TO_DOWN_RIGHT -> {
                ChunkPos from = new ChunkPos(originChunk.x + radius, originChunk.z - radius);
                ChunkPos to = new ChunkPos(originChunk.x + radius, originChunk.z + radius);
                appendActivePathChunks(from, to);
            }
            case DOWN_RIGHT_TO_DOWN_LEFT -> {
                ChunkPos from = new ChunkPos(originChunk.x + radius, originChunk.z + radius);
                ChunkPos to = new ChunkPos(originChunk.x - radius, originChunk.z + radius);
                appendActivePathChunks(from, to);
            }
            case DOWN_LEFT_TO_LEFT -> {
                ChunkPos from = new ChunkPos(originChunk.x - radius, originChunk.z + radius);
                ChunkPos to = new ChunkPos(originChunk.x - radius, originChunk.z);
                appendActivePathChunks(from, to);
            }
        }
    }

    // 添加当前路径区块
    private void appendActivePathChunks(ChunkPos from, ChunkPos to) {
        int deltaX = to.x - from.x;
        int deltaZ = to.z - from.z;

        int steps = Math.max(Math.abs(deltaX), Math.abs(deltaZ));

        for (int i = 0; i <= steps; i++) {
            int x = from.x + (deltaX * i) / steps;
            int z = from.z + (deltaZ * i) / steps;
            currentPathChunks.add(new ChunkPos(x, z));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            setScanForwardKey(false);
            return;
        }

        refreshActivePathChunks();

        ChunkPos playerChunk = mc.player.getChunkPos();
        visitedChunks.add(playerChunk);
        recordContainerChunkIfNeeded(playerChunk);

        if (!isBack) {
            if (mc.player.getChunkPos().equals(targetChunk)) {
                isBack = true;
            }
            setScanForwardKey(false);
            return;
        }

        if (currentCircle > circleLimit.get()) {
            setScanForwardKey(false);
            info("搜索完成");
            toggle();
            return;
        }

        if (currentPath == SweepRoute.NEXT_CIRCLE) {
            advanceSweepRoute();
            currentCircle++;
            info("前往第" + currentCircle + "圈...");

            int maxPreloadCircle = currentCircle + preloadCircles.get();
            if (maxPreloadCircle <= circleLimit.get()) {
                for (int circle = currentCircle; circle <= maxPreloadCircle; circle++) {
                    appendRingTargets(circle);
                }
            }
        }

        switch (currentPath) {
            case CENTER_TO_LEFT, DOWN_LEFT_TO_LEFT ->
                targetChunk = new ChunkPos(originChunk.x - chunkLoadRadius.get() * currentCircle * 2, originChunk.z);
            case CENTER_LEFT_TO_UP_LEFT ->
                targetChunk = new ChunkPos(originChunk.x - chunkLoadRadius.get() * currentCircle * 2,
                    originChunk.z - chunkLoadRadius.get() * currentCircle * 2);
            case UP_LEFT_TO_UP_RIGHT ->
                targetChunk = new ChunkPos(originChunk.x + chunkLoadRadius.get() * currentCircle * 2,
                    originChunk.z - chunkLoadRadius.get() * currentCircle * 2);
            case UP_RIGHT_TO_DOWN_RIGHT ->
                targetChunk = new ChunkPos(originChunk.x + chunkLoadRadius.get() * currentCircle * 2,
                    originChunk.z + chunkLoadRadius.get() * currentCircle * 2);
            case DOWN_RIGHT_TO_DOWN_LEFT ->
                targetChunk = new ChunkPos(originChunk.x - chunkLoadRadius.get() * currentCircle * 2,
                    originChunk.z + chunkLoadRadius.get() * currentCircle * 2);
        }

        if (mc.player.getChunkPos().equals(targetChunk)) {
            setScanForwardKey(false);
            if (turnDelayTimer == 0) {
                turnDelayTimer = turnDelay.get();
                return;
            }

            if (turnDelayTimer == 1) {
                advanceSweepRoute();
                info("已完成第" + currentCircle + "圈的 " + currentPath);
            }

            turnDelayTimer--;
            return;
        }

        if (targetChunk == null || turnDelayTimer > 0) {
            setScanForwardKey(false);
            return;
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d targetPos = new Vec3d(targetChunk.getStartX() + 8, mc.player.getY(), targetChunk.getStartZ() + 8);

        double deltaX = targetPos.x - playerPos.x;
        double deltaZ = targetPos.z - playerPos.z;

        double distance2D = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        if (distance2D < 1.0) {
            setScanForwardKey(false);
            return;
        }

        targetYaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        mc.player.setYaw(targetYaw);

        if (waitChunkLoad.get()) {
            if (!areNeighborChunksLoaded(targetYaw)) {
                setScanForwardKey(false);
                return;
            }

            int chunkX = (int) (mc.player.getX() / 16);
            int chunkZ = (int) (mc.player.getZ() / 16);
            if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                setScanForwardKey(false);
                return;
            }
        }

        if (moveSpeed.get() > 1.0) {
            mc.player.setSprinting(true);
        }

        setScanForwardKey(true);
    }

    @Override
    public void onDeactivate() {
        setScanForwardKey(false);
        info("你可以保存此信息以便于下次重新开始: ");
        if (originChunk != null) {
            info("originX（起始区块X）: " + originChunk.x);
            info("originZ（起始区块Z）: " + originChunk.z);
        }
        info("circle（圈数）: " + currentCircle);
        info("currentPath（圈进度）: " + currentPath);

        // 保存进度信息
        if (originChunk != null && mc.player != null) {
            lastOriginX.set(originChunk.x);
            lastOriginZ.set(originChunk.z);
            lastChunkX.set(mc.player.getChunkPos().x);
            lastChunkZ.set(mc.player.getChunkPos().z);
            lastCircle.set(currentCircle);
            lastPath.set(currentPath);
        }

        // 清空区块数据
        targetChunks.clear();
        visitedChunks.clear();
        currentPathChunks.clear();

        // 还原变量
        originChunk = null;
        targetChunk = null;
        currentPath = null;
    }

    private void setScanForwardKey(boolean pressed) {
        if (mc.options == null) return;

        if (pressed) {
            mc.options.forwardKey.setPressed(true);
            forcingForward = true;
        } else if (forcingForward) {
            mc.options.forwardKey.setPressed(false);
            forcingForward = false;
        }
    }

    // 检查当前区块左右两边区块是否已完全加载
    private boolean areNeighborChunksLoaded(float yaw) {
        ChunkPos currentChunk = mc.player.getChunkPos();
        boolean movingAlongZ = Math.abs(Math.cos(Math.toRadians(yaw))) >= Math.abs(Math.sin(Math.toRadians(yaw)));

        for (int offset = -chunkLoadRadius.get(); offset <= chunkLoadRadius.get(); offset++) {
            int chunkX = movingAlongZ ? currentChunk.x + offset : currentChunk.x;
            int chunkZ = movingAlongZ ? currentChunk.z : currentChunk.z + offset;
            if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) return false;
        }

        return true;
    }

    private void recordContainerChunkIfNeeded(ChunkPos chunkPos) {
        long key = chunkPos.toLong();
        if (recordedContainerChunks.contains(key)) return;

        WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false);
        if (chunk == null) return;

        int count = 0;
        BlockPos firstPos = null;

        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!containerBlocks.get().contains(blockEntity.getType())) continue;

            count++;
            if (firstPos == null) firstPos = blockEntity.getPos();
        }

        if (count < containerThreshold.get()) return;

        recordedContainerChunks.add(key);
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos recordPos = firstPos != null ? firstPos : playerPos;
        appendContainerRecord(chunkPos, recordPos, playerPos, count);
        createXaeroWaypointIfEnabled(recordPos);
        announceBaseDiscovery(chunkPos, recordPos, count);
    }

    private boolean isScanAreaLoaded() {
        ChunkPos center = mc.player.getChunkPos();
        int radius = mc.options.getViewDistance().getValue();

        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                if (!mc.world.getChunkManager().isChunkLoaded(center.x + offsetX, center.z + offsetZ)) return false;
            }
        }

        return true;
    }

    private void announceBaseDiscovery(ChunkPos chunkPos, BlockPos recordPos, int count) {
        ChatUtils.warning("(highlight)(bold)Base found! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default) | Containers: (highlight)%d(default)",
            chunkPos.x, chunkPos.z, recordPos.getX(), recordPos.getY(), recordPos.getZ(), count);
    }

    private boolean hasReachedWaypointLimit(BlockPos candidate) {
        int radiusBlocks = waypointLimitRadius.get() * 16;
        int nearby = 0;
        for (BlockPos existing : createdWaypointPositions) {
            if (Math.abs(existing.getX() - candidate.getX()) > radiusBlocks || Math.abs(existing.getZ() - candidate.getZ()) > radiusBlocks) continue;
            if (++nearby >= maximumWaypointsPerArea.get()) {
                info("Skipped Xaero waypoint near (%d, %d): area limit of %d reached.", candidate.getX(), candidate.getZ(), maximumWaypointsPerArea.get());
                return true;
            }
        }
        return false;
    }

    private int getXaeroWaypointColorId() {
        XaeroWaypointColor color = xaeroWaypointColor.get();
        return color == XaeroWaypointColor.RANDOM ? ThreadLocalRandom.current().nextInt(16) : color.colorId;
    }
    private void appendContainerRecord(ChunkPos chunkPos, BlockPos recordPos, BlockPos playerPos, int count) {
        String line = "%s | chunk=(%d,%d) | first-container=(%d,%d,%d) | player=(%d,%d,%d) | count=%d%n".formatted(
            LocalDateTime.now().format(RECORD_TIME_FORMAT),
            chunkPos.x,
            chunkPos.z,
            recordPos.getX(),
            recordPos.getY(),
            recordPos.getZ(),
            playerPos.getX(),
            playerPos.getY(),
            playerPos.getZ(),
            count
        );

        try {
            Files.createDirectories(CONTAINER_RECORD_PATH.getParent());
            Files.writeString(CONTAINER_RECORD_PATH, line, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            error("Failed to save container chunk record: %s", e.getMessage());
        }
    }

    private void createXaeroWaypointIfEnabled(BlockPos pos) {
        if (!validateXaeroWaypointSetting()) return;

        try {
            if (hasReachedWaypointLimit(pos)) return;

            String name = xaeroWaypointPrefix.get() + nextWaypointNumber + xaeroWaypointSuffix.get();
            String initials = makeWaypointInitials(name);

            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Object currentSession = sessionClass.getMethod("getCurrentSession").invoke(null);
            if (currentSession == null) {
                warning("Xaero's Minimap session is not ready. Container record saved without a waypoint.");
                return;
            }

            Object processor = currentSession.getClass().getMethod("getMinimapProcessor").invoke(currentSession);
            Object minimapSession = processor.getClass().getMethod("getSession").invoke(processor);
            Object worldManager = minimapSession.getClass().getMethod("getWorldManager").invoke(minimapSession);
            Object currentWorld = worldManager.getClass().getMethod("getCurrentWorld").invoke(worldManager);
            if (currentWorld == null) {
                warning("Xaero current waypoint world is not ready. Container record saved without a waypoint.");
                return;
            }

            Object waypointSet = currentWorld.getClass().getMethod("getCurrentWaypointSet").invoke(currentWorld);
            if (waypointSet == null) {
                warning("Xaero current waypoint set is not ready. Container record saved without a waypoint.");
                return;
            }

            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Constructor<?> constructor = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
            Object waypoint = constructor.newInstance(pos.getX(), pos.getY(), pos.getZ(), name, initials, getXaeroWaypointColorId());
            Method addMethod = waypointSet.getClass().getMethod("add", waypointClass);
            addMethod.invoke(waypointSet, waypoint);

            Object waypointSession = minimapSession.getClass().getMethod("getWaypointSession").invoke(minimapSession);
            waypointSession.getClass().getMethod("setSetChangedTime", long.class).invoke(waypointSession, System.currentTimeMillis());
            createdWaypointPositions.add(pos.toImmutable());
            nextWaypointNumber++;
            info("Created Xaero waypoint: " + name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warning("Failed to create Xaero waypoint: %s", e.getMessage());
        }
    }

    private boolean isXaeroAvailable() {
        try {
            Class.forName("xaero.common.XaeroMinimapSession");
            Class.forName("xaero.common.minimap.waypoints.Waypoint");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private boolean validateXaeroWaypointSetting() {
        if (!xaeroWaypoints.get()) return false;
        if (isXaeroAvailable()) return true;

        xaeroWaypoints.set(false);
        warning("Xaero's Minimap was not detected. Xaero Waypoints has been disabled, but container recording will continue.");
        return false;
    }

    private String makeWaypointInitials(String name) {
        if (name == null || name.isBlank()) return "B";

        StringBuilder initials = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && initials.length() < 2) initials.append(Character.toUpperCase(part.charAt(0)));
        }

        return initials.isEmpty() ? "B" : initials.toString();
    }


    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null)
            return;
        recordedContainerChunks.clear();
        createdWaypointPositions.clear();
        nextWaypointNumber = 1;
        validateXaeroWaypointSetting();

        BlockPos playerPos = new BlockPos(mc.player.getBlockX(), renderHeight.get(), mc.player.getBlockZ());
        double renderDistanceBlocks = renderDistance.get() * 16.0;

        // 只渲染目标区块，根据状态使用不同颜色
        synchronized (targetChunks) {
            for (ChunkPos chunk : targetChunks) {
                if (chunk != null && playerPos.isWithinDistance(
                        new BlockPos(chunk.getCenterX(), renderHeight.get(), chunk.getCenterZ()),
                        renderDistanceBlocks)) {

                    // 根据区块状态选择颜色
                    SettingColor sideColor, lineColor;

                    if (currentPathChunks.contains(chunk)) {
                        // 当前路径区块
                        sideColor = currentPathSideColor.get();
                        lineColor = currentPathLineColor.get();
                    } else if (visitedChunks.contains(chunk)) {
                        // 已访问区块
                        sideColor = visitedChunksSideColor.get();
                        lineColor = visitedChunksLineColor.get();
                    } else {
                        // 未访问的目标区块
                        sideColor = targetChunksSideColor.get();
                        lineColor = targetChunksLineColor.get();
                    }

                    if (sideColor.a > 5 || lineColor.a > 5) {
                        renderScanChunk(chunk, sideColor, lineColor, event);
                    }
                }
            }
        }
    }

    // 渲染单个区块的方法
    private void renderScanChunk(ChunkPos chunk, SettingColor sideColor, SettingColor lineColor, Render3DEvent event) {
        Box box = new Box(
                new Vec3d(chunk.getStartX(), renderHeight.get(), chunk.getStartZ()),
                new Vec3d(chunk.getEndX() + 1, renderHeight.get() + 1, chunk.getEndZ() + 1));

        event.renderer.box(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                sideColor, lineColor, shapeMode.get(), 0);
    }

    // 更新下一个路径
    private void advanceSweepRoute() {
        switch (currentPath) {
            case NEXT_CIRCLE -> currentPath = SweepRoute.CENTER_TO_LEFT;
            case CENTER_TO_LEFT -> currentPath = SweepRoute.CENTER_LEFT_TO_UP_LEFT;
            case CENTER_LEFT_TO_UP_LEFT -> currentPath = SweepRoute.UP_LEFT_TO_UP_RIGHT;
            case UP_LEFT_TO_UP_RIGHT -> currentPath = SweepRoute.UP_RIGHT_TO_DOWN_RIGHT;
            case UP_RIGHT_TO_DOWN_RIGHT -> currentPath = SweepRoute.DOWN_RIGHT_TO_DOWN_LEFT;
            case DOWN_RIGHT_TO_DOWN_LEFT -> currentPath = SweepRoute.DOWN_LEFT_TO_LEFT;
            case DOWN_LEFT_TO_LEFT -> currentPath = SweepRoute.NEXT_CIRCLE;
        }
    }

    // ==================== 速度获取和设置方法 ====================

    /**
     * 获取玩家当前的X轴速度
     */
    private double getX() {
        return mc.player.getVelocity().x;
    }

    /**
     * 获取玩家当前的Y轴速度
     */
    private double getY() {
        return mc.player.getVelocity().y;
    }

    /**
     * 获取玩家当前的Z轴速度
     */
    private double getZ() {
        return mc.player.getVelocity().z;
    }

    /**
     * 设置玩家的X轴速度
     * 保持Y和Z轴速度不变
     */
    private void setX(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(f, currentVel.y, currentVel.z);
        mc.player.setVelocity(newVel);
    }

    /**
     * 设置玩家的Y轴速度
     * 保持X和Z轴速度不变
     */
    private void setY(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(currentVel.x, f, currentVel.z);
        mc.player.setVelocity(newVel);
    }

    /**
     * 设置玩家的Z轴速度
     * 保持X和Y轴速度不变
     */
    private void setZ(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(currentVel.x, currentVel.y, f);
        mc.player.setVelocity(newVel);
    }

    public enum SweepRoute {
        NEXT_CIRCLE,
        CENTER_TO_LEFT,
        CENTER_LEFT_TO_UP_LEFT,
        UP_LEFT_TO_UP_RIGHT,
        UP_RIGHT_TO_DOWN_RIGHT,
        DOWN_RIGHT_TO_DOWN_LEFT,
        DOWN_LEFT_TO_LEFT
    }
}
