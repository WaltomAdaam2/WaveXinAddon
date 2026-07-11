package me.waltom.wavexin;



import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
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
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

public class SimpleElytraFlyPath extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    
    private final SettingGroup sgTarget = settings.createGroup("Target Coordinates");
    private final SettingGroup sgFlight = settings.createGroup("Flight Settings");
    private boolean isArrive = false;

    
    
    public final Setting<Integer> globalX = sgTarget.add(new IntSetting.Builder()
        .name("Target X")
        .description("Target X")
        .defaultValue(0)
        .range(-30000000, 30000000)
        .sliderRange(-30000000, 30000000)
        .build()
    );

    
    public final Setting<Integer> globalZ = sgTarget.add(new IntSetting.Builder()
        .name("Target Z")
        .description("Target Z")
        .defaultValue(0)
        .range(-30000000, 30000000)
        .sliderRange(-30000000, 30000000)
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
        .defaultValue(0.5)
        .min(0.1)
        .sliderMin(0.1)
        .max(3)
        .sliderMax(3)
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
        
        if (mc.player == null || mc.world == null || !checkElytra()) {
            toggle();
            return;
        }

        
        if (!checkValidHeight()) {
            ChatUtils.error("Can only be used above each dimension height limit: Nether (Y > 128), Overworld (Y > 320), End (Y > 256)");
            toggle();
            return;
        }

        
        if (!mc.player.isCreative()) mc.player.getAbilities().allowFlying = false;
        mc.player.getAbilities().flying = false;

        if (autoTakeoff.get() && !mc.player.isGliding()) {
            recastElytra(mc.player);
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
    public void onPlayerMove(MoveEvent event) {
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
            recastElytra(mc.player);
        }
    }


    



    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(TravelEvent event) {
        
        if (mc.player == null || mc.world == null || !mc.player.isGliding() || !checkElytra() || event.isPost()) {
            return;
        }

        
        target = new BlockPos(getTargetX(), 0, getTargetZ());

        
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        
        Vec3d targetPos = new Vec3d(getTargetX(), playerPos.y, getTargetZ());
        
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
            setX(direction.x * speed.get());
            setY(0);
            setZ(direction.z * speed.get());
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

    

    


    private boolean checkElytra() {
        
        return isUsableElytra(mc.player.getEquippedStack(EquipmentSlot.CHEST));
    }

    private int getTargetX() {
        return netherPosCalculation.get() ? Math.floorDiv(globalX.get(), 8) : globalX.get();
    }

    private int getTargetZ() {
        return netherPosCalculation.get() ? Math.floorDiv(globalZ.get(), 8) : globalZ.get();
    }

    






    private boolean checkValidHeight() {
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

    



    public static boolean recastElytra(ClientPlayerEntity player) {
        if (checkConditions(player) && ignoreGround(player)) {
            
            player.networkHandler.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            return true;
        }
        return false;
    }

    



    public static boolean checkConditions(ClientPlayerEntity player) {
        ItemStack itemStack = player.getEquippedStack(EquipmentSlot.CHEST);
        return (!player.getAbilities().flying &&    
            !player.hasVehicle() &&                 
            !player.isClimbing() &&                 
            itemStack.isOf(Items.ELYTRA) &&         
            isUsableElytra(itemStack));        
    }

    



    private static boolean ignoreGround(ClientPlayerEntity player) {
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


}
