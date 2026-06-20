package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class BaseFinderXin extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgRestart = settings.createGroup("Restart");
    private ChunkPos originChunk;
    private ChunkPos targetChunk;
    private int currentCircle;
    private PathEnum currentPath;
    private boolean isBack;
    private int turnDelayTimer;
    private float targetYaw;

    private boolean forcingForward;

    
    private final Set<ChunkPos> targetChunks = Collections.synchronizedSet(new HashSet<>());
    private final Set<ChunkPos> visitedChunks = Collections.synchronizedSet(new HashSet<>());
    private final Set<ChunkPos> currentPathChunks = Collections.synchronizedSet(new HashSet<>());

    
    public final Setting<Integer> chunkLoadRadius = sgGeneral.add(new IntSetting.Builder()
            .name("Chunk Load Radius")
            .description("Loaded chunk radius in the current world")
            .defaultValue(5)
            .min(2)
            .max(10)
            .sliderMin(2)
            .sliderMax(10)
            .build());

    
    public final Setting<Integer> circleLimit = sgGeneral.add(new IntSetting.Builder()
            .name("Search Circle Limit")
            .description("Stops after reaching the search circle limit")
            .defaultValue(50)
            .min(2)
            .max(Integer.MAX_VALUE)
            .sliderMin(2)
            .sliderMax(100)
            .build());

    
    private final Setting<Double> moveSpeed = sgGeneral.add(new DoubleSetting.Builder()
            .name("Movement Speed")
            .description("Only controls sprint toggle; it does not multiply movement speed.")
            .defaultValue(3.0)
            .min(0.01)
            .max(3.0)
            .sliderMin(0.01)
            .sliderMax(3.0)
            .build());

    
    private final Setting<Integer> turnDelay = sgGeneral.add(new IntSetting.Builder()
            .name("Turn Delay")
            .description("Delay before turning to help chunks load")
            .defaultValue(40)
            .min(1)
            .max(100)
            .sliderMin(1)
            .sliderMax(100)
            .build());

    
    private final Setting<Boolean> waitChunkLoad = sgGeneral.add(new BoolSetting.Builder()
            .name("Wait for Chunks")
            .description("Waits for searched chunks to load")
            .defaultValue(true)
            .build());

    
    private final Setting<Boolean> lastBegin = sgRestart.add(new BoolSetting.Builder()
            .name("Resume Previous Search")
            .description("Whether to resume saved progress")
            .defaultValue(false)
            .build());

    
    private final Setting<Integer> lastCircle = sgRestart.add(new IntSetting.Builder()
            .name("Last Circle")
            .description("Last Circle")
            .visible(lastBegin::get)
            .defaultValue(0)
            .min(0)
            .max(1000)
            .sliderMin(0)
            .sliderMax(100)
            .build());

    
    private final Setting<Integer> lastChunkX = sgRestart.add(new IntSetting.Builder()
            .name("Last Paused Chunk X")
            .description("Last Paused Chunk X")
            .visible(lastBegin::get)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    
    private final Setting<Integer> lastChunkZ = sgRestart.add(new IntSetting.Builder()
            .name("Last Paused Chunk Z")
            .description("Last Paused Chunk Z")
            .visible(lastBegin::get)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    
    private final Setting<PathEnum> lastPath = sgRestart.add(new EnumSetting.Builder<PathEnum>()
            .name("Last Path Point")
            .description("Last Path Point")
            .visible(lastBegin::get)
            .defaultValue(PathEnum.NEXT_CIRCLE)
            .build());

    
    private final Setting<Integer> lastOriginX = sgRestart.add(new IntSetting.Builder()
            .name("Last Origin Chunk X")
            .description("Last Origin Chunk X")
            .visible(lastBegin::get)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    
    private final Setting<Integer> lastOriginZ = sgRestart.add(new IntSetting.Builder()
            .name("Last Origin Chunk Z")
            .description("Last Origin Chunk Z")
            .visible(lastBegin::get)
            .defaultValue(0)
            .min(Integer.MIN_VALUE)
            .max(Integer.MAX_VALUE)
            .sliderMin(Integer.MIN_VALUE)
            .sliderMax(Integer.MAX_VALUE)
            .build());

    
    public final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
            .name("Render Range")
            .description("Render range in chunks")
            .defaultValue(32)
            .min(6)
            .max(128)
            .sliderMin(6)
            .sliderMax(128)
            .build());

    
    public final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
            .name("Render Height")
            .description("Render Height")
            .defaultValue(0)
            .min(-64)
            .max(320)
            .sliderMin(-64)
            .sliderMax(320)
            .build());

    
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("Render Mode")
            .description("Rendered shape mode")
            .defaultValue(ShapeMode.Both)
            .build());

    
    private final Setting<Integer> preloadCircles = sgRender.add(new IntSetting.Builder()
            .name("Preload Circles")
            .description("Number of circles to preload")
            .defaultValue(3)
            .min(1)
            .max(10)
            .sliderMin(1)
            .sliderMax(10)
            .build());

    
    private final Setting<SettingColor> targetChunksSideColor = sgRender.add(new ColorSetting.Builder()
            .name("Target Chunk Side Color")
            .description("Target Chunk Side Color")
            .defaultValue(new SettingColor(255, 0, 0, 95))
            .visible(() -> (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
            .build());

    private final Setting<SettingColor> targetChunksLineColor = sgRender.add(new ColorSetting.Builder()
            .name("Target Chunk Line Color")
            .description("Target chunk line color")
            .defaultValue(new SettingColor(255, 0, 0, 205))
            .visible(() -> (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both))
            .build());

    
    private final Setting<SettingColor> visitedChunksSideColor = sgRender.add(new ColorSetting.Builder()
            .name("Visited Chunk Side Color")
            .description("Visited Chunk Side Color")
            .defaultValue(new SettingColor(0, 255, 0, 40))
            .visible(() -> (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
            .build());

    private final Setting<SettingColor> visitedChunksLineColor = sgRender.add(new ColorSetting.Builder()
            .name("Visited Chunk Line Color")
            .description("Visited Chunk Line Color")
            .defaultValue(new SettingColor(0, 255, 0, 80))
            .visible(() -> (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both))
            .build());

    
    private final Setting<SettingColor> currentPathSideColor = sgRender.add(new ColorSetting.Builder()
            .name("Current Path Side Color")
            .description("Current Path Side Color")
            .defaultValue(new SettingColor(255, 255, 0, 60))
            .visible(() -> (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
            .build());

    private final Setting<SettingColor> currentPathLineColor = sgRender.add(new ColorSetting.Builder()
            .name("Current Path Line Color")
            .description("Current Path Line Color")
            .defaultValue(new SettingColor(255, 255, 0, 100))
            .visible(() -> (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both))
            .build());

    public BaseFinderXin() {
        super(WaveXinAddon.CATEGORY, "base-finder-xin", "base-finder-xin");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        boolean resumeFromSaved = lastBegin.get() && isSavedStateValid();
        turnDelayTimer = 0;

        
        if (resumeFromSaved) {
            
            currentCircle = lastCircle.get();
            currentPath = lastPath.get();
            originChunk = new ChunkPos(lastOriginX.get(), lastOriginZ.get());
        } else {
            
            currentCircle = 0;
            currentPath = PathEnum.NEXT_CIRCLE;
            originChunk = mc.player.getChunkPos();
        }

        
        targetChunks.clear();
        visitedChunks.clear();
        currentPathChunks.clear();

        
        preloadTargetChunks();

        isBack = true;
        targetChunk = null;
    }

    private boolean isSavedStateValid() {
        return lastCircle.get() >= 0
            && lastCircle.get() <= circleLimit.get()
            && lastPath.get() != null
            && !(lastPath.get() == PathEnum.NEXT_CIRCLE && lastCircle.get() == 0);
    }

    
    private void preloadTargetChunks() {
        targetChunks.clear();

        
        int maxPreloadCircle = Math.min(currentCircle + preloadCircles.get(), circleLimit.get());
        for (int circle = currentCircle; circle <= maxPreloadCircle; circle++) {
            addCircleChunks(circle);
        }
    }

    
    private void addCircleChunks(int circle) {
        int radius = chunkLoadRadius.get() * circle * 2;

        
        ChunkPos centerLeft = new ChunkPos(originChunk.x - radius, originChunk.z);
        targetChunks.add(centerLeft);

        
        ChunkPos upLeft = new ChunkPos(originChunk.x - radius, originChunk.z - radius);
        addPathChunks(centerLeft, upLeft);

        
        ChunkPos upRight = new ChunkPos(originChunk.x + radius, originChunk.z - radius);
        addPathChunks(upLeft, upRight);

        
        ChunkPos downRight = new ChunkPos(originChunk.x + radius, originChunk.z + radius);
        addPathChunks(upRight, downRight);

        
        ChunkPos downLeft = new ChunkPos(originChunk.x - radius, originChunk.z + radius);
        addPathChunks(downRight, downLeft);

        
        addPathChunks(downLeft, centerLeft);
    }

    
    private void addPathChunks(ChunkPos from, ChunkPos to) {
        int deltaX = to.x - from.x;
        int deltaZ = to.z - from.z;

        int steps = Math.max(Math.abs(deltaX), Math.abs(deltaZ));

        
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

    
    private void updateCurrentPathChunks() {
        currentPathChunks.clear();

        if (originChunk == null || currentPath == null)
            return;

        int radius = chunkLoadRadius.get() * currentCircle * 2;

        switch (currentPath) {
            case CENTER_TO_LEFT -> {
                ChunkPos from = originChunk;
                ChunkPos to = new ChunkPos(originChunk.x - radius, originChunk.z);
                addCurrentPathChunks(from, to);
            }
            case CENTER_LEFT_TO_UP_LEFT -> {
                ChunkPos from = new ChunkPos(originChunk.x - radius, originChunk.z);
                ChunkPos to = new ChunkPos(originChunk.x - radius, originChunk.z - radius);
                addCurrentPathChunks(from, to);
            }
            case UP_LEFT_TO_UP_RIGHT -> {
                ChunkPos from = new ChunkPos(originChunk.x - radius, originChunk.z - radius);
                ChunkPos to = new ChunkPos(originChunk.x + radius, originChunk.z - radius);
                addCurrentPathChunks(from, to);
            }
            case UP_RIGHT_TO_DOWN_RIGHT -> {
                ChunkPos from = new ChunkPos(originChunk.x + radius, originChunk.z - radius);
                ChunkPos to = new ChunkPos(originChunk.x + radius, originChunk.z + radius);
                addCurrentPathChunks(from, to);
            }
            case DOWN_RIGHT_TO_DOWN_LEFT -> {
                ChunkPos from = new ChunkPos(originChunk.x + radius, originChunk.z + radius);
                ChunkPos to = new ChunkPos(originChunk.x - radius, originChunk.z + radius);
                addCurrentPathChunks(from, to);
            }
            case DOWN_LEFT_TO_LEFT -> {
                ChunkPos from = new ChunkPos(originChunk.x - radius, originChunk.z + radius);
                ChunkPos to = new ChunkPos(originChunk.x - radius, originChunk.z);
                addCurrentPathChunks(from, to);
            }
        }
    }

    
    private void addCurrentPathChunks(ChunkPos from, ChunkPos to) {
        int deltaX = to.x - from.x;
        int deltaZ = to.z - from.z;

        int steps = Math.max(Math.abs(deltaX), Math.abs(deltaZ));

        if (steps == 0) {
            currentPathChunks.add(from);
            return;
        }

        for (int i = 0; i <= steps; i++) {
            int x = from.x + (deltaX * i) / steps;
            int z = from.z + (deltaZ * i) / steps;
            currentPathChunks.add(new ChunkPos(x, z));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            setForwardForced(false);
            return;
        }

        if (originChunk == null || currentPath == null) {
            setForwardForced(false);
            toggle();
            return;
        }

        updateCurrentPathChunks();

        ChunkPos playerChunk = mc.player.getChunkPos();
        visitedChunks.add(playerChunk);

        if (!isBack) {
            if (mc.player.getChunkPos().equals(targetChunk)) {
                isBack = true;
            }
            setForwardForced(false);
            return;
        }

        if (currentCircle > circleLimit.get()) {
            setForwardForced(false);
            info("Search complete");
            toggle();
            return;
        }

        if (currentPath == PathEnum.NEXT_CIRCLE) {
            updateNextPath();
            currentCircle++;
            info("Going to circle " + currentCircle + "...");

            int maxPreloadCircle = currentCircle + preloadCircles.get();
            if (maxPreloadCircle <= circleLimit.get()) {
                for (int circle = currentCircle; circle <= maxPreloadCircle; circle++) {
                    addCircleChunks(circle);
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
            setForwardForced(false);
            if (turnDelayTimer == 0) {
                turnDelayTimer = turnDelay.get();
                return;
            }

            if (turnDelayTimer == 1) {
                updateNextPath();
                info("Completed circle " + currentCircle + " path " + currentPath);
            }

            turnDelayTimer--;
            return;
        }

        if (targetChunk == null || turnDelayTimer > 0) {
            setForwardForced(false);
            return;
        }

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d targetPos = new Vec3d(targetChunk.getStartX() + 8, mc.player.getY(), targetChunk.getStartZ() + 8);

        double deltaX = targetPos.x - playerPos.x;
        double deltaZ = targetPos.z - playerPos.z;

        double distance2D = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        if (distance2D < 1.0) {
            setForwardForced(false);
            return;
        }

        targetYaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        mc.player.setYaw(targetYaw);

        if (waitChunkLoad.get()) {
            Direction playerDirection = Direction.fromHorizontalDegrees(targetYaw);
            if (!AdjacentChunksLoaded(playerDirection)) {
                setForwardForced(false);
                return;
            }

            ChunkPos chunkPos = mc.player.getChunkPos();
            if (!mc.world.getChunkManager().isChunkLoaded(chunkPos.x, chunkPos.z)) {
                setForwardForced(false);
                return;
            }
        }

        if (moveSpeed.get() > 1.0) {
            mc.player.setSprinting(true);
        }

        setForwardForced(true);
    }

    @Override
    public void onDeactivate() {
        setForwardForced(false);
        info("Save this information if you want to resume later: ");
        if (originChunk != null) {
            info("originX (start chunk X): " + originChunk.x);
            info("originZ (start chunk Z): " + originChunk.z);
        }
        info("circle: " + currentCircle);
        info("currentPath: " + currentPath);

        
        if (originChunk != null && currentPath != null && mc.player != null) {
            lastOriginX.set(originChunk.x);
            lastOriginZ.set(originChunk.z);
            lastChunkX.set(mc.player.getChunkPos().x);
            lastChunkZ.set(mc.player.getChunkPos().z);
            lastCircle.set(currentCircle);
            lastPath.set(currentPath);
        }

        
        targetChunks.clear();
        visitedChunks.clear();
        currentPathChunks.clear();
        turnDelayTimer = 0;

        
        originChunk = null;
        targetChunk = null;
        currentPath = null;
    }

    private void setForwardForced(boolean pressed) {
        if (mc.options == null) return;

        if (pressed) {
            mc.options.forwardKey.setPressed(true);
            forcingForward = true;
        } else if (forcingForward) {
            mc.options.forwardKey.setPressed(false);
            forcingForward = false;
        }
    }

    
    private boolean AdjacentChunksLoaded(Direction direction) {
        ChunkPos currentChunk = mc.player.getChunkPos();

        for (int i = -chunkLoadRadius.get(); i <= chunkLoadRadius.get(); i++) {
            ChunkPos adjacentChunk = null;
            switch (direction) {
                case NORTH, SOUTH ->
                    adjacentChunk = new ChunkPos(currentChunk.x + i, currentChunk.z);
                case EAST, WEST -> adjacentChunk = new ChunkPos(currentChunk.x, currentChunk.z + i);
            }
            if (adjacentChunk == null) continue;

            if (!mc.world.getChunkManager().isChunkLoaded(adjacentChunk.x, adjacentChunk.z)) {
                return false;
            }
        }

        return true;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null)
            return;

        BlockPos playerPos = new BlockPos(mc.player.getBlockX(), renderHeight.get(), mc.player.getBlockZ());
        double renderDistanceBlocks = renderDistance.get() * 16.0;

        
        synchronized (targetChunks) {
            for (ChunkPos chunk : targetChunks) {
                if (chunk != null && playerPos.isWithinDistance(
                        new BlockPos(chunk.getCenterX(), renderHeight.get(), chunk.getCenterZ()),
                        renderDistanceBlocks)) {

                    
                    SettingColor sideColor, lineColor;

                    if (currentPathChunks.contains(chunk)) {
                        
                        sideColor = currentPathSideColor.get();
                        lineColor = currentPathLineColor.get();
                    } else if (visitedChunks.contains(chunk)) {
                        
                        sideColor = visitedChunksSideColor.get();
                        lineColor = visitedChunksLineColor.get();
                    } else {
                        
                        sideColor = targetChunksSideColor.get();
                        lineColor = targetChunksLineColor.get();
                    }

                    if (sideColor.a > 5 || lineColor.a > 5) {
                        renderChunk(chunk, sideColor, lineColor, event);
                    }
                }
            }
        }
    }

    
    private void renderChunk(ChunkPos chunk, SettingColor sideColor, SettingColor lineColor, Render3DEvent event) {
        Box box = new Box(
                new Vec3d(chunk.getStartX(), renderHeight.get(), chunk.getStartZ()),
                new Vec3d(chunk.getEndX() + 1, renderHeight.get() + 1, chunk.getEndZ() + 1));

        event.renderer.box(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                sideColor, lineColor, shapeMode.get(), 0);
    }

    
    private void updateNextPath() {
        switch (currentPath) {
            case NEXT_CIRCLE -> currentPath = PathEnum.CENTER_TO_LEFT;
            case CENTER_TO_LEFT -> currentPath = PathEnum.CENTER_LEFT_TO_UP_LEFT;
            case CENTER_LEFT_TO_UP_LEFT -> currentPath = PathEnum.UP_LEFT_TO_UP_RIGHT;
            case UP_LEFT_TO_UP_RIGHT -> currentPath = PathEnum.UP_RIGHT_TO_DOWN_RIGHT;
            case UP_RIGHT_TO_DOWN_RIGHT -> currentPath = PathEnum.DOWN_RIGHT_TO_DOWN_LEFT;
            case DOWN_RIGHT_TO_DOWN_LEFT -> currentPath = PathEnum.DOWN_LEFT_TO_LEFT;
            case DOWN_LEFT_TO_LEFT -> currentPath = PathEnum.NEXT_CIRCLE;
        }
    }
    public enum PathEnum {
        NEXT_CIRCLE,
        CENTER_TO_LEFT,
        CENTER_LEFT_TO_UP_LEFT,
        UP_LEFT_TO_UP_RIGHT,
        UP_RIGHT_TO_DOWN_RIGHT,
        DOWN_RIGHT_TO_DOWN_LEFT,
        DOWN_LEFT_TO_LEFT
    }
}
