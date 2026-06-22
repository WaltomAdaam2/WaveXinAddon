package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.util.math.ChunkPos;

public class BaseFinderXin extends Module {
    public enum ScanMode {
        RESUME("§a按保存的断点"),
        CALCULATE("§e根据坐标实时计算"),
        CURRENT("§b从当前位置开始");

        private final String name;

        ScanMode(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> chunkStep = sgGeneral.add(new IntSetting.Builder()
        .name("区块步长")
        .description("每移动多少个区块后旋转视角")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Integer> maxChunks = sgGeneral.add(new IntSetting.Builder()
        .name("最大区块数")
        .description("扫描区块段数上限，0 为无限")
        .defaultValue(0)
        .min(0)
        .sliderMax(10000)
        .build()
    );

    private final Setting<ScanMode> scanMode = sgGeneral.add(new EnumSetting.Builder<ScanMode>()
        .name("扫描方式")
        .description("选择扫描的起点和恢复方式")
        .defaultValue(ScanMode.CURRENT)
        .build()
    );

    private final Setting<Boolean> debugMode = sgGeneral.add(new BoolSetting.Builder()
        .name("调试模式")
        .description("在聊天栏显示详细调试信息")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lockView = sgGeneral.add(new BoolSetting.Builder()
        .name("锁定视角")
        .description("强制锁定视角朝向扫描方向")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoWalk = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Walk")
        .description("Automatically holds forward while scanning.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sprint = sgGeneral.add(new BoolSetting.Builder()
        .name("Sprint")
        .description("Sprints while auto walking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnScreen = sgGeneral.add(new BoolSetting.Builder()
        .name("Pause On Screen")
        .description("Pauses scanning controls while a screen is open.")
        .defaultValue(true)
        .build()
    );

    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> renderRoute = sgRender.add(new BoolSetting.Builder()
        .name("Render Route")
        .description("Renders the current map scan target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renderRange = sgRender.add(new IntSetting.Builder()
        .name("Render Range")
        .description("How many chunk markers to render toward the target.")
        .defaultValue(64)
        .min(16)
        .sliderRange(16, 256)
        .build()
    );

    private final Setting<Double> renderHeight = sgRender.add(new DoubleSetting.Builder()
        .name("Render Height")
        .description("Render height offset from the player's block Y.")
        .defaultValue(0.02)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("Shape Mode")
        .description("Rendered route shape mode.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> routeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("Route Side Color")
        .description("Route marker side color.")
        .defaultValue(new SettingColor(0, 180, 255, 35))
        .build()
    );

    private final Setting<SettingColor> routeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("Route Line Color")
        .description("Route marker line color.")
        .defaultValue(new SettingColor(0, 220, 255, 180))
        .build()
    );

    // 螺旋状态
    private MapScanDirection currentDir = MapScanDirection.EAST;
    private int stepsInCurrentLength = 0;  // 当前步长已走的次数
    private int currentStepLength = 1;     // 当前步长（会递增：1,1,2,2,3,3...）
    private int totalSegments = 0;         // 总段数
    private ChunkPos startPos = null;      // 起始区块坐标
    private int targetChunkX = 0;          // 目标区块 X 坐标
    private int targetChunkZ = 0;          // 目标区块 Z 坐标

    // 旋转控制
    private float targetYaw = 0f;
    private boolean isRotating = false;
    private boolean forcingForward = false;
    
    // 进度恢复设置
    private boolean needsInitialRotation = false; // 是否需要初始旋转校准

    public BaseFinderXin() {
        super(WaveXinAddon.CATEGORY, "base-finder-xin", "方形螺旋扫图 - 自动旋转视角");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        ScanMode mode = scanMode.get();

        switch (mode) {
            case RESUME -> resumeFromSavedProgress();
            case CALCULATE -> calculateFromPositionWithZeroStart();
            case CURRENT -> startFromCurrentPosition();
        }
    }

    /**
     * 模式1：按保存的断点继续（从 0,0 起点，使用保存的进度数据）
     */
    private void resumeFromSavedProgress() {
        ScanProgressManager.ScanProgress savedProgress = ScanProgressManager.loadProgress();
        
        if (savedProgress == null) {
            if (debugMode.get()) {
                info("§e未找到保存的进度文件，请确保之前已保存过进度");
            }
            // 回退到从当前位置开始
            startFromCurrentPosition();
            return;
        }
        
        // 验证 chunkStep 是否一致
        int savedChunkStep = savedProgress.chunkStep;
        int currentChunkStep = chunkStep.get();
        if (savedChunkStep != currentChunkStep) {
            info("§c§l警告: §f检测到区块步长不一致！");
            info("§e保存的步长: §f" + savedChunkStep);
            info("§e当前设置的步长: §f" + currentChunkStep);
            info("§a将使用保存的步长 §f" + savedChunkStep + " §a进行恢复");
            // 临时修改设置以匹配保存的值
            chunkStep.set(savedChunkStep);
        }
        
        // 使用保存的起点（从 0,0 开始的路径）
        int startX = savedProgress.startX;
        int startZ = savedProgress.startZ;
        
        if (debugMode.get()) {
            info("§a使用保存的断点恢复，起点为 §f(" + startX + ", " + startZ + ")" );
            info("§a已扫描 §e" + savedProgress.totalSegments + " §a段");
        }
        
        // 恢复进度状态
        startPos = new ChunkPos(startX, startZ);
        currentDir = MapScanDirection.values()[savedProgress.currentDir];
        stepsInCurrentLength = savedProgress.stepsInCurrentLength;
        currentStepLength = savedProgress.currentStepLength;
        totalSegments = savedProgress.totalSegments;
        needsInitialRotation = false;
        
        // 计算当前目标点
        ChunkPos targetPos = ScanProgressManager.calculateTargetChunkPos(savedProgress, chunkStep.get());
        if (targetPos != null) {
            targetChunkX = targetPos.x;
            targetChunkZ = targetPos.z;
            
            // 校准朝向
            if (!currentDir.isFacingDirection(mc.player.getYaw())) {
                needsInitialRotation = true;
                targetYaw = currentDir.yaw;
                isRotating = true;
                
                if (debugMode.get()) {
                    info("§e正在校准朝向至 §f" + currentDir.name());
                }
            } else {
                if (lockView.get()) {
                    applyRotation(currentDir.yaw);
                }
            }
            
            if (debugMode.get()) {
                info("§a下一个目标区块: §e(" + targetChunkX + ", " + targetChunkZ + ")");
            }
        }
    }

    /**
     * 模式2：根据坐标实时计算（从 0,0 起点，根据玩家位置反推进度）
     */
    private void calculateFromPositionWithZeroStart() {
        ChunkPos playerChunk = mc.player.getChunkPos();
        
        // 前两种模式都以 0,0 作为螺旋路径的理论起点
        final int ORIGIN_X = 0;
        final int ORIGIN_Z = 0;
        
        if (debugMode.get()) {
            info("§e螺旋路径起点: §f(" + ORIGIN_X + ", " + ORIGIN_Z + ")" );
            info("§e正在根据当前位置 §f(" + playerChunk.x + ", " + playerChunk.z + ") §e计算最近拐点...");
        }
        
        // 计算从原点 (0,0) 到玩家位置的最近拐点
        ScanProgressManager.ScanProgress calculatedProgress = ScanProgressManager.calculateProgressFromPosition(
            playerChunk.x, playerChunk.z,
            ORIGIN_X, ORIGIN_Z,
            chunkStep.get()
        );
        
        if (calculatedProgress != null) {
            // 成功计算出进度，从最近的拐点继续
            if (debugMode.get()) {
                info("§a计算成功: §f从第 §e" + calculatedProgress.totalSegments + " §f段继续扫描");
            }
            
            // 计算最近拐点的实际坐标
            int cornerX = ORIGIN_X + calculateOffsetX(calculatedProgress.totalSegments);
            int cornerZ = ORIGIN_Z + calculateOffsetZ(calculatedProgress.totalSegments);
            
            // 检查坐标偏离程度（不考虑正负号，只比较绝对值，允许 ±2 区块误差）
            int diffAbsX = Math.abs(Math.abs(playerChunk.x) - Math.abs(cornerX));
            int diffAbsZ = Math.abs(Math.abs(playerChunk.z) - Math.abs(cornerZ));
            boolean isAtLayer = diffAbsX <= 2 && diffAbsZ <= 2;
            
            if (!isAtLayer) {
                // 玩家不在同一圈层，提示前往
                info("§c§l警告: §f玩家偏离螺旋路径过远！");
                info("§e当前位置(区块): §f(" + playerChunk.x + ", " + playerChunk.z + ")");
                info("§e推荐坐标(区块): §f(" + cornerX + ", " + cornerZ + ")");
                info("§e绝对值差距: §fX=" + diffAbsX + " Z=" + diffAbsZ + " §e(区块)");
                info("§a请前往坐标 §f(" + (cornerX * 16) + ", " + (cornerZ * 16) + ") §a后重新启动扫描");
                
                // 关闭模块
                toggle();
                return;
            }
            
            // 恢复计算出的进度
            startPos = new ChunkPos(calculatedProgress.startX, calculatedProgress.startZ);
            currentDir = MapScanDirection.values()[calculatedProgress.currentDir];
            stepsInCurrentLength = calculatedProgress.stepsInCurrentLength;
            currentStepLength = calculatedProgress.currentStepLength;
            totalSegments = calculatedProgress.totalSegments;
            needsInitialRotation = false;
            
            // 计算目标点
            ChunkPos targetPos = ScanProgressManager.calculateTargetChunkPos(calculatedProgress, chunkStep.get());
            if (targetPos != null) {
                targetChunkX = targetPos.x;
                targetChunkZ = targetPos.z;
                
                if (!currentDir.isFacingDirection(mc.player.getYaw())) {
                    needsInitialRotation = true;
                    targetYaw = currentDir.yaw;
                    isRotating = true;
                } else {
                    if (lockView.get()) {
                        applyRotation(currentDir.yaw);
                    }
                }
                
                if (debugMode.get()) {
                    info("§a下一个目标区块: §e(" + targetChunkX + ", " + targetChunkZ + ")");
                }
            }
        } else {
            // 无法计算，从原点重新开始
            if (debugMode.get()) {
                info("§e无法计算进度，从原点 §f(" + ORIGIN_X + ", " + ORIGIN_Z + ") §e重新开始扫描");
            }
            startNewScanFromOrigin(ORIGIN_X, ORIGIN_Z);
        }
    }

    /**
     * 模式3：从当前位置开始扫描（以当前位置为起点）
     */
    private void startFromCurrentPosition() {
        ChunkPos playerChunk = mc.player.getChunkPos();
        
        if (debugMode.get()) {
            info("§a从当前位置 §f(" + playerChunk.x + ", " + playerChunk.z + ") §a开始新扫描");
        }
        
        startNewScanFromPosition(playerChunk);
    }

    /**
     * 从指定原点开始新的扫描（起点固定，不从当前位置重置）
     * @param originX 起点区块 X
     * @param originZ 起点区块 Z
     */
    private void startNewScanFromOrigin(int originX, int originZ) {
        startPos = new ChunkPos(originX, originZ);
        info("§a扫描开始: §f起始区块 §e(" + originX + ", " + originZ + ")" );
    
        // 重置状态
        currentDir = MapScanDirection.EAST;
        stepsInCurrentLength = 0;
        currentStepLength = 1;
        totalSegments = 0;
        isRotating = false;
        needsInitialRotation = false;
    
        // 计算第一个目标点
        updateTarget();
    
        // 设置初始朝向（东）
        targetYaw = MapScanDirection.EAST.yaw;
        applyRotation(targetYaw);
    
        // 保存起点坐标
        saveInitialProgress();
    
        if (debugMode.get()) {
            info("§a下一个目标区块: §e(" + targetChunkX + ", " + targetChunkZ + ")");
        }
    }

    /**
     * 从指定位置开始新的扫描
     * @param startPos 起始区块坐标
     */
    private void startNewScanFromPosition(ChunkPos startPos) {
        // 记录起始区块
        this.startPos = startPos;
        info("§a扫描开始: §f起始区块 §e(" + startPos.x + ", " + startPos.z + ")" );
    
        // 重置状态
        currentDir = MapScanDirection.EAST;
        stepsInCurrentLength = 0;
        currentStepLength = 1;
        totalSegments = 0;
        isRotating = false;
        needsInitialRotation = false;
    
        // 计算第一个目标点
        updateTarget();
    
        // 设置初始朝向（东）
        targetYaw = MapScanDirection.EAST.yaw;
        applyRotation(targetYaw);
    
        // 立即保存起点坐标到文件，以便后续崩溃或重启时能根据坐标推算进度
        saveInitialProgress();
    
        if (debugMode.get()) {
            info("§a下一个目标区块: §e(" + targetChunkX + ", " + targetChunkZ + ")");
        }
    }

    /**
     * 保存初始进度（仅记录起点）
     */
    private void saveInitialProgress() {
        if (startPos != null) {
            ScanProgressManager.ScanProgress progress = new ScanProgressManager.ScanProgress(
                startPos.x, startPos.z,
                totalSegments,
                currentDir.ordinal(),
                stepsInCurrentLength,
                currentStepLength,
                chunkStep.get()  // 保存当前的 chunkStep
            );
            ScanProgressManager.saveProgress(progress);
        }
    }

    @Override
    public void onDeactivate() {
        releaseForward();
        info("§c扫描结束: §f共走过 §e" + totalSegments + " §f个区块段");
        
        // 保存进度
        if (startPos != null) {
            ScanProgressManager.ScanProgress progress = new ScanProgressManager.ScanProgress(
                startPos.x, startPos.z,
                totalSegments,
                currentDir.ordinal(),
                stepsInCurrentLength,
                currentStepLength,
                chunkStep.get()  // 保存当前的 chunkStep
            );
            ScanProgressManager.saveProgress(progress);
            
            if (debugMode.get()) {
                info("§a进度已保存");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            releaseForward();
            return;
        }

        if (pauseOnScreen.get() && mc.currentScreen != null) {
            releaseForward();
            return;
        }

        // 检查最大区块限制
        if (maxChunks.get() > 0 && totalSegments >= maxChunks.get()) {
            info("§a已达到最大区块数，扫描完成");
            toggle();
            return;
        }

        // 如果需要初始旋转校准（恢复模式）
        if (needsInitialRotation && isRotating) {
            smoothRotation();
            releaseForward();
            if (isRotating) return; // 旋转未完成，等待下一 tick
            // 旋转完成，继续正常流程
        }

        // 如果正在旋转，执行平滑旋转
        if (isRotating) {
            smoothRotation();
            releaseForward();
            if (isRotating) return;
        }

        // 如果启用锁定视角，持续应用当前方向
        if (lockView.get() && !isRotating && !needsInitialRotation) {
            applyRotation(currentDir.yaw);
        }

        handleAutoWalk();

        // 获取当前位置（区块坐标）
        ChunkPos currentChunk = mc.player.getChunkPos();

        // 检查是否到达或超过目标区块
        if (hasReachedTarget(currentChunk)) {
            // 计算下一个方向，并判断是否需要平滑旋转
            boolean needSmoothRotation = turnToNextDirection();

            // 更新目标坐标
            updateTarget();

            // 只有在需要时才启动平滑旋转
            if (needSmoothRotation) {
                targetYaw = currentDir.yaw;
                isRotating = true;
            }

            if (debugMode.get()) {
                info("§a已转向 §e" + currentDir.name() + " §f→ 下一个目标 §e(" + targetChunkX + ", " + targetChunkZ + ")");
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderRoute.get() || mc.player == null || mc.world == null || startPos == null)
            return;

        int playerChunkX = mc.player.getChunkPos().x;
        int playerChunkZ = mc.player.getChunkPos().z;
        int markers = Math.max(1, renderRange.get() / 16);
        int maxMarkers = Math.min(markers, Math.max(1, currentStepLength * chunkStep.get()));
        double y = Math.floor(mc.player.getY()) + renderHeight.get();

        for (int i = 0; i <= maxMarkers; i++) {
            int chunkX = playerChunkX + currentDir.dx * i;
            int chunkZ = playerChunkZ + currentDir.dz * i;

            if (hasPassedRenderTarget(chunkX, chunkZ)) break;

            double minX = chunkX * 16;
            double minZ = chunkZ * 16;
            event.renderer.box(
                minX, y, minZ,
                minX + 16, y + 0.05, minZ + 16,
                routeSideColor.get(), routeLineColor.get(), shapeMode.get(), 0
            );
        }
    }

    private boolean hasPassedRenderTarget(int chunkX, int chunkZ) {
        return switch (currentDir) {
            case EAST -> chunkX > targetChunkX;
            case WEST -> chunkX < targetChunkX;
            case NORTH -> chunkZ < targetChunkZ;
            case SOUTH -> chunkZ > targetChunkZ;
        };
    }

    private void handleAutoWalk() {
        if (autoWalk.get()) {
            mc.options.forwardKey.setPressed(true);
            forcingForward = true;

            if (sprint.get()) {
                mc.player.setSprinting(true);
            }
        } else {
            releaseForward();
        }
    }

    private void releaseForward() {
        if (!forcingForward) return;

        mc.options.forwardKey.setPressed(false);
        forcingForward = false;
    }

    /**
     * 判断是否已到达或超过目标区块
     */
    private boolean hasReachedTarget(ChunkPos currentChunk) {
        // 主要轴：检查是否到达或超过目标
        boolean mainAxisReached = switch (currentDir) {
            case EAST -> currentChunk.x >= targetChunkX;
            case WEST -> currentChunk.x <= targetChunkX;
            case NORTH -> currentChunk.z <= targetChunkZ;
            case SOUTH -> currentChunk.z >= targetChunkZ;
        };

        // 如果主要轴未到达，直接返回
        if (!mainAxisReached) return false;

        // 次要轴：检查是否偏离（允许±1区块的误差）
        return switch (currentDir) {
            case EAST, WEST -> Math.abs(currentChunk.z - targetChunkZ) <= 1;
            case NORTH, SOUTH -> Math.abs(currentChunk.x - targetChunkX) <= 1;
        };
    }

    /**
     * 更新目标区块坐标
     */
    private void updateTarget() {
        // 计算从起点开始到当前段的累积偏移
        int currentX = startPos.x;
        int currentZ = startPos.z;

        MapScanDirection tempDir = MapScanDirection.EAST;
        int tempStepLen = 1;
        int tempStepsInLen = 0;

        // 累加所有已完成的段
        for (int i = 0; i < totalSegments; i++) {
            // 步长序列：1, 1, 2, 2, 3, 3... （需要乘以 chunkStep）
            int dist = tempStepLen * chunkStep.get();
            currentX += tempDir.dx * dist;
            currentZ += tempDir.dz * dist;

            tempStepsInLen++;
            if (tempStepsInLen >= 2) {
                tempStepLen++;
                tempStepsInLen = 0;
            }

            tempDir = tempDir.getNext();
        }

        // 当前方向的目标 = 已完成的累积位置 + 当前段应移动的距离
        int currentDist = currentStepLength * chunkStep.get();  // 需要乘以 chunkStep
        targetChunkX = currentX + tempDir.dx * currentDist;
        targetChunkZ = currentZ + tempDir.dz * currentDist;
    }

    /**
     * 转向下一个方向
     * @return 是否需要后续平滑旋转
     */
    private boolean turnToNextDirection() {
        // 方向循环：EAST -> NORTH -> WEST -> SOUTH -> EAST
        currentDir = currentDir.getNext();

        // 更新步数计数
        stepsInCurrentLength++;
        totalSegments++;

        // 每两次相同步长后，步长增加1（1,1,2,2,3,3,4,4...）
        if (stepsInCurrentLength >= 2) {
            currentStepLength++;
            stepsInCurrentLength = 0;
        }

        // 如果启用锁定视角，立即应用新方向的旋转
        if (lockView.get()) {
            applyRotation(currentDir.yaw);
            return false;  // 不需要后续平滑旋转
        }
        
        return true;  // 需要后续平滑旋转
    }

    /**
     * 平滑旋转视角
     */
    private void smoothRotation() {
        if (mc.player == null) {
            isRotating = false;
            return;
        }

        float currentYaw = mc.player.getYaw();
        float diff = targetYaw - currentYaw;

        // 处理角度跨越 -180/180 的问题
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;

        // 旋转速度（度/tick）
        float rotationSpeed = 15f;

        if (Math.abs(diff) < rotationSpeed) {
            // 接近目标，直接设置
            applyRotation(targetYaw);

            if (isRotating && debugMode.get()) {
                info("§a旋转完成");
            }
            isRotating = false;
        } else {
            // 逐步旋转
            applyRotation(currentYaw + Math.signum(diff) * rotationSpeed);
        }
    }

    /**
     * 应用旋转（同时设置 yaw, headYaw, bodyYaw）
     */
    private void applyRotation(float yaw) {
        mc.player.setYaw(yaw);
        mc.player.headYaw = yaw;
        mc.player.bodyYaw = yaw;
    }

    /**
     * 计算从起点开始经过指定段数后的 X 偏移量
     */
    private int calculateOffsetX(int segments) {
        int x = 0;
        MapScanDirection tempDir = MapScanDirection.EAST;
        int tempStepLen = 1;
        int tempStepsInLen = 0;

        for (int i = 0; i < segments; i++) {
            x += tempDir.dx * tempStepLen * chunkStep.get();  // 需要乘以 chunkStep

            tempStepsInLen++;
            if (tempStepsInLen >= 2) {
                tempStepLen++;
                tempStepsInLen = 0;
            }

            tempDir = tempDir.getNext();
        }

        return x;
    }

    /**
     * 计算从起点开始经过指定段数后的 Z 偏移量
     */
    private int calculateOffsetZ(int segments) {
        int z = 0;
        MapScanDirection tempDir = MapScanDirection.EAST;
        int tempStepLen = 1;
        int tempStepsInLen = 0;

        for (int i = 0; i < segments; i++) {
            z += tempDir.dz * tempStepLen * chunkStep.get();  // 需要乘以 chunkStep

            tempStepsInLen++;
            if (tempStepsInLen >= 2) {
                tempStepLen++;
                tempStepsInLen = 0;
            }

            tempDir = tempDir.getNext();
        }

        return z;
    }

    @Override
    public String getInfoString() {
        return "§e" + totalSegments + " §f| §e" + currentDir.name();
    }
}
