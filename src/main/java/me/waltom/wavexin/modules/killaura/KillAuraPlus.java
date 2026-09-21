package me.waltom.wavexin.modules.killaura;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.mixins.MixinLivingEntityAccessor;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Blink;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.TridentItem;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Independent Meteor adaptation of Alienv4 Aura (commit e443c5b), retaining its
 * targeting and timing defaults without depending on AlienClient classes.
 */
public final class KillAuraPlus extends WaveXinModule {
    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup targets = settings.createGroup("Targets");
    private final SettingGroup rotation = settings.createGroup("Rotation");
    private final SettingGroup render = settings.createGroup("Render");
    private final Setting<Double> range = add(general, "Range", "Maximum distance at which this module attacks.", 6, .1, 7);
    private final Setting<Double> targetRange = add(general, "Target Range", "Search distance used only to display a possible target; attacks remain inside Range.", 8, .1, 14);
    private final Setting<Cooldown> cooldownMode = general.add(new EnumSetting.Builder<Cooldown>().name("Cooldown Mode").description("Delay uses elapsed attack time; Vanilla uses the client cooldown value.").defaultValue(Cooldown.DELAY).build());
    private final Setting<Boolean> reset = bool(general, "Reset", "Resets delay timing after an outgoing attack or swing packet.", true);
    private final Setting<SwingSide> swing = general.add(new EnumSetting.Builder<SwingSide>().name("Swing").description("Sends a client animation, a server swing packet, or both.").defaultValue(SwingSide.ALL).build());
    private final Setting<Double> hurtTime = add(general, "Hurt Time", "Skips living targets whose hurt timer exceeds this value.", 10, 0, 10);
    private final Setting<Double> cooldown = add(general, "Cooldown", "Required cooldown progress; values above one use unbounded elapsed progress.", 1.1, 0, 1.2);
    private final Setting<Double> wallRange = add(general, "Wall Range", "Maximum hidden-target distance.", 6, .1, 7);
    private final Setting<Boolean> whileUsing = bool(general, "While Using", "Allows attacks while the player is using an item.", true);
    private final Setting<Boolean> weaponOnly = bool(general, "Weapon Only", "Requires a sword, axe, or trident in the main hand; maces are excluded.", true);
    private final Setting<Timing> timing = general.add(new EnumSetting.Builder<Timing>().name("Timing").description("Chooses which Meteor tick phase runs targeting.").defaultValue(Timing.ALL).build());
    private final Setting<Boolean> players = bool(targets, "Players", "Targets non-friend players.", true);
    private final Setting<Boolean> armorLow = bool(targets, "Armor Low", "Prioritizes players with an empty armor slot or an armor item below ten percent durability.", true);
    private final Setting<Boolean> mobs = bool(targets, "Mobs", "Targets hostile mobs.", true);
    private final Setting<Boolean> animals = bool(targets, "Animals", "Targets animal entities.", true);
    private final Setting<Boolean> villagers = bool(targets, "Villagers", "Targets villagers and wandering traders.", true);
    private final Setting<Boolean> slimes = bool(targets, "Slimes", "Targets slimes.", true);
    private final Setting<Priority> priority = targets.add(new EnumSetting.Builder<Priority>().name("Filter").description("Sorts eligible targets by distance or health.").defaultValue(Priority.DISTANCE).build());
    private final Setting<Boolean> rotate = bool(rotation, "Rotate", "Rotates toward the selected attack point before attacking.", true);
    private final Setting<Boolean> yawStep = bool(rotation, "Yaw Step", "Uses the limited-step rotation and optional field-of-view gate.", false);
    private final Setting<Boolean> fallFlying = bool(rotation, "Fall Flying", "Keeps yaw-step enabled while fall flying.", true);
    private final Setting<Double> steps = add(rotation, "Steps", "Yaw-step fraction; one is a 180 degree yaw step.", .05, 0, 1);
    private final Setting<Boolean> onlyLooking = bool(rotation, "Only Looking", "Requires the yaw-step target to be inside the configured field of view.", true);
    private final Setting<Double> fov = add(rotation, "Fov", "Field-of-view radius used by yaw-step targeting.", 20, 0, 360);
    private final Setting<Integer> rotationPriority = rotation.add(new IntSetting.Builder().name("Priority").description("Meteor rotation queue priority.").defaultValue(10).range(0, 100).build());
    private final Setting<TargetEsp> esp = render.add(new EnumSetting.Builder<TargetEsp>().name("Target ESP").description("Renders the current target; None disables rendering.").defaultValue(TargetEsp.FILL).build());
    private final Setting<Integer> animationTime = render.add(new IntSetting.Builder().name("Animation Time").description("Hit flash duration in milliseconds.").defaultValue(200).range(0, 2000).build());
    private final Setting<KillAuraLogic.AuraEase> ease = render.add(new EnumSetting.Builder<KillAuraLogic.AuraEase>().name("Ease").description("Easing curve for the hit flash.").defaultValue(KillAuraLogic.AuraEase.CUBIC_IN_OUT).build());
    private final Setting<SettingColor> color = color(render, "Color", "Base target fill color.", 255, 255, 255, 50);
    private final Setting<SettingColor> outline = color(render, "Outline Color", "Base target outline color.", 255, 255, 255, 50);
    private final Setting<SettingColor> hitColor = color(render, "Hit Color", "Target fill color directly after an attack.", 255, 255, 255, 150);
    private final Setting<SettingColor> hitOutline = color(render, "Hit Outline Color", "Target outline color directly after an attack.", 255, 255, 255, 150);
    private Entity target;
    private long lastResetMillis;
    private long lastHitMillis;

    public KillAuraPlus() { super(WaveXinAddon.CATEGORY, "kill-aura-plus", "Alienv4-style combat aura adapted for Meteor."); }

    @Override public void onActivate() { lastResetMillis = System.currentTimeMillis(); }
    @Override public void onDeactivate() { target = null; }
    @EventHandler private void onTickPre(TickEvent.Pre event) { if (timing.get() != Timing.POST) tick(); }
    @EventHandler private void onTickPost(TickEvent.Post event) { if (timing.get() != Timing.PRE) tick(); }

    private void tick() {
        if (!usable()) { target = null; return; }
        target = findTarget(range.get());
        boolean attackInRange = target != null;
        if (target == null) target = findTarget(targetRange.get());
        if (target != null) attackSelected(target, attackInRange);
    }

    private void attackSelected(Entity selected, boolean attackInRange) {
        Vec3d point = closest(selected);
        if (!rotate.get()) { if (attackInRange) attack(selected, mc.player, mc.world); return; }
        double yaw = Rotations.getYaw(point), pitch = Rotations.getPitch(point);
        boolean useYawStep = shouldYawStep();
        if (!useYawStep && (!attackInRange || !ready(selected))) return;
        if (useYawStep) {
            yaw = Rotations.serverYaw + MathHelper.clamp((float) KillAuraLogic.wrap(yaw - Rotations.serverYaw), (float) (-180 * steps.get()), (float) (180 * steps.get()));
            pitch = Rotations.serverPitch + MathHelper.clamp((float) (pitch - Rotations.serverPitch), (float) (-90 * steps.get()), (float) (90 * steps.get()));
        }
        Object player = mc.player, world = mc.world;
        double finalYaw = yaw, finalPitch = pitch;
        Rotations.rotate(finalYaw, finalPitch, rotationPriority.get(), () -> {
            if (!attackInRange || !ready(selected) || mc.player != player || mc.world != world) return;
            Vec3d currentPoint = closest(selected);
            if (!useYawStep || !onlyLooking.get() || KillAuraLogic.inFov(Rotations.serverYaw, Rotations.serverPitch,
                Rotations.getYaw(currentPoint), Rotations.getPitch(currentPoint), fov.get())) attack(selected, player, world);
        });
    }

    private void attack(Entity selected, Object player, Object world) {
        if (!ready(selected) || mc.player != player || mc.world != world || selected != target || mc.currentScreen != null) return;
        mc.interactionManager.attackEntity(mc.player, selected);
        switch (swing.get()) {
            case ALL -> mc.player.swingHand(Hand.MAIN_HAND);
            case CLIENT -> mc.player.swingHand(Hand.MAIN_HAND, false);
            case SERVER -> mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }
        lastResetMillis = System.currentTimeMillis();
        lastHitMillis = System.currentTimeMillis();
    }

    @EventHandler private void onPacket(PacketEvent.Send event) {
        if (!reset.get()) return;
        if (event.packet instanceof HandSwingC2SPacket || event.packet instanceof PlayerInteractEntityC2SPacket packet
            && ((Enum<?>) ((IPlayerInteractEntityC2SPacket) packet).meteor$getType()).name().equals("ATTACK")) lastResetMillis = System.currentTimeMillis();
    }

    private boolean ready(Entity entity) {
        if (!isActive() || !usable() || entity != target || mc.world.getEntityById(entity.getId()) != entity || !valid(entity) || !inAttackRange(entity)) return false;
        int elapsed = cooldownMode.get() == Cooldown.VANILLA
            ? ((MixinLivingEntityAccessor) mc.player).wavexin$getTicksSinceLastAttack()
            : KillAuraLogic.elapsedTicks(System.currentTimeMillis(), lastResetMillis);
        double progress = KillAuraLogic.elapsedProgress(elapsed, mc.player.getAttackCooldownProgressPerTick(), TickRate.INSTANCE.getTickRate());
        return progress >= cooldown.get() && (!(entity instanceof LivingEntity living) || living.hurtTime <= hurtTime.get());
    }

    private Entity findTarget(double candidateRange) {
        Entity best = null; double bestDistance = candidateRange; double bestHealth = 36;
        for (Entity entity : mc.world.getEntities()) {
            if (!valid(entity) || !inRange(entity, candidateRange)) continue;
            if (armorLow.get() && entity instanceof PlayerEntity player && armorIsLow(player)) return entity;
            double distance = mc.player.getEyePos().distanceTo(closest(entity)), health = health(entity);
            if (best == null || priority.get() == Priority.DISTANCE && distance < bestDistance || priority.get() == Priority.HEALTH && health < bestHealth) { best = entity; bestDistance = distance; bestHealth = health; }
        }
        return best;
    }

    private boolean usable() {
        Blink blink = Modules.get().get(Blink.class);
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.currentScreen == null
            && mc.player.isAlive() && (blink == null || !blink.isActive())
            && (!weaponOnly.get() || weapon(mc.player.getMainHandStack())) && (whileUsing.get() || !mc.player.isUsingItem());
    }
    private boolean valid(Entity entity) { if (entity == mc.player || !entity.isAlive() || entity.isSpectator()) return false; if (entity instanceof PlayerEntity player) return players.get() && Friends.get().shouldAttack(player); if (entity instanceof SlimeEntity) return slimes.get(); if (entity instanceof VillagerEntity || entity instanceof WanderingTraderEntity) return villagers.get(); if (entity instanceof AnimalEntity) return animals.get(); return entity instanceof MobEntity && mobs.get(); }
    private boolean inAttackRange(Entity entity) { return inRange(entity, range.get()); }
    private boolean inRange(Entity entity, double candidateRange) { return KillAuraLogic.inRange(mc.player.getEyePos().distanceTo(closest(entity)), PlayerUtils.canSeeEntity(entity), candidateRange, wallRange.get()); }
    private boolean shouldYawStep() { return yawStep.get() && (fallFlying.get() || !mc.player.isGliding()); }
    private static boolean weapon(ItemStack stack) { return stack.isIn(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem || stack.getItem() instanceof TridentItem; }
    private static double health(Entity entity) { return entity instanceof LivingEntity living ? living.getHealth() + living.getAbsorptionAmount() : 36; }
    private static boolean armorIsLow(PlayerEntity player) { for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) { ItemStack stack = player.getEquippedStack(slot); if (stack.isEmpty() || !stack.isDamageable() || (stack.getMaxDamage() - stack.getDamage()) * 100.0 / stack.getMaxDamage() < 10) return true; } return false; }
    private Vec3d closest(Entity entity) { Box box = entity.getBoundingBox(); Vec3d eye = mc.player.getEyePos(); return new Vec3d(MathHelper.clamp(eye.x, box.minX, box.maxX), MathHelper.clamp(eye.y, box.minY, box.maxY), MathHelper.clamp(eye.z, box.minZ, box.maxZ)); }
    @EventHandler private void onRender(Render3DEvent event) {
        if (target == null || mc.world == null || mc.world.getEntityById(target.getId()) != target || !target.isAlive() || esp.get() == TargetEsp.NONE) return;
        double duration = Math.max(1, animationTime.get());
        double progress = ease.get().apply(Math.max(0, 1 - (System.currentTimeMillis() - lastHitMillis) / duration));
        SettingColor side = blended(color.get(), hitColor.get(), progress);
        SettingColor line = blended(outline.get(), hitOutline.get(), progress);
        Box box = target.getBoundingBox().expand(0, .1, 0);
        if (esp.get() == TargetEsp.JELLO) { renderRing(event, box, line, box.minY + (box.maxY - box.minY) * (0.5 + 0.5 * Math.sin(System.nanoTime() / 400_000_000.0))); return; }
        if (esp.get() == TargetEsp.THUNDER_HACK) { renderCross(event, box, line); return; }
        event.renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, side, line, esp.get() == TargetEsp.BOX ? ShapeMode.Both : ShapeMode.Sides, 0);
    }

    private static void renderRing(Render3DEvent event, Box box, SettingColor color, double y) {
        double x = (box.minX + box.maxX) / 2, z = (box.minZ + box.maxZ) / 2;
        double radius = Math.max(box.maxX - box.minX, box.maxZ - box.minZ) / 2;
        for (int i = 0; i < 32; i++) {
            double a = Math.PI * 2 * i / 32, b = Math.PI * 2 * (i + 1) / 32;
            event.renderer.line(x + Math.cos(a) * radius, y, z + Math.sin(a) * radius, x + Math.cos(b) * radius, y, z + Math.sin(b) * radius, color);
        }
    }

    private static void renderCross(Render3DEvent event, Box box, SettingColor color) {
        double x = (box.minX + box.maxX) / 2, y = (box.minY + box.maxY) / 2, z = (box.minZ + box.maxZ) / 2;
        event.renderer.line(box.minX, y, z, box.maxX, y, z, color);
        event.renderer.line(x, box.minY, z, x, box.maxY, z, color);
        event.renderer.line(x, y, box.minZ, x, y, box.maxZ, color);
    }
    private static SettingColor blended(SettingColor a, SettingColor b, double p) { return new SettingColor(KillAuraLogic.channel(a.r, b.r, p), KillAuraLogic.channel(a.g, b.g, p), KillAuraLogic.channel(a.b, b.b, p), KillAuraLogic.channel(a.a, b.a, p)); }
    private static Setting<Boolean> bool(SettingGroup group, String name, String description, boolean value) { return group.add(new BoolSetting.Builder().name(name).description(description).defaultValue(value).build()); }
    private static Setting<Double> add(SettingGroup group, String name, String description, double value, double min, double max) { return group.add(new DoubleSetting.Builder().name(name).description(description).defaultValue(value).range(min, max).sliderRange(min, max).build()); }
    private static Setting<SettingColor> color(SettingGroup group, String name, String description, int r, int g, int b, int a) { return group.add(new ColorSetting.Builder().name(name).description(description).defaultValue(new SettingColor(r, g, b, a)).build()); }
    @Override public String getInfoString() { return target == null ? null : target.getName().getString(); }
    private enum Cooldown { VANILLA, DELAY }
    private enum Timing { PRE, POST, ALL }
    private enum Priority { DISTANCE, HEALTH }
    private enum SwingSide { ALL, CLIENT, SERVER }
    private enum TargetEsp { FILL, BOX, JELLO, THUNDER_HACK, NONE }
}
