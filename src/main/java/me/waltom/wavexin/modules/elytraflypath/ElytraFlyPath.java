package me.waltom.wavexin.modules.elytraflypath;

import me.waltom.wavexin.events.TravelEvent;
import me.waltom.wavexin.events.MoveEvent;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.gui.TargetCoordinateInput;
import me.waltom.wavexin.i18n.WaveXinI18n;
import me.waltom.wavexin.modules.basefinder.BaseFinder;
import me.waltom.wavexin.modules.basefinder.XaeroWaypointBridge;
import me.waltom.wavexin.modules.basefinder.XaeroWaypointColorSetting;
import me.waltom.wavexin.modules.elytrafly.ElytraFlightLogic;
import me.waltom.wavexin.modules.elytrafly.ElytraSpeedRamp;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import java.util.function.Consumer;
import java.util.concurrent.ThreadLocalRandom;

public class ElytraFlyPath extends WaveXinModule {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int MAX_TARGET_COORDINATE = 30000000;

    static {
        SettingsWidgetFactory.registerCustomFactory(TargetCoordinateSetting.class, theme -> (table, setting) -> {
            TargetCoordinateSetting coordinate = (TargetCoordinateSetting) setting;
            WIntEdit edit = table.add(theme.intEdit(coordinate.get(), coordinate.min, coordinate.max, coordinate.sliderMin, coordinate.sliderMax, coordinate.noSlider)).expandX().widget();
            ((TargetCoordinateInput) edit).wavexin$setTargetCoordinateInput(true);

            edit.action = () -> {
                if (!coordinate.set(edit.get())) edit.set(coordinate.get());
            };

            var reset = table.add(theme.button(GuiRenderer.RESET)).widget();
            reset.action = () -> {
                coordinate.reset();
                edit.set(coordinate.get());
            };
            reset.tooltip = WaveXinI18n.tr("tooltip.wavexin.common.reset", "Reset");
        });
    }

    
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
        .name("Initial Speed")
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


    
    private BlockPos target;                

    


    public ElytraFlyPath() {
        super(WaveXinAddon.CATEGORY, "elytra-fly-path", "Automatic elytra path flight");
    }

    



    @Override
    public void onActivate() {
        
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
        removeTemporaryWaypoint();
        target = null;
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
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        if (mc.player != null && mc.player.input != null) mc.player.input.tick();
    }

    private void restoreMovementInput() {
        if (mc.options == null) return;
        KeyBinding.updatePressedStates();
        if (mc.player != null && mc.player.input != null) mc.player.input.tick();
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
        target = new BlockPos(currentTargetX, 0, currentTargetZ);

        
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        
        Vec3d targetPos = new Vec3d(currentTargetX, playerPos.y, currentTargetZ);
        
        double deltaX = targetPos.x - playerPos.x;
        double deltaZ = targetPos.z - playerPos.z;

        
        double distance2D = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        if (distance2D <= arrivalDistance2D.get()) {
            markArrived(event);
            return;
        }

        
        Vec3d direction = new Vec3d(deltaX, 0, deltaZ).normalize();
        double flightSpeed = Math.min(currentFlightSpeed(), distance2D - arrivalDistance2D.get());
        setX(direction.x * flightSpeed);
        setY(0);
        setZ(direction.z * flightSpeed);

        
        setY(getY() * 0.9900000095367432D);
        setX(getX() * 0.9800000190734863D);
        setZ(getZ() * 0.9900000095367432D);

        
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
        if (!createXaeroWaypoint.get() || mc.player == null) return;
        if (!ElytraFlightLogic.shouldCreateWaypoint(createXaeroWaypoint.get(), xaeroWaypointBridge.isAvailable())) {
            warningKey("warning.wavexin.elytra_fly_path.xaero_unavailable", "Xaero waypoint was not created: %s", xaeroWaypointBridge.unavailableReason());
            return;
        }

        int colorId = xaeroWaypointColor.get() == BaseFinder.XaeroWaypointColor.RANDOM
            ? ThreadLocalRandom.current().nextInt(16)
            : xaeroWaypointColor.get().colorId();
        XaeroWaypointBridge.Result result = xaeroWaypointBridge.create(
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
        temporaryWaypoint = null;
        if (waypoint != null) xaeroWaypointBridge.remove(waypoint);
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


    

    


    private double getX() {
        return mc.player.getVelocity().x;
    }

    private static boolean isUsableElytra(ItemStack stack) {
        return stack.isOf(Items.ELYTRA) && stack.getDamage() < stack.getMaxDamage() - 1;
    }

    


    private double getY() {
        return mc.player.getVelocity().y;
    }

    


    private double getZ() {
        return mc.player.getVelocity().z;
    }

    



    private void setX(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(f, currentVel.y, currentVel.z);
        mc.player.setVelocity(newVel);
    }

    



    private void setY(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(currentVel.x, f, currentVel.z);
        mc.player.setVelocity(newVel);
    }

    



    private void setZ(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        Vec3d newVel = new Vec3d(currentVel.x, currentVel.y, f);
        mc.player.setVelocity(newVel);
    }

    private static class TargetCoordinateSetting extends Setting<Integer> {
        public final int min, max;
        public final int sliderMin, sliderMax;
        public final boolean noSlider;

        private TargetCoordinateSetting(String name, String description, int defaultValue, Consumer<Integer> onChanged, Consumer<Setting<Integer>> onModuleActivated, IVisible visible, int min, int max, int sliderMin, int sliderMax, boolean noSlider) {
            super(name, description, defaultValue, onChanged, onModuleActivated, visible);

            this.min = min;
            this.max = max;
            this.sliderMin = sliderMin;
            this.sliderMax = sliderMax;
            this.noSlider = noSlider;
        }

        @Override
        protected Integer parseImpl(String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        @Override
        protected boolean isValueValid(Integer value) {
            return value >= min && value <= max;
        }

        @Override
        protected NbtCompound save(NbtCompound tag) {
            tag.putInt("value", get());
            return tag;
        }

        @Override
        protected Integer load(NbtCompound tag) {
            set(tag.getInt("value", 0));
            return get();
        }

        private static class Builder extends SettingBuilder<Builder, Integer, TargetCoordinateSetting> {
            private int min = Integer.MIN_VALUE, max = Integer.MAX_VALUE;
            private int sliderMin = 0, sliderMax = 10;
            private boolean noSlider = false;

            private Builder() {
                super(0);
            }

            public Builder min(int min) {
                this.min = min;
                return this;
            }

            public Builder max(int max) {
                this.max = max;
                return this;
            }

            public Builder sliderMin(int min) {
                this.sliderMin = min;
                return this;
            }

            public Builder sliderMax(int max) {
                this.sliderMax = max;
                return this;
            }

            @Override
            public TargetCoordinateSetting build() {
                return new TargetCoordinateSetting(name, description, defaultValue, onChanged, onModuleActivated, visible, min, max, Math.max(sliderMin, min), Math.min(sliderMax, max), noSlider);
            }
        }
    }


}
