package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Set;

public class BaseFinderXin extends Module {
    private static final Path CONTAINER_RECORD_PATH = MeteorClient.FOLDER.toPath().resolve("base-finder-xin").resolve("container-records.txt");
    private static final DateTimeFormatter RECORD_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public enum ScanMode {
        RESUME("Resume Saved Progress"),
        CALCULATE("Calculate From Position"),
        CURRENT("Start From Current Position");

        private final String name;

        ScanMode(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }


    public enum XaeroWaypointColor {
        RANDOM("Random", -1), RED("Red", 0), ORANGE("Orange", 1), YELLOW("Yellow", 2), LIME("Lime", 3), GREEN("Green", 4), CYAN("Cyan", 5), LIGHT_BLUE("Light Blue", 6), BLUE("Blue", 7), PURPLE("Purple", 8), MAGENTA("Magenta", 9), PINK("Pink", 10), WHITE("White", 11), LIGHT_GRAY("Light Gray", 12), GRAY("Gray", 13), BROWN("Brown", 14), BLACK("Black", 15);
        private final String title; private final int colorId;
        XaeroWaypointColor(String title, int colorId) { this.title = title; this.colorId = colorId; }
        @Override public String toString() { return title; }
    }
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgXaeroWaypoints = settings.createGroup("Xaero Waypoints");

    private final Setting<Integer> chunkStep = sgGeneral.add(new IntSetting.Builder()
        .name("Chunk Step")
        .description("How many chunks to move before rotating the scan direction.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Integer> maxChunks = sgGeneral.add(new IntSetting.Builder()
        .name("Maximum Chunks")
        .description("Maximum scanned chunk segments. 0 means unlimited.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10000)
        .build()
    );

    private final Setting<ScanMode> scanMode = sgGeneral.add(new EnumSetting.Builder<ScanMode>()
        .name("Scan Mode")
        .description("Selects how the scan starts or resumes.")
        .defaultValue(ScanMode.CURRENT)
        .build()
    );

    private final Setting<Boolean> debugMode = sgGeneral.add(new BoolSetting.Builder()
        .name("Debug Mode")
        .description("Shows detailed scan messages in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lockView = sgGeneral.add(new BoolSetting.Builder()
        .name("Lock View")
        .description("Locks view direction toward the current scan direction.")
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
    private final Setting<Boolean> waitForChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("Wait for Chunks").description("Pauses movement until the surrounding scan area has fully loaded.").defaultValue(true).build()
    );

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

    private final Setting<XaeroWaypointColor> xaeroWaypointColor = sgXaeroWaypoints.add(new EnumSetting.Builder<XaeroWaypointColor>()
        .name("Waypoint Color").description("Xaero waypoint color, or a random supported color for each waypoint.").defaultValue(XaeroWaypointColor.RANDOM).visible(xaeroWaypoints::get).build()
    );
    private final Setting<Integer> waypointLimitRadius = sgXaeroWaypoints.add(new IntSetting.Builder()
        .name("Waypoint Limit Radius").description("Chunk radius used to group nearby waypoints into one base area.").defaultValue(8).range(1, 64).sliderRange(1, 32).visible(xaeroWaypoints::get).build()
    );
    private final Setting<Integer> maximumWaypointsPerArea = sgXaeroWaypoints.add(new IntSetting.Builder()
        .name("Maximum Waypoints Per Area").description("Maximum waypoints created within one base area during the current scan.").defaultValue(3).range(1, 100).sliderRange(1, 20).visible(xaeroWaypoints::get).build()
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
        .defaultValue(new SettingColor(0, 180, 255, 32))
        .build()
    );

    private final Setting<SettingColor> routeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("Route Line Color")
        .description("Route marker line color.")
        .defaultValue(new SettingColor(0, 180, 255, 180))
        .build()
    );

    // 闁捐崵鍎ゅΛ鍡涙偐閼哥鍋?
    private MapScanDirection currentDir = MapScanDirection.EAST;
    private int stepsInCurrentLength = 0;  // 鐟滅増鎸告晶鐘差潰閵夆晜姣愮€规瓕灏摂瀣儍閸曨剦鍋ч柡?
    private int currentStepLength = 1;     // 鐟滅増鎸告晶鐘差潰閵夆晜姣愰柨娑樼墔缁变即鏌呴幒鎴澔闁?,1,2,2,3,3...闁?
    private int totalSegments = 0;         // 闁诡剛绮宀勫极?
    private ChunkPos startPos = null;      // 閻犙冨槻椤劙宕犻崫鍕仴闁秆勫姈閻?
    private int targetChunkX = 0;          // 闁烩晩鍠楅悥锝夊礌閸濆嫭鍋?X 闁秆勫姈閻?
    private int targetChunkZ = 0;          // 闁烩晩鍠楅悥锝夊礌閸濆嫭鍋?Z 闁秆勫姈閻?

    // 闁哄啫顑堝ù鍡涘箳瑜嶉崺?
    private float targetYaw = 0f;
    private boolean isRotating = false;
    private boolean forcingForward = false;
    private final Set<Long> recordedContainerChunks = new HashSet<>();
    private final List<BlockPos> createdWaypointPositions = new ArrayList<>();
    
    // 閺夆晜绋戠€规娊骞侀姀鐙€妲婚悹浣稿⒔閻?
    private boolean needsInitialRotation = false; // 闁哄嫷鍨伴幆渚€妫侀埀顒傛啺娴ｇ鐏ュ┑顔碱儐濡棙娼浣哄ⅰ闁?

    public BaseFinderXin() {
        super(WaveXinAddon.CATEGORY, "base-finder", "Square spiral map scanner with automatic view rotation.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        recordedContainerChunks.clear();
        validateXaeroWaypointSetting();

        ScanMode mode = scanMode.get();

        switch (mode) {
            case RESUME -> resumeFromSavedProgress();
            case CALCULATE -> calculateFromPositionWithZeroStart();
            case CURRENT -> startFromCurrentPosition();
        }
    }

    /**
     * 婵☆垪鈧磭纭€1闁挎稒纰嶇€垫粍绌卞┑鍡欐憼闁汇劌瀚弻鍥倷閸︻厽鍩涚紓渚囧弿缁辨瑦绂?0,0 閻犙囶棑閸嬶綁鏁嶇仦鐓庘枏闁活潿鍔嬬换姘扁偓娑欘焽濞堟垶娼诲☉妯侯唺闁轰胶澧楀畵渚€鏁?
     */
    private void resumeFromSavedProgress() {
        ScanProgressManager.ScanProgress savedProgress = ScanProgressManager.loadProgress();
        
        if (savedProgress == null) {
            if (debugMode.get()) {
                info("No saved progress file was found. Save progress before using resume mode.");
            }
            // 闁搞儳鍋ら埀顑藉亾闁告帡顣︾划鐘恒亹閹惧啿顤呭ù锝呯Ф閻ゅ棗顕ｉ埀顒佹叏?
            startFromCurrentPosition();
            return;
        }
        
        // 濡ょ姴鐭侀惁?chunkStep 闁哄嫷鍨伴幆浣圭▔閳ь剟鎳?
        int savedChunkStep = savedProgress.chunkStep;
        int currentChunkStep = chunkStep.get();
        if (savedChunkStep != currentChunkStep) {
            info("Warning: saved chunk step differs from current setting.");
            info("Saved chunk step: " + savedChunkStep);
            info("Current chunk step: " + currentChunkStep);
            info("Using saved chunk step " + savedChunkStep + " to resume.");
            // 濞戞挸鐡ㄥ鍌涚┍椤旇姤鏆悹浣稿⒔閻ゅ棙绂掗妷銉ョ埍闂佹澘绉崇换姘扁偓娑欘焽濞堟垿宕?
            chunkStep.set(savedChunkStep);
        }
        
        // 濞达綀娉曢弫銈嗙┍濠靛棛鎽犻柣銊ュ閹癸綁鎮欓惂鍝ョ濞?0,0 鐎殿喒鍋撳┑顔碱儑濞堟垹鎹勯姘辩獮闁?
        int startX = savedProgress.startX;
        int startZ = savedProgress.startZ;
        
        if (debugMode.get()) {
            info("Resuming from saved checkpoint at (" + startX + ", " + startZ + ").");
            info("Scanned segments: " + savedProgress.totalSegments);
        }
        
        // 闁诡厹鍨归ˇ鍙夋交濞戞ê顔婇柣妯垮煐閳?
        startPos = new ChunkPos(startX, startZ);
        currentDir = MapScanDirection.values()[savedProgress.currentDir];
        stepsInCurrentLength = savedProgress.stepsInCurrentLength;
        currentStepLength = savedProgress.currentStepLength;
        totalSegments = savedProgress.totalSegments;
        needsInitialRotation = false;
        
        // 閻犱緤绱曢悾鏄忋亹閹惧啿顤呴柣鈺婂枟閻栵綁鎮?
        ChunkPos targetPos = ScanProgressManager.calculateTargetChunkPos(savedProgress, chunkStep.get());
        if (targetPos != null) {
            targetChunkX = targetPos.x;
            targetChunkZ = targetPos.z;
            
            // 闁哄秮鈧啿娅欓柡鍫熺箓閹?
            if (!currentDir.isFacingDirection(mc.player.getYaw())) {
                needsInitialRotation = true;
                targetYaw = currentDir.yaw;
                isRotating = true;
                
                if (debugMode.get()) {
                    info("Calibrating view to " + currentDir.name());
                }
            } else {
                if (lockView.get()) {
                    applyRotation(currentDir.yaw);
                }
            }
            
            if (debugMode.get()) {
                info("Next target chunk: (" + targetChunkX + ", " + targetChunkZ + ")");
            }
        }
    }

    /**
     * 婵☆垪鈧磭纭€2闁挎稒纰嶉悧鎾箲椤斿吋缍忛柡宥呮搐閻ゅ嫰寮幆閭﹀悁缂佺姵顨愮槐娆愮?0,0 閻犙囶棑閸嬶綁鏁嶇仦鍓у闁硅鍠氱敮铏光偓瑙勬构缂嶅懐绱旈鐓庡唨闁规亽鍔忕换妯绘償閿旇偐绀?
     */
    private void calculateFromPositionWithZeroStart() {
        ChunkPos playerChunk = mc.player.getChunkPos();
        
        // 闁告挸绉崇悮杈╃矓瀹ュ枺浣割嚕韫囨稑鍘村ù?0,0 濞达絾绮堢拹鐔兼懚閻戞ɑ顥嬮悹渚灠缁剁偤鎯冮崟顓熷€為悹渚€缂氶幑锝夋倷?
        final int ORIGIN_X = 0;
        final int ORIGIN_Z = 0;
        
        if (debugMode.get()) {
            info("Spiral origin: (" + ORIGIN_X + ", " + ORIGIN_Z + ").");
            info("Calculating nearest spiral corner from current chunk (" + playerChunk.x + ", " + playerChunk.z + ").");
        }
        
        // 閻犱緤绱曢悾缁樼鎼粹€虫枾闁?(0,0) 闁告帗澹嗙敮铏光偓瑙勬构缂嶅懐绱旈鐐暠闁哄牃鍋撻弶鈺傚灦鐎氬嫰鎮?
        ScanProgressManager.ScanProgress calculatedProgress = ScanProgressManager.calculateProgressFromPosition(
            playerChunk.x, playerChunk.z,
            ORIGIN_X, ORIGIN_Z,
            chunkStep.get()
        );
        
        if (calculatedProgress != null) {
            // 闁瑰瓨鍔曟慨娑氭媼閿涘嫮鏆柛鎴︾細缁绘ɑ鎯旈敂鑲╃濞寸姴瀛╁〒鑸垫交閹寸姵鐣遍柟閿嬪姉閸嬶絿绱掕閻?
            if (debugMode.get()) {
                info("Calculation succeeded. Continuing from segment " + calculatedProgress.totalSegments + ".");
            }
            
            // 閻犱緤绱曢悾濠氬嫉閳ь剚娼婚幋鐐茬彄闁绘劕婀卞▓鎴犫偓鍦仱濡绢垶宕搁幇顓犲灱
            int cornerX = ORIGIN_X + calculateOffsetX(calculatedProgress.totalSegments);
            int cornerZ = ORIGIN_Z + calculateOffsetZ(calculatedProgress.totalSegments);
            
            // 婵☆偀鍋撻柡灞诲劚濞兼寮介崶褌鐒婄紒鍌濆吹閳诲吋鎯旈敂鑲╃濞戞挸绉烽埀顒€鍟冲璇差潰閿濆牏顦伴柛娆忓殩缁辨繈宕ｉ鍛Х閺夊牆鍟扮划椋庘偓鐢垫嚀閳ь剛銆嬬槐婵嬪礂娴ｇ瓔鍟?閸? 闁告牕鎼锛勬嫚椤栨碍鈻曢柨?
            int diffAbsX = Math.abs(Math.abs(playerChunk.x) - Math.abs(cornerX));
            int diffAbsZ = Math.abs(Math.abs(playerChunk.z) - Math.abs(cornerZ));
            boolean isAtLayer = diffAbsX <= 2 && diffAbsZ <= 2;
            
            if (!isAtLayer) {
                // 闁绘壕鏅涢宥嗙▔瀹ュ懏韬柛姘缁旀挳宕烽崼婵堟勾闁挎稑鏈ぐ浣虹矆閸濆嫬顤呯€垫壋鍋?
                info("Warning: the player is too far from the spiral path.");
                info("Current chunk: (" + playerChunk.x + ", " + playerChunk.z + ").");
                info("Recommended chunk: (" + cornerX + ", " + cornerZ + ").");
                info("Distance from path: X=" + diffAbsX + " Z=" + diffAbsZ + " chunks.");
                info("Move to block coordinates (" + (cornerX * 16) + ", " + (cornerZ * 16) + ") and restart scanning.");
                
                // 闁稿繑濞婂Λ鏉懳熼垾铏仴
                toggle();
                return;
            }
            
            // 闁诡厹鍨归ˇ鑼媼閿涘嫮鏆柛鎴ｆ濞堟垶娼诲☉妯侯唺
            startPos = new ChunkPos(calculatedProgress.startX, calculatedProgress.startZ);
            currentDir = MapScanDirection.values()[calculatedProgress.currentDir];
            stepsInCurrentLength = calculatedProgress.stepsInCurrentLength;
            currentStepLength = calculatedProgress.currentStepLength;
            totalSegments = calculatedProgress.totalSegments;
            needsInitialRotation = false;
            
            // 閻犱緤绱曢悾濠氭儎椤旂晫鍨奸柣?
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
                    info("Next target chunk: (" + targetChunkX + ", " + targetChunkZ + ")");
                }
            }
        } else {
            // 闁哄啰濮电涵鍓佹媼閿涘嫮鏆柨娑樺缁娀宕㈤悢鍝勪化闂佹彃绉甸弻濠傤嚕閳ь剚鎱?
            if (debugMode.get()) {
                info("Could not calculate progress. Restarting from origin (" + ORIGIN_X + ", " + ORIGIN_Z + ").");
            }
            startNewScanFromOrigin(ORIGIN_X, ORIGIN_Z);
        }
    }

    /**
     * 婵☆垪鈧磭纭€3闁挎稒鐭划鐘恒亹閹惧啿顤呭ù锝呯Ф閻ゅ棗顕ｉ埀顒佹叏鐎ｎ偄顥囬柟璇查獜缁辨瑦绂掗妷銉хЪ闁告挸绉崇紞鍛磾椤旀槒绀嬮悹褔顥撻崑锝夋晬?
     */
    private void startFromCurrentPosition() {
        ChunkPos playerChunk = mc.player.getChunkPos();
        
        if (debugMode.get()) {
            info("Starting a new scan from current chunk (" + playerChunk.x + ", " + playerChunk.z + ").");
        }
        
        startNewScanFromPosition(playerChunk);
    }

    /**
     * 濞寸姴瀛╃€垫氨鈧鑹剧敮顐︽倷閻熸壆纾诲┑顔碱儐閺屽﹪鎯冮崟顒€顥囬柟璇查獜缁辨瑧鎸ф搴′化闁搞儱鎼悾楣冩晬鐏炶偐鐟濆ù鐘查缂嶅宕滃鍕Т缂傚喚鍣ｉ崳鍝ョ磾椤曞棛绀?
     * @param originX 閻犙囶棑閸嬶綁宕犻崫鍕仴 X
     * @param originZ 閻犙囶棑閸嬶綁宕犻崫鍕仴 Z
     */
    private void startNewScanFromOrigin(int originX, int originZ) {
        startPos = new ChunkPos(originX, originZ);
        info("Scan started at chunk (" + originX + ", " + originZ + ").");
    
        // 闂佹彃绉堕悿鍡涙偐閼哥鍋?
        currentDir = MapScanDirection.EAST;
        stepsInCurrentLength = 0;
        currentStepLength = 1;
        totalSegments = 0;
        isRotating = false;
        needsInitialRotation = false;
    
        // 閻犱緤绱曢悾鑽ょ箔椤戣法顏卞☉鎿冧簽濞蹭即寮介崶鈺佷化
        updateTarget();
    
        // 閻犱礁澧介悿鍡涘礆濠靛棭娼楅柡鍫熺箓閹粓鏁嶉崼婊咁偨闁?
        targetYaw = MapScanDirection.EAST.yaw;
        applyRotation(targetYaw);
    
        // 濞ｅ洦绻傞悺銊ф導妞嬪骸浠柛褎鍔栭悥?
        saveInitialProgress();
    
        if (debugMode.get()) {
            info("Next target chunk: (" + targetChunkX + ", " + targetChunkZ + ")");
        }
    }

    /**
     * 濞寸姴瀛╃€垫氨鈧鐭紞鍛磾椤旇偐纾诲┑顔碱儐閺屽﹪鎯冮崟顒€顥囬柟?
     * @param startPos 閻犙冨槻椤劙宕犻崫鍕仴闁秆勫姈閻?
     */
    private void startNewScanFromPosition(ChunkPos startPos) {
        // 閻犱焦婢樼紞宥囨導瀹勯偊娼楅柛鏍ф惈濞?
        this.startPos = startPos;
        info("Scan started at chunk (" + startPos.x + ", " + startPos.z + ").");
    
        // 闂佹彃绉堕悿鍡涙偐閼哥鍋?
        currentDir = MapScanDirection.EAST;
        stepsInCurrentLength = 0;
        currentStepLength = 1;
        totalSegments = 0;
        isRotating = false;
        needsInitialRotation = false;
    
        // 閻犱緤绱曢悾鑽ょ箔椤戣法顏卞☉鎿冧簽濞蹭即寮介崶鈺佷化
        updateTarget();
    
        // 閻犱礁澧介悿鍡涘礆濠靛棭娼楅柡鍫熺箓閹粓鏁嶉崼婊咁偨闁?
        targetYaw = MapScanDirection.EAST.yaw;
        applyRotation(targetYaw);
    
        // 缂佹柨顑呭畵鍡樼┍濠靛棛鎽犻悹褔顥撻崑锝夊锤閹邦厾鍨奸柛鎺斿閺嬪啯绂掔拋鍦濞寸姰鍎扮粚鍫曞触鎼达絿鏁剧€规洍鏅滅花婵嬪箣閺嶎厼娅㈤柛姘煎灡濡炲倿鎳楅懞銉у闁硅鍠栧妤呭冀閸ャ劌鑵圭紒鐘愁殙缁绘ɑ鎯?
        saveInitialProgress();
    
        if (debugMode.get()) {
            info("Next target chunk: (" + targetChunkX + ", " + targetChunkZ + ")");
        }
    }

    /**
     * 濞ｅ洦绻傞悺銊╁礆濠靛棭娼楅弶鈺傜☉鐎规娊鏁嶉崼婊呯煂閻犱焦婢樼紞宥囨導妞嬪骸浠柨?
     */
    private void saveInitialProgress() {
        if (startPos != null) {
            ScanProgressManager.ScanProgress progress = new ScanProgressManager.ScanProgress(
                startPos.x, startPos.z,
                totalSegments,
                currentDir.ordinal(),
                stepsInCurrentLength,
                currentStepLength,
                chunkStep.get()  // 濞ｅ洦绻傞悺銊ㄣ亹閹惧啿顤呴柣?chunkStep
            );
            ScanProgressManager.saveProgress(progress);
        }
    }

    @Override
    public void onDeactivate() {
        releaseForward();
        info("Scan ended after " + totalSegments + " chunk segments.");
        
        // 濞ｅ洦绻傞悺銊︽交濞戞ê顔?
        if (startPos != null) {
            ScanProgressManager.ScanProgress progress = new ScanProgressManager.ScanProgress(
                startPos.x, startPos.z,
                totalSegments,
                currentDir.ordinal(),
                stepsInCurrentLength,
                currentStepLength,
                chunkStep.get()  // 濞ｅ洦绻傞悺銊ㄣ亹閹惧啿顤呴柣?chunkStep
            );
            ScanProgressManager.saveProgress(progress);
            
            if (debugMode.get()) {
                info("Progress saved.");
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

        // 婵☆偀鍋撻柡灞诲劜濞撹埖寰勮鐏忣垶宕稿Δ鍛€欓柛?
        if (maxChunks.get() > 0 && totalSegments >= maxChunks.get()) {
            info("Maximum chunk count reached. Scan complete.");
            toggle();
            return;
        }

        // 濠碘€冲€归悘澶愭閳ь剛鎲版担绋跨仴濠殿喖顑嗗Λ鍡樻姜椤掍胶澧￠柛鎴濇４缁辨瑩骞侀姀鐙€妲绘俊顖椻偓宕囩闁?
        if (needsInitialRotation && isRotating) {
            smoothRotation();
            releaseForward();
            if (isRotating) return; // 闁哄啫顑堝ù鍡涘嫉椤忓嫮鏆氶柟瀛樺姧缁辨繄绮垫径濠勭濞戞挸顑勭粩?tick
            // 闁哄啫顑堝ù鍡欌偓鐟版湰閸ㄦ岸鏁嶅畝鈧幋椋庣磼椤撶噥鍔€閻㈩垰鎲＄粊锔剧矙?
        }

        // 濠碘€冲€归悘澶婎潰閿濆懏韬柡鍐儓濞村棝鏁嶇仦鎯р挃閻炴稑鑻柦鈺侇煥閹寸偞顥嬮弶?
        if (isRotating) {
            smoothRotation();
            releaseForward();
            if (isRotating) return;
        }

        // 濠碘€冲€归悘澶愬触椤栨粍鏆忛梺澶哥閻ｅ墽鎲撮崱姘兼健闁挎稑鏈€垫梻绱掗鐐靛畨闁活潿鍔岀紞瀣礈瀹ュ棙鐓欓柛?
        if (lockView.get() && !isRotating && !needsInitialRotation) {
            applyRotation(currentDir.yaw);
        }

        if (waitForChunks.get() && !isScanAreaLoaded()) {
            releaseForward();
            return;
        }

        handleAutoWalk();

        // 闁兼儳鍢茶ぐ鍥亹閹惧啿顤呭ù锝呯Ф閻ゅ棝鏁嶉崼婵嗛殬闁秆勵殔濞兼寮介崶椋庣
        ChunkPos currentChunk = mc.player.getChunkPos();
        recordContainerChunkIfNeeded(currentChunk);

        // 婵☆偀鍋撻柡灞诲劜濡叉悂宕ラ敃鈧崺灞炬綇閻愵剙鐏楅悺鎺戞嚀缁诲啴鎯勯鐣屽灱闁告牕鎼?
        if (hasReachedTarget(currentChunk)) {
            // 閻犱緤绱曢悾缁樼▔鐎ｂ晝顏卞☉鎿冧簼閺岀喖宕ラ幋顖滅妤犵偠娉涢崹浠嬪棘椤撶喐笑闁告熬绠撳〒鍓佹啺娴ｆ悂鎸繝濠冨灦濡棙娼?
            boolean needSmoothRotation = turnToNextDirection();

            // 闁哄洤鐡ㄩ弻濠囨儎椤旂晫鍨奸柛褎鍔栭悥?
            updateTarget();

            // 闁告瑯浜濆﹢渚€宕烽妸鈺備粯閻熸洑鐒﹀鍌炲箥瀹ュ懏鍎欓柛鏂诲妼闁解晛顭ㄩ幋鐐搭棆閺?
            if (needSmoothRotation) {
                targetYaw = currentDir.yaw;
                isRotating = true;
            }

            if (debugMode.get()) {
                info("Turned to " + currentDir.name() + ". Next target: (" + targetChunkX + ", " + targetChunkZ + ").");
            }
        }
    }

    @EventHandler
    private void handleWaveRender(Render3DEvent event) {
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
            if (count >= containerThreshold.get()) break;
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
        ChatUtils.warning("(highlight)(bold)Base found! (default)Chunk: (highlight)%d, %d(default) | Position: (highlight)%d, %d, %d(default) | Containers: (highlight)%d+(default)",
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
        String line = "%s | chunk=(%d,%d) | first-container=(%d,%d,%d) | player=(%d,%d,%d) | count>=%d%n".formatted(
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

            String name = xaeroWaypointPrefix.get() + xaeroWaypointSuffix.get();
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
     * 闁告帇鍊栭弻鍥及椤栨碍鍎婄€瑰憡褰冮崺灞炬綇閻愵剙鐏楅悺鎺戞嚀缁诲啴鎯勯鐣屽灱闁告牕鎼?
     */
    private boolean hasReachedTarget(ChunkPos currentChunk) {
        // 濞戞挻妲掗々锔芥姜鏉堝墽绐楁俊顐熷亾闁哄被鍎插Σ鎼佸触閿曗偓閸╁本娼忛悙顒€鐏楅悺鎺戞嚀缁诲啴鎯勯鐣屽灱
        boolean mainAxisReached = switch (currentDir) {
            case EAST -> currentChunk.x >= targetChunkX;
            case WEST -> currentChunk.x <= targetChunkX;
            case NORTH -> currentChunk.z <= targetChunkZ;
            case SOUTH -> currentChunk.z >= targetChunkZ;
        };

        // 濠碘€冲€归悘澶嬬▔閺勫浚娲ｉ弶鐐茬摠濠€顓㈠礆閹峰本褰ч柨娑樼灱濞插潡骞掗妷銊х闁?
        if (!mainAxisReached) return false;

        // 婵炲枴銈庢矗閺夌偠鎻槐鏉课涢埀顒勫蓟閵夛附笑闁告熬绠戞禍鍝ョ矉娴兼瑧绀勯柛蹇庢祰椤斿繐宕?闁告牕鎼锟犳儍閸曨噮鍤栫€瑰壊鍣槐?
        return switch (currentDir) {
            case EAST, WEST -> Math.abs(currentChunk.z - targetChunkZ) <= 1;
            case NORTH, SOUTH -> Math.abs(currentChunk.x - targetChunkX) <= 1;
        };
    }

    /**
     * 闁哄洤鐡ㄩ弻濠囨儎椤旂晫鍨奸柛鏍ф惈濞硷繝宕搁幇顓犲灱
     */
    private void updateTarget() {
        // 閻犱緤绱曢悾缁樼鎼淬倖宕抽柣鎰嚀缁辨垶鎱ㄧ€ｎ亜鐓傜憸鐗堟尭婢х姴鈻撻悽鍨暠缂侀硸鍨宠ⅶ闁稿绻掍簺
        int currentX = startPos.x;
        int currentZ = startPos.z;

        MapScanDirection tempDir = MapScanDirection.EAST;
        int tempStepLen = 1;
        int tempStepsInLen = 0;

        // 缂侀硸鍨版慨鐐哄箥閳ь剟寮垫径濠傚殥閻庣懓鏈崹姘舵儍閸曨剦鍞?
        for (int i = 0; i < totalSegments; i++) {
            // 婵縿鍎甸弳杈ㄦ償韫囨挸鐏欓柨?, 1, 2, 2, 3, 3... 闁挎稑鐗撳〒鍓佹啺娴ｉ顔掑ù?chunkStep闁?
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

        // 鐟滅増鎸告晶鐘诲棘閻熺増鍊婚柣銊ュ濞蹭即寮?= 鐎瑰憡褰冮悾顒勫箣閹邦喗鐣辩紒槌栧灣琚уù锝呯Ф閻?+ 鐟滅増鎸告晶鐘测枔闂堟稓瀹夌紒澶庮嚙婵晠鎯冮崟顔剧崺缂?
        int currentDist = currentStepLength * chunkStep.get();  // 闂傚洠鍋撻悷鏇氭缁犵粯绂?chunkStep
        targetChunkX = currentX + tempDir.dx * currentDist;
        targetChunkZ = currentZ + tempDir.dz * currentDist;
    }

    /**
     * 閺夌儐鍓欓幃婊勭▔鐎ｂ晝顏卞☉鎿冧簼閺岀喖宕?
     * @return 闁哄嫷鍨伴幆渚€妫侀埀顒傛啺娴ｅ憡鍊电紓渚囧幖闁解晛顭ㄩ幋鐐搭棆閺?
     */
    private boolean turnToNextDirection() {
        // 闁哄倻鎳撻幃婊冾嚗椤忓棗绠氶柨娑欘儛AST -> NORTH -> WEST -> SOUTH -> EAST
        currentDir = currentDir.getNext();

        // 闁哄洤鐡ㄩ弻濠傤潰閵夛附娈堕悹浣插墲閺?
        stepsInCurrentLength++;
        totalSegments++;

        // 婵絽绻嬬悮鍗炩枎閿涘嫭绁查柛姘湰椤掔偤姊归崹顔藉€甸柨娑樻湰椤掔偤姊归崹顕呮澔闁?闁?,1,2,2,3,3,4,4...闁?
        if (stepsInCurrentLength >= 2) {
            currentStepLength++;
            stepsInCurrentLength = 0;
        }

        // 濠碘€冲€归悘澶愬触椤栨粍鏆忛梺澶哥閻ｅ墽鎲撮崱姘兼健闁挎稑鐬奸悵娑㈠础閸愯尙瀹夐柣顫妽閺屽﹪寮悷鐗堝€婚柣銊ュ濡棙娼?
        if (lockView.get()) {
            applyRotation(currentDir.yaw);
            return false;  // 濞戞挸绉瑰〒鍓佹啺娴ｅ憡鍊电紓渚囧幖闁解晛顭ㄩ幋鐐搭棆閺?
        }
        
        return true;  // 闂傚洠鍋撻悷鏇氱閹绱掗鐐烘尙婵犲﹥鍨跺Λ鍡樻姜?
    }

    /**
     * 妤犵偠娅曠划锕傚籍鐎ｎ厽绁悷娆忔椤?
     */
    private void smoothRotation() {
        if (mc.player == null) {
            isRotating = false;
            return;
        }

        float currentYaw = mc.player.getYaw();
        float diff = targetYaw - currentYaw;

        // 濠㈣泛瀚幃濠勬喆閹烘垵顔婇悹鎭掑姀缁?-180/180 闁汇劌瀚板Λ鑸碉紣?
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;

        // 闁哄啫顑堝ù鍡涙焻閻斿嘲顔婇柨娑樼墕鐎?tick闁?
        float rotationSpeed = 15f;

        if (Math.abs(diff) < rotationSpeed) {
            // 闁规亽鍎寸换搴ㄦ儎椤旂晫鍨奸柨娑樼灱濞插潡骞掗妷顭戝晭缂?
            applyRotation(targetYaw);

            if (isRotating && debugMode.get()) {
                info("Rotation complete.");
            }
            isRotating = false;
        } else {
            // 闂侇偅鍔栭鐐哄籍鐎ｎ厽绁?
            applyRotation(currentYaw + Math.signum(diff) * rotationSpeed);
        }
    }

    /**
     * 閹煎瓨姊婚弫銈夊籍鐎ｎ厽绁柨娑樼墕閹捇寮幆閭﹀晭缂?yaw, headYaw, bodyYaw闁?
     */
    private void applyRotation(float yaw) {
        mc.player.setYaw(yaw);
        mc.player.headYaw = yaw;
        mc.player.bodyYaw = yaw;
    }

    /**
     * 閻犱緤绱曢悾缁樼鎼淬倖宕抽柣鎰嚀缁辨垶鎱ㄧ€ｎ剛鐥呴弶鈺佹处鐎垫氨鈧纰嶉宀勫极閺夋寧鍊甸柣?X 闁稿绻掍簺闂?
     */
    private int calculateOffsetX(int segments) {
        int x = 0;
        MapScanDirection tempDir = MapScanDirection.EAST;
        int tempStepLen = 1;
        int tempStepsInLen = 0;

        for (int i = 0; i < segments; i++) {
            x += tempDir.dx * tempStepLen * chunkStep.get();  // 闂傚洠鍋撻悷鏇氭缁犵粯绂?chunkStep

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
     * 閻犱緤绱曢悾缁樼鎼淬倖宕抽柣鎰嚀缁辨垶鎱ㄧ€ｎ剛鐥呴弶鈺佹处鐎垫氨鈧纰嶉宀勫极閺夋寧鍊甸柣?Z 闁稿绻掍簺闂?
     */
    private int calculateOffsetZ(int segments) {
        int z = 0;
        MapScanDirection tempDir = MapScanDirection.EAST;
        int tempStepLen = 1;
        int tempStepsInLen = 0;

        for (int i = 0; i < segments; i++) {
            z += tempDir.dz * tempStepLen * chunkStep.get();  // 闂傚洠鍋撻悷鏇氭缁犵粯绂?chunkStep

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
        return "" + totalSegments + " | " + currentDir.name();
    }
}


