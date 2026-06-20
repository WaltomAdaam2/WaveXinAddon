package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class ElytraFlyXin extends Module {
    static MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> autoStop = sgGeneral.add(new BoolSetting.Builder()
        .name("Stop in Unloaded Chunks")
        .description("Stops flight movement when the current chunk is unloaded")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("Flight Speed")
        .description("Horizontal flight speed")
        .defaultValue(1.5)
        .min(0.1)
        .sliderMin(0.1)
        .max(3)
        .sliderMax(3)
        .build()
    );

    public final Setting<Double> downSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("Descent Speed")
        .description("Descent speed")
        .defaultValue(1)
        .min(0.1)
        .sliderMin(0.1)
        .max(3)
        .sliderMax(3)
        .build()
    );

    private boolean hasElytra = false;

    public ElytraFlyXin() {
        super(WaveXinAddon.CATEGORY, "elytrafly-xin", "Xin elytra flight");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) {
            if (!mc.player.isCreative()) mc.player.getAbilities().allowFlying = false;
            mc.player.getAbilities().flying = false;
        }
        hasElytra = false;
    }

    @Override
    public void onDeactivate() {
        hasElytra = false;
        if (mc.player != null) {
            if (!mc.player.isCreative()) mc.player.getAbilities().allowFlying = false;
            mc.player.getAbilities().flying = false;
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        hasElytra = false;
        for (ItemStack stack : mc.player.getArmorItems()) {
            if (stack.isOf(Items.ELYTRA)) {
                hasElytra = true;
                break;
            }
        }
    }

    protected final Vec3d getRotationVector(float pitch, float yaw) {
        float f = pitch * 0.017453292F;
        float g = -yaw * 0.017453292F;
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        return new Vec3d(i * j, -k, h * j);
    }

    public final Vec3d getRotationVec(float tickDelta) {
        return this.getRotationVector(0, mc.player.getYaw(tickDelta));
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
        return !player.getAbilities().flying
            && !player.hasVehicle()
            && !player.isClimbing()
            && itemStack.isOf(Items.ELYTRA)
            && isUsableElytra(itemStack);
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

    public static double[] directionSpeedKey(double speed) {
        float forward = (mc.options.forwardKey.isPressed() ? 1 : 0) + (mc.options.backKey.isPressed() ? -1 : 0);
        float side = (mc.options.leftKey.isPressed() ? 1 : 0) + (mc.options.rightKey.isPressed() ? -1 : 0);
        float yaw = mc.player.getYaw();

        if (forward != 0.0f) {
            if (side > 0.0f) yaw += forward > 0.0f ? -45 : 45;
            else if (side < 0.0f) yaw += forward > 0.0f ? 45 : -45;
            side = 0.0f;
            if (forward > 0.0f) forward = 1.0f;
            else if (forward < 0.0f) forward = -1.0f;
        }

        double sin = Math.sin(Math.toRadians(yaw + 90.0f));
        double cos = Math.cos(Math.toRadians(yaw + 90.0f));
        double posX = forward * speed * cos + side * speed * sin;
        double posZ = forward * speed * sin - side * speed * cos;
        return new double[]{posX, posZ};
    }

    @EventHandler
    public void onPlayerMove(MoveEvent event) {
        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) return;

        if (mc.player.isGliding()) {
            int chunkX = (int) (mc.player.getX() / 16);
            int chunkZ = (int) (mc.player.getZ() / 16);

            if (autoStop.get()) {
                if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                    event.setX(0);
                    event.setY(0);
                    event.setZ(0);
                }
            }
        }
    }

    @EventHandler
    public void onMove(TravelEvent event) {
        if (mc.player == null || mc.world == null || !hasElytra || !mc.player.isGliding() || event.isPost()) return;

        Vec3d lookVec = getRotationVec(1.0f);
        double lookDist = Math.sqrt(lookVec.x * lookVec.x + lookVec.z * lookVec.z);
        double motionDist = Math.sqrt(getX() * getX() + getZ() * getZ());

        if (mc.options.sneakKey.isPressed()) {
            setY(-downSpeed.get());
        } else if (!mc.options.jumpKey.isPressed()) {
            setY(-0.00000000003D * 0);
        }

        if (mc.options.jumpKey.isPressed()) {
            if (motionDist > 0 / 10) {
                double rawUpSpeed = motionDist * 0.01325D;
                setY(getY() + rawUpSpeed * 3.2D);
                setX(getX() - lookVec.x * rawUpSpeed / lookDist);
                setZ(getZ() - lookVec.z * rawUpSpeed / lookDist);
            } else {
                double[] dir = directionSpeedKey(speed.get());
                setX(dir[0]);
                setZ(dir[1]);
            }
        }

        if (lookDist > 0.0D) {
            setX(getX() + (lookVec.x / lookDist * motionDist - getX()) * 0.1D);
            setZ(getZ() + (lookVec.z / lookDist * motionDist - getZ()) * 0.1D);
        }

        if (!mc.options.jumpKey.isPressed()) {
            double[] dir = directionSpeedKey(speed.get());
            setX(dir[0]);
            setZ(dir[1]);
        }

        setY(getY() * 0.9900000095367432D);
        setX(getX() * 0.9800000190734863D);
        setZ(getZ() * 0.9900000095367432D);

        event.cancel();
        mc.player.move(MovementType.SELF, mc.player.getVelocity());
    }

    private double getX() {
        return mc.player.getVelocity().x;
    }

    private double getY() {
        return mc.player.getVelocity().y;
    }

    private double getZ() {
        return mc.player.getVelocity().z;
    }

    private void setX(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        mc.player.setVelocity(new Vec3d(f, currentVel.y, currentVel.z));
    }

    private void setY(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        mc.player.setVelocity(new Vec3d(currentVel.x, f, currentVel.z));
    }

    private void setZ(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        mc.player.setVelocity(new Vec3d(currentVel.x, currentVel.y, f));
    }

    private static boolean isUsableElytra(ItemStack stack) {
        return stack.isOf(Items.ELYTRA) && stack.getDamage() < stack.getMaxDamage() - 1;
    }
}
