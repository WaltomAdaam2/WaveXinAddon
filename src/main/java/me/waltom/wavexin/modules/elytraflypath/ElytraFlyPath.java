package me.waltom.wavexin.modules.elytraflypath;

import me.waltom.wavexin.events.TravelEvent;
import me.waltom.wavexin.events.MoveEvent;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.core.WaveXinDebugLog;
import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.gui.TargetCoordinateSetting;
import me.waltom.wavexin.i18n.WaveXinI18n;
import me.waltom.wavexin.modules.NavigationModuleControl;
import me.waltom.wavexin.modules.basefinder.BaseFinder;
import me.waltom.wavexin.modules.basefinder.XaeroWaypointBridge;
import me.waltom.wavexin.modules.basefinder.XaeroWaypointColorSetting;
import me.waltom.wavexin.modules.elytrafly.ElytraFlightLogic;
import me.waltom.wavexin.modules.elytrafly.ElytraSpeedRamp;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import java.util.concurrent.ThreadLocalRandom;

public class ElytraFlyPath extends WaveXinModule {
    private final WaveXinDebugLog debugLog = new WaveXinDebugLog("ElytraFlyPath");
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int MAX_TARGET_COORDINATE = 30000000;
    private boolean activationRejected;
    private boolean debugMode;



    
    private final SettingGroup sgTarget = settings.createGroup("Target Coordinates");
    private final SettingGroup sgFlight = settings.createGroup("Flight Settings");
    private final SettingGroup sgSpeedAcceleration = settings.createGroup("Speed Acceleration");
    private final SettingGroup sgXaeroWaypoint = settings.createGroup("Xaero Waypoint");
    private boolean isArrive = false;
    private final ElytraSpeedRamp speedRamp = new ElytraSpeedRamp();
    private final XaeroWaypointBridge xaeroWaypointBridge = new XaeroWaypointBridge();
    private XaeroWaypointBridge.WaypointHandle temporaryWaypoint;
    private boolean normalizingMaxSpeed;

    
    
    public final Setting<Integer> globalX = sgTarget.add(new TargetCoordinateSetting.Builder()
        .name("Target X")
        .description("Target X")
        .defaultValue(0)
        .min(-MAX_TARGET_COORDINATE)
        .sliderMin(-MAX_TARGET_COORDINATE)
        .max(MAX_TARGET_COORDINATE)
        .sliderMax(MAX_TARGET_COORDINATE)
        .build()
    );

    
    public final Setting<Integer> globalZ = sgTarget.add(new TargetCoordinateSetting.Builder()
        .name("Target Z")
        .description("Target Z")
        .defaultValue(0)
        .min(-MAX_TARGET_COORDINATE)
        .sliderMin(-MAX_TARGET_COORDINATE)
        .max(MAX_TARGET_COORDINATE)
        .sliderMax(MAX_TARGET_COORDINATE)
        .build()
    );

    
    
    public final Setting<Boolean> autoStop = sgFlight.add(new BoolSetting.Builder()
        .name("Stop in Unloaded Chunks")
        .description("Stops flight movement when the current chunk is unloaded")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> netherPosCalculation = sgFlight.add(new BoolSetting.Builder()
        .name("Nether Pos Calculation")
        .description("Divides Target X and Target Z by 8 before pathing, for Overworld-to-Nether coordinate conversion")
        .defaultValue(false)
        .build()
    );

    
    public final Setting<Double> speed = sgFlight.add(new DoubleSetting.Builder()
        .name("Flight Speed")
        .description("Horizontal flight speed before acceleration")
        .defaultValue(1.8)
        .min(0.1)
        .sliderMin(0.1)
        .max(20)
        .sliderMax(20)
        .onChanged(this::onInitialSpeedChanged)
        .build()
    );

    public final Setting<Boolean> speedAcceleration = sgSpeedAcceleration.add(new BoolSetting.Builder()
        .name("Enable")
        .description("Increases flight speed while gliding")
        .defaultValue(false)
        .onChanged(value -> speedRamp.reset())
        .build()
    );

    public final Setting<Double> speedIncreasePerSecond = sgSpeedAcceleration.add(new DoubleSetting.Builder()
        .name("Speed Increase Per Second")
        .description("Speed added for each second of active gliding")
        .defaultValue(0.1)
        .min(0.0)
        .sliderMin(0.0)
        .max(20.0)
        .sliderMax(2.0)
        .visible(speedAcceleration::get)
        .build()
    );

    public final Setting<Double> maxSpeed = sgSpeedAcceleration.add(new DoubleSetting.Builder()
        .name("Max Speed")
        .description("Maximum accelerated flight speed; never lower than Initial Speed")
        .defaultValue(1.8)
        .min(0.1)
        .sliderMin(0.1)
        .max(20.0)
        .sliderMax(20.0)
        .onChanged(this::onMaxSpeedChanged)
        .visible(speedAcceleration::get)
        .build()
    );

    public final Setting<Boolean> resetAfterLagback = sgSpeedAcceleration.add(new BoolSetting.Builder()
        .name("Reset After Lagback")
        .description("Resets to Initial Speed and holds it for five seconds after a server position correction")
        .defaultValue(true)
        .visible(speedAcceleration::get)
        .build()
    );

    public final Setting<Boolean> createXaeroWaypoint = sgXaeroWaypoint.add(new BoolSetting.Builder()
        .name("Create Xaero Waypoint")
        .description("Creates a temporary Xaero waypoint at the active path target")
        .defaultValue(false)
        .build()
    );

    public final Setting<BaseFinder.XaeroWaypointColor> xaeroWaypointColor = sgXaeroWaypoint.add(new XaeroWaypointColorSetting.Builder()
        .name("Waypoint Color")
        .description("Xaero waypoint color, or a random supported color")
        .defaultValue(BaseFinder.XaeroWaypointColor.RANDOM)
        .visible(createXaeroWaypoint::get)
        .build()
    );

    
    public final Setting<Boolean> autoQuitServer = sgFlight.add(new BoolSetting.Builder()
        .name("Auto Disconnect on Arrival")
        .description("Disconnects after arriving at the target")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> autoStopOnArrival = sgFlight.add(new BoolSetting.Builder()
        .name("Stop on Arrival")
        .description("Disables Elytra Fly Path after arriving at the target")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> autoTakeoff = sgFlight.add(new BoolSetting.Builder()
        .name("Air Takeoff")
        .description("Air Takeoff")
        .defaultValue(true)
        .build()
    );

    
    
    
    public final Setting<Double> arrivalDistance2D = sgFlight.add(new DoubleSetting.Builder()
        .name("Arrival Distance")
        .description("Distance from the target required to count as arrived")
        .defaultValue(2.0)
        .min(1)
        .sliderMin(0.1)
        .max(Integer.MAX_VALUE)
        .sliderMax(256)
        .build()
    );


    

    


    public ElytraFlyPath() {
        super(WaveXinAddon.CATEGORY, "elytra-fly-path", "Automatic elytra path flight");
    }

    public void setDebugMode(boolean enabled) {
        debugMode = enabled;
        if (enabled && isActive()) debugLog.open(true, mc.runDirectory.toPath());
        else if (!enabled) debugLog.close();
    }

    



    @Override
    public void onActivate() {
        debugLog.open(debugMode, mc.runDirectory.toPath());
        if (debugMode) debugLog.info("activation", "target", getTargetX() + "," + getTargetZ());
        if (NavigationModuleControl.reportConflictingActivation(this)) {
            activationRejected = true;
            toggle();
            return;
        }
        activationRejected = false;
        
        if (mc.player == null || mc.world == null || !hasWorkingElytra()) {
            toggle();
            return;
        }

        suppressMovementInput();
        speedRamp.reset();

        
        if (!isSafeFlightHeight()) {
            warningKey("error.wavexin.safe_flight_height", "Recommended to use above each dimension height limit: Nether (Y > 128), Overworld (Y > 320), End (Y > 256)");
        }


        if (!mc.player.isCreative()) mc.player.getAbilities().allowFlying = false;
        mc.player.getAbilities().flying = false;

        if (autoTakeoff.get() && !mc.player.isGliding()) {
            requestElytraGlide(mc.player);
        }

        createTemporaryWaypoint();

        
        infoKey("message.wavexin.elytra_fly_path.started", "Started pathing to X=%d, Z=%d", getTargetX(), getTargetZ());
    }

    



    @Override
    public void onDeactivate() {
        if (debugMode) debugLog.info("deactivation", "arrived", isArrive);
        debugLog.close();
        if (activationRejected) {
            activationRejected = false;
            return;
        }
        removeTemporaryWaypoint();
        isArrive = false;
        speedRamp.reset();

        
        if (mc.player != null) {
            if (!mc.player.isCreative()) mc.player.getAbilities().allowFlying = false;
            mc.player.getAbilities().flying = false;
        }
        restoreMovementInput();
    }

    



    @EventHandler
    public void handleWavePlayerMove(MoveEvent event) {
        if (mc.player == null || mc.world == null) return;

        
        if (mc.player.isGliding()) {
            
            
            ChunkPos chunkPos = mc.player.getChunkPos();

            
            if (autoStop.get()) {
                
                if (!mc.world.getChunkManager().isChunkLoaded(chunkPos.x, chunkPos.z)) {
                    
                    event.setX(0);
                    event.setY(0);
                    event.setZ(0);
                }
            }
        }

    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTick(TickEvent.Pre event) {
        
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        suppressMovementInput();

        if (isArrive) {
            finishArrival();
            return;
        }

        
        if (autoTakeoff.get() && !mc.player.isGliding()) {
            requestElytraGlide(mc.player);
        }

        speedRamp.tick(mc.player.isGliding() && hasWorkingElytra());
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            speedRamp.onLagback(resetAfterLagback.get());
        }
    }

    private void suppressMovementInput() {
        NavigationModuleControl.suppressMovementInput();
    }

    private void restoreMovementInput() {
        NavigationModuleControl.restoreMovementInput();
    }


    



    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(TravelEvent event) {
        
        if (mc.player == null || mc.world == null || event.isPost()) {
            return;
        }
        suppressMovementInput();
        if (!mc.player.isGliding() || !hasWorkingElytra()) return;

        
        int currentTargetX = getTargetX();
        int currentTargetZ = getTargetZ();

        
        double deltaX = currentTargetX - mc.player.getX();
        double deltaZ = currentTargetZ - mc.player.getZ();

        
        double distance2D = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        if (distance2D <= arrivalDistance2D.get()) {
            markArrived(event);
            return;
        }

        
        double flightSpeed = Math.min(currentFlightSpeed(), distance2D - arrivalDistance2D.get());
        mc.player.setVelocity(
            deltaX / distance2D * flightSpeed * 0.9800000190734863D,
            0,
            deltaZ / distance2D * flightSpeed * 0.9900000095367432D
        );

        
        event.cancel();
        mc.player.move(MovementType.SELF, mc.player.getVelocity());
    }

    

    


    private void markArrived(TravelEvent event) {
        isArrive = true;
        stopMotion();
        event.cancel();
        finishArrival();
    }

    private void finishArrival() {
        boolean shouldDisconnect = autoQuitServer.get();
        boolean shouldStop = autoStopOnArrival.get();

        stopMotion();
        isArrive = false;

        if (shouldStop && isActive()) {
            toggle();
            sendToggledMsg();
        }

        if (shouldDisconnect && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(WaveXinI18n.text("disconnect.wavexin.elytra_fly_path.arrived", "Auto quit after arriving at target"));
        }
    }

    private void stopMotion() {
        if (mc.player == null) return;
        mc.player.setVelocity(Vec3d.ZERO);
    }

    private boolean hasWorkingElytra() {
        
        return isUsableElytra(mc.player.getEquippedStack(EquipmentSlot.CHEST));
    }

    private int getTargetX() {
        return ElytraFlightLogic.targetCoordinate(globalX.get(), netherPosCalculation.get());
    }

    private int getTargetZ() {
        return ElytraFlightLogic.targetCoordinate(globalZ.get(), netherPosCalculation.get());
    }

    private double currentFlightSpeed() {
        return speedRamp.speed(speedAcceleration.get(), speed.get(), speedIncreasePerSecond.get(), maxSpeed.get());
    }

    private void onInitialSpeedChanged(double value) {
        if (maxSpeed != null && maxSpeed.get() < value) maxSpeed.set(value);
        speedRamp.reset();
    }

    private void onMaxSpeedChanged(double value) {
        if (normalizingMaxSpeed || value >= speed.get()) return;
        normalizingMaxSpeed = true;
        maxSpeed.set(speed.get());
        normalizingMaxSpeed = false;
    }

    private void createTemporaryWaypoint() {
        removeTemporaryWaypoint();
        if (temporaryWaypoint != null) return;
        if (!createXaeroWaypoint.get() || mc.player == null) return;
        if (!ElytraFlightLogic.shouldCreateWaypoint(createXaeroWaypoint.get(), xaeroWaypointBridge.isAvailable())) {
            warningKey("warning.wavexin.elytra_fly_path.xaero_unavailable", "Xaero waypoint was not created: %s", xaeroWaypointBridge.unavailableReason());
            return;
        }

        int colorId = xaeroWaypointColor.get() == BaseFinder.XaeroWaypointColor.RANDOM
            ? ThreadLocalRandom.current().nextInt(16)
            : xaeroWaypointColor.get().colorId();
        XaeroWaypointBridge.Result result = xaeroWaypointBridge.createTemporary(
            new BlockPos(getTargetX(), mc.player.getBlockY(), getTargetZ()),
            "Elytra Path",
            "EP",
            colorId
        );
        if (result.created()) {
            temporaryWaypoint = result.handle();
        } else {
            warningKey("warning.wavexin.elytra_fly_path.xaero_unavailable", "Xaero waypoint was not created: %s", result.detail());
        }
    }

    private void removeTemporaryWaypoint() {
        XaeroWaypointBridge.WaypointHandle waypoint = temporaryWaypoint;
        if (waypoint == null) return;
        XaeroWaypointBridge.Result result = xaeroWaypointBridge.remove(waypoint);
        if (result.removed()) temporaryWaypoint = null;
        else WaveXinAddon.LOG.warn("Temporary flight waypoint cleanup failed: {}", result.detail());
    }

    






    private boolean isSafeFlightHeight() {
        if (mc.player == null || mc.world == null) return false;

        double playerY = mc.player.getY();
        String dimensionName = mc.world.getRegistryKey().getValue().toString();

        switch (dimensionName) {
            case "minecraft:the_nether":
                
                return playerY > 128;
            case "minecraft:overworld":
                
                return playerY > 320;
            case "minecraft:the_end":
                
                return playerY > 256;
            default:
                return false;
        }
    }

    



    public static boolean requestElytraGlide(ClientPlayerEntity player) {
        if (canStartElytraGlide(player) && beginGlidingIfSafe(player)) {
            
            player.networkHandler.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            return true;
        }
        return false;
    }

    



    public static boolean canStartElytraGlide(ClientPlayerEntity player) {
        ItemStack itemStack = player.getEquippedStack(EquipmentSlot.CHEST);
        return (!player.getAbilities().flying &&    
            !player.hasVehicle() &&                 
            !player.isClimbing() &&                 
            itemStack.isOf(Items.ELYTRA) &&         
            isUsableElytra(itemStack));        
    }

    



    private static boolean beginGlidingIfSafe(ClientPlayerEntity player) {
        if (!player.isTouchingWater() && !player.hasStatusEffect(StatusEffects.LEVITATION)) {
            ItemStack itemStack = player.getEquippedStack(EquipmentSlot.CHEST);
            if (isUsableElytra(itemStack)) {
                player.startGliding(); 
                return true;
            }
        }
        return false;
    }


    

    


    private static boolean isUsableElytra(ItemStack stack) {
        return stack.isOf(Items.ELYTRA) && stack.getDamage() < stack.getMaxDamage() - 1;
    }




}
