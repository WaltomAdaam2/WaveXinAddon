package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
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
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

public class BetterElytraFly extends WaveXinModule {
    static MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgElytraReplace = settings.createGroup("Elytra Replace");

    public final Setting<Boolean> autoStop = sgGeneral.add(new BoolSetting.Builder()
        .name("Stop in Unloaded Chunks")
        .description("Stops flight movement when the current chunk is unloaded")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> autoStart = sgGeneral.add(new BoolSetting.Builder()
        .name("AutoStart")
        .description("Automatically starts gliding with an equipped elytra")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("Speed")
        .description("Horizontal flight speed")
        .defaultValue(1.0)
        .min(0.1)
        .sliderMin(0.1)
        .max(20)
        .sliderMax(20)
        .build()
    );

    public final Setting<Double> upPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("UpPitch")
        .description("Pitch value reserved for upward control")
        .defaultValue(0.0)
        .min(0)
        .sliderMin(0)
        .max(90)
        .sliderMax(90)
        .build()
    );

    public final Setting<Double> upFactor = sgGeneral.add(new DoubleSetting.Builder()
        .name("UpFactor")
        .description("Minimum horizontal speed factor before upward acceleration")
        .defaultValue(1.0)
        .min(0)
        .sliderMin(0)
        .max(10)
        .sliderMax(10)
        .build()
    );

    public final Setting<Double> downFactor = sgGeneral.add(new DoubleSetting.Builder()
        .name("FallSpeed")
        .description("Passive fall speed factor")
        .defaultValue(1.0)
        .min(0)
        .sliderMin(0)
        .max(10)
        .sliderMax(10)
        .build()
    );

    public final Setting<Double> downSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("DownSpeed")
        .description("Sneak descent speed")
        .defaultValue(1.0)
        .min(0.1)
        .sliderMin(0.1)
        .max(10)
        .sliderMax(10)
        .build()
    );

    public final Setting<Boolean> speedLimit = sgGeneral.add(new BoolSetting.Builder()
        .name("SpeedLimit")
        .description("Limits horizontal flight speed")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> maxSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("MaxSpeed")
        .description("Maximum horizontal flight speed")
        .defaultValue(2.5)
        .min(0.1)
        .sliderMin(0.1)
        .max(20)
        .sliderMax(20)
        .visible(speedLimit::get)
        .build()
    );

    public final Setting<Boolean> noDrag = sgGeneral.add(new BoolSetting.Builder()
        .name("NoDrag")
        .description("Disables vanilla-style elytra drag")
        .defaultValue(false)
        .build()
    );
    private static final int ELYTRA_MAX_DAMAGE = new ItemStack(Items.ELYTRA).getMaxDamage();

    private final Setting<Boolean> elytraReplace = sgElytraReplace.add(new BoolSetting.Builder()
        .name("Enabled")
        .description("Automatically replaces damaged elytra")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> replaceDurability = sgElytraReplace.add(new IntSetting.Builder()
        .name("Durability Threshold")
        .description("Elytra durability threshold for replacement")
        .defaultValue(2)
        .range(1, ELYTRA_MAX_DAMAGE - 1)
        .sliderRange(1, ELYTRA_MAX_DAMAGE - 1)
        .visible(elytraReplace::get)
        .build()
    );

    private final Setting<Boolean> elytraReplaceChatFeedback = sgElytraReplace.add(new BoolSetting.Builder()
        .name("Chat Feedback")
        .description("Sends chat feedback when replacing elytra")
        .defaultValue(true)
        .visible(elytraReplace::get)
        .build()
    );

    private final Setting<Boolean> onlyWhenFlying = sgElytraReplace.add(new BoolSetting.Builder()
        .name("Only While Flying")
        .description("Only replaces elytra while gliding")
        .defaultValue(false)
        .visible(elytraReplace::get)
        .build()
    );

    private final Setting<Boolean> pauseInventoryTweaks = sgElytraReplace.add(new BoolSetting.Builder()
        .name("InventoryTweaks Compatibility")
        .description("Temporarily disables InventoryTweaks while replacing elytra")
        .defaultValue(true)
        .visible(elytraReplace::get)
        .build()
    );

    private final Setting<Integer> reEnableDelay = sgElytraReplace.add(new IntSetting.Builder()
        .name("Compatibility Delay")
        .description("Delay before re-enabling InventoryTweaks after elytra replacement, in ticks")
        .defaultValue(10)
        .range(1, 60)
        .sliderMax(60)
        .visible(() -> elytraReplace.get() && pauseInventoryTweaks.get())
        .build()
    );

    private boolean hasElytra = false;
    private boolean inventoryTweaksWasActive = false;
    private int reEnableCountdown = 0;

    public BetterElytraFly() {
        super(WaveXinAddon.CATEGORY, "better-elytra-fly", "Better Elytra Fly");
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
        if (inventoryTweaksWasActive) restoreInventoryTweaks();
        reEnableCountdown = 0;
        if (mc.player != null) {
            if (!mc.player.isCreative()) mc.player.getAbilities().allowFlying = false;
            mc.player.getAbilities().flying = false;
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (isSimpleElytraFlyPathActive()) return;

        if (mc.player == null || mc.world == null) {
            hasElytra = false;
            return;
        }

        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        hasElytra = isUsableElytra(chestStack);

        if (autoStart.get() && hasElytra && !mc.player.isGliding()) {
            requestElytraGlide(mc.player);
        }
    }

    @EventHandler
    private void onElytraReplaceTick(TickEvent.Post event) {
        if (!elytraReplace.get() || mc.player == null || mc.world == null) return;

        if (reEnableCountdown > 0) {
            reEnableCountdown--;
            if (reEnableCountdown == 0 && inventoryTweaksWasActive) restoreInventoryTweaks();
        }

        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestStack.getItem() != Items.ELYTRA) return;

        int remainingDurability = chestStack.getMaxDamage() - chestStack.getDamage();
        performElytraReplacement(remainingDurability);
    }
    protected final Vec3d buildFlightDirectionVector(float pitch, float yaw) {
        float f = pitch * 0.017453292F;
        float g = -yaw * 0.017453292F;
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        return new Vec3d(i * j, -k, h * j);
    }

    public final Vec3d getFlightDirectionVector(float tickDelta) {
        return this.buildFlightDirectionVector(-upPitch.get().floatValue(), mc.player.getYaw(tickDelta));
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
        return !player.getAbilities().flying
            && !player.hasVehicle()
            && !player.isClimbing()
            && itemStack.isOf(Items.ELYTRA)
            && isUsableElytra(itemStack);
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

    public static double[] calculateFlightVelocity(double speed) {
        if (mc.player == null) return new double[]{0, 0};

        Vec2f movementInput = mc.player.input.getMovementInput();
        float forward = movementInput.y;
        float side = movementInput.x;
        float yaw = mc.player.getYaw(mc.getRenderTickCounter().getTickProgress(true));

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
    public void handleWavePlayerMove(MoveEvent event) {
        if (isSimpleElytraFlyPathActive() || !autoStop.get() || mc.player == null || mc.world == null || !mc.player.isGliding()) return;

        int chunkX = MathHelper.floor(mc.player.getX()) >> 4;
        int chunkZ = MathHelper.floor(mc.player.getZ()) >> 4;
        if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) event.cancel();
    }

    @EventHandler
    public void onMove(TravelEvent event) {
        if (isSimpleElytraFlyPathActive() || mc.player == null || mc.world == null || !hasElytra || !mc.player.isGliding() || event.isPost()) return;

        Vec3d lookVec = getFlightDirectionVector(mc.getRenderTickCounter().getTickProgress(true));
        double lookDist = Math.sqrt(lookVec.x * lookVec.x + lookVec.z * lookVec.z);
        double motionDist = Math.sqrt(getX() * getX() + getZ() * getZ());

        if (mc.player.input.playerInput.sneak()) {
            setY(-downSpeed.get());
        } else if (!mc.player.input.playerInput.jump()) {
            setY(-0.00000000003D * downFactor.get());
        }

        if (mc.player.input.playerInput.jump()) {
            if (motionDist > upFactor.get() / 10.0D) {
                double rawUpSpeed = motionDist * 0.01325D;
                setY(getY() + rawUpSpeed * 3.2D);
                if (lookDist > 0.0D) {
                    setX(getX() - lookVec.x * rawUpSpeed / lookDist);
                    setZ(getZ() - lookVec.z * rawUpSpeed / lookDist);
                }
            } else {
                double[] dir = calculateFlightVelocity(speed.get());
                setX(dir[0]);
                setZ(dir[1]);
            }
        }

        if (lookDist > 0.0D) {
            setX(getX() + (lookVec.x / lookDist * motionDist - getX()) * 0.1D);
            setZ(getZ() + (lookVec.z / lookDist * motionDist - getZ()) * 0.1D);
        }

        if (!mc.player.input.playerInput.jump()) {
            double[] dir = calculateFlightVelocity(speed.get());
            setX(dir[0]);
            setZ(dir[1]);
        }

        if (!noDrag.get()) {
            setY(getY() * 0.9900000095367432D);
            setX(getX() * 0.9800000190734863D);
            setZ(getZ() * 0.9900000095367432D);
        }

        double finalDist = Math.sqrt(getX() * getX() + getZ() * getZ());
        if (speedLimit.get() && finalDist > maxSpeed.get()) {
            setX(getX() * maxSpeed.get() / finalDist);
            setZ(getZ() * maxSpeed.get() / finalDist);
        }

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

    private void performElytraReplacement(int remainingDurability) {
        if (onlyWhenFlying.get() && !mc.player.isGliding()) return;
        if (remainingDurability > replaceDurability.get()) return;

        FindItemResult elytra = InvUtils.find(stack -> {
            if (stack.getItem() != Items.ELYTRA) return false;
            int stackDurability = stack.getMaxDamage() - stack.getDamage();
            return stackDurability > replaceDurability.get();
        });

        if (!elytra.found()) {
            if (elytraReplaceChatFeedback.get()) warning("No replacement elytra found with durability > %d", replaceDurability.get());
            return;
        }

        if (pauseInventoryTweaks.get()) pauseInventoryTweaks();

        InvUtils.move().from(elytra.slot()).toArmor(2);

        if (elytraReplaceChatFeedback.get()) {
            info("Replaced elytra (durability: %d -> %d)", remainingDurability,
                mc.player.getEquippedStack(EquipmentSlot.CHEST).getMaxDamage() - mc.player.getEquippedStack(EquipmentSlot.CHEST).getDamage());
        }

        if (pauseInventoryTweaks.get() && inventoryTweaksWasActive) reEnableCountdown = reEnableDelay.get();
    }

    private void pauseInventoryTweaks() {
        InventoryTweaks inventoryTweaks = Modules.get().get(InventoryTweaks.class);
        if (inventoryTweaks != null && inventoryTweaks.isActive()) {
            inventoryTweaksWasActive = true;
            inventoryTweaks.toggle();
            if (elytraReplaceChatFeedback.get()) info("Temporarily disabled InventoryTweaks");
        } else {
            inventoryTweaksWasActive = false;
        }
    }

    private void restoreInventoryTweaks() {
        InventoryTweaks inventoryTweaks = Modules.get().get(InventoryTweaks.class);
        if (inventoryTweaks != null && !inventoryTweaks.isActive()) {
            inventoryTweaks.toggle();
            if (elytraReplaceChatFeedback.get()) info("Re-enabled InventoryTweaks");
        }
        inventoryTweaksWasActive = false;
    }
    private boolean isSimpleElytraFlyPathActive() {
        SimpleElytraFlyPath simpleElytraFlyPath = Modules.get().get(SimpleElytraFlyPath.class);
        return simpleElytraFlyPath != null && simpleElytraFlyPath.isActive();
    }

    private static boolean isUsableElytra(ItemStack stack) {
        return stack.isOf(Items.ELYTRA) && stack.getDamage() < stack.getMaxDamage() - 1;
    }
}
