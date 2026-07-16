package me.waltom.wavexin;



import me.waltom.wavexin.gui.TargetCoordinateInput;
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
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.function.Consumer;

public class SimpleElytraFlyPath extends WaveXinModule {
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
            reset.tooltip = "Reset";
        });
    }

    
    private final SettingGroup sgTarget = settings.createGroup("Target Coordinates");
    private final SettingGroup sgFlight = settings.createGroup("Flight Settings");
    private boolean isArrive = false;

    
    
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
        .description("Horizontal flight speed")
        .defaultValue(2.5)
        .min(0.1)
        .sliderMin(0.1)
        .max(20)
        .sliderMax(20)
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
        .description("Disables Simple Elytra Fly Path after arriving at the target")
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
        .defaultValue(1)
        .min(1)
        .sliderMin(0.1)
        .max(Integer.MAX_VALUE)
        .sliderMax(256)
        .build()
    );


    
    private BlockPos target;                

    


    public SimpleElytraFlyPath() {
        super(WaveXinAddon.CATEGORY, "simple-elytra-fly-path", "Automatic elytra path flight");
    }

    



    @Override
    public void onActivate() {
        
        if (mc.player == null || mc.world == null || !hasWorkingElytra()) {
            toggle();
            return;
        }

        
        if (!isSafeFlightHeight()) {
            ChatUtils.error("Recommended to use above each dimension height limit: Nether (Y > 128), Overworld (Y > 320), End (Y > 256)");
        }


        if (!mc.player.isCreative()) mc.player.getAbilities().allowFlying = false;
        mc.player.getAbilities().flying = false;

        if (autoTakeoff.get() && !mc.player.isGliding()) {
            requestElytraGlide(mc.player);
        }

        
        ChatUtils.info("Started pathing to X=%d, Z=%d", getTargetX(), getTargetZ());
    }

    



    @Override
    public void onDeactivate() {
        target = null;
        isArrive = false;

        
        if (mc.player != null) {
            if (!mc.player.isCreative()) mc.player.getAbilities().allowFlying = false;
            mc.player.getAbilities().flying = false;
        }
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


    @EventHandler
    public void onTick(TickEvent.Pre event) {
        
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        if (isArrive) {
            boolean shouldDisconnect = autoQuitServer.get();

            if (autoStopOnArrival.get()) {
                toggle();
            }

            if (shouldDisconnect) {
                mc.getNetworkHandler().getConnection().disconnect(Text.literal("Auto quit after arriving at target"));
            }

            isArrive = false;
            return;
        }

        
        if (autoTakeoff.get() && !mc.player.isGliding()) {
            requestElytraGlide(mc.player);
        }
    }


    



    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(TravelEvent event) {
        
        if (mc.player == null || mc.world == null || !mc.player.isGliding() || !hasWorkingElytra() || event.isPost()) {
            return;
        }

        
        int currentTargetX = getTargetX();
        int currentTargetZ = getTargetZ();
        target = new BlockPos(currentTargetX, 0, currentTargetZ);

        
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        
        Vec3d targetPos = new Vec3d(currentTargetX, playerPos.y, currentTargetZ);
        
        double deltaX = targetPos.x - playerPos.x;
        double deltaZ = targetPos.z - playerPos.z;

        
        double distance2D = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        if (distance2D == 0) {
            setX(0);
            setY(0);
            setZ(0);
            isArrive = true;
            event.cancel();
            return;
        }

        
        Vec3d direction = new Vec3d(deltaX, 0, deltaZ).normalize();

        if (distance2D > arrivalDistance2D.get()) {
            double flightSpeed = Math.min(speed.get(), distance2D - arrivalDistance2D.get());
            setX(direction.x * flightSpeed);
            setY(0);
            setZ(direction.z * flightSpeed);
        }else{
            setX(0);
            setY(0);
            setZ(0);

            isArrive = true;
        }

        
        setY(getY() * 0.9900000095367432D);
        setX(getX() * 0.9800000190734863D);
        setZ(getZ() * 0.9900000095367432D);

        
        event.cancel();
        mc.player.move(MovementType.SELF, mc.player.getVelocity());
    }

    

    


    private boolean hasWorkingElytra() {
        
        return isUsableElytra(mc.player.getEquippedStack(EquipmentSlot.CHEST));
    }

    private int getTargetX() {
        return netherPosCalculation.get() ? Math.floorDiv(globalX.get(), 8) : globalX.get();
    }

    private int getTargetZ() {
        return netherPosCalculation.get() ? Math.floorDiv(globalZ.get(), 8) : globalZ.get();
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
