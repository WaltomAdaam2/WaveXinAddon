package me.waltom.wavexin.modules.chickennametags;

import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;
import java.util.*;

public class ChickenNametags extends WaveXinModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    
    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("Scale")
        .description("Nametag scale.")
        .defaultValue(1.1)
        .min(0.1)
        .build()
    );

    private final Setting<Boolean> displayHealth = sgGeneral.add(new BoolSetting.Builder()
        .name("Show Health")
        .description("Shows chicken health.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> displayDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("Show Distance")
        .description("Shows distance to the chicken.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> maxRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("Render Range")
        .description("Only renders chicken nametags within this range.")
        .defaultValue(64)
        .min(1)
        .sliderMax(256)
        .build()
    );

    
    private final Setting<SettingColor> background = sgRender.add(new ColorSetting.Builder()
        .name("Background Color")
        .description("The nametag background color.")
        .defaultValue(new SettingColor(0, 0, 0, 75))
        .build()
    );

    private final Setting<SettingColor> nameColor = sgRender.add(new ColorSetting.Builder()
        .name("Name Color")
        .description("The nametag text color.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> distanceColor = sgRender.add(new ColorSetting.Builder()
        .name("Distance Color")
        .description("The distance text color.")
        .defaultValue(new SettingColor(150, 150, 150))
        .visible(displayDistance::get)
        .build()
    );

    
    private final Color RED = new Color(255, 25, 25);
    private final Color AMBER = new Color(255, 105, 25);
    private final Color GREEN = new Color(25, 252, 25);

    private final Vector3d pos = new Vector3d();
    private final List<ChickenEntity> chickenList = new ArrayList<>();

    public ChickenNametags() {
        super(WaveXinAddon.CATEGORY, "chicken-nametags", "Displays custom nametags for chicken entities.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            chickenList.clear();
            return;
        }

        chickenList.clear();
        Vec3d cameraPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        
        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() != EntityType.CHICKEN) continue;

            ChickenEntity chicken = (ChickenEntity) entity;
            double distance = PlayerUtils.distanceToCamera(chicken);

            
            if (distance <= maxRange.get()) {
                chickenList.add(chicken);
            }
        }

        
        chickenList.sort(Comparator.comparing(e -> e.squaredDistanceTo(cameraPos)));
    }

    @EventHandler
    private void handleWaveRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null) {
            chickenList.clear();
            return;
        }

        chickenList.removeIf(chicken -> chicken == null || chicken.isRemoved() || !chicken.isAlive());

        boolean shadow = Config.get().customFont.get();

        for (ChickenEntity chicken : chickenList) {
            Utils.set(pos, chicken, event.tickDelta);
            pos.add(0, getHeight(chicken), 0);

            if (NametagUtils.to2D(pos, scale.get())) {
                renderChickenNameplate(chicken, shadow);
            }
        }
    }

    private double getHeight(Entity entity) {
        return entity.getEyeHeight(entity.getPose()) + 0.5;
    }

    private void renderChickenNameplate(ChickenEntity chicken, boolean shadow) {
        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos);

        
        String nameText = WaveXinI18n.tr("entity.wavexin.chicken_nametags.chicken", "Chicken");
        if (chicken.hasCustomName()) {
            nameText = chicken.getCustomName().getString(); 
        }

        
        String healthText = "";
        Color healthColor = GREEN;
        if (displayHealth.get()) {
            float absorption = chicken.getAbsorptionAmount();
            int health = Math.round(chicken.getHealth() + absorption);
            double healthPercentage = health / (chicken.getMaxHealth() + absorption);

            healthText = " " + health;

            if (healthPercentage <= 0.333) healthColor = RED;
            else if (healthPercentage <= 0.666) healthColor = AMBER;
            else healthColor = GREEN;
        }

        
        String distanceText = "";
        if (displayDistance.get()) {
            double dist = Math.round(PlayerUtils.distanceToCamera(chicken) * 10.0) / 10.0;
            distanceText = " [" + dist + "m]";
        }

        
        double nameWidth = text.getWidth(nameText, shadow);
        double healthWidth = displayHealth.get() ? text.getWidth(healthText, shadow) : 0;
        double distanceWidth = displayDistance.get() ? text.getWidth(distanceText, shadow) : 0;
        double heightDown = text.getHeight(shadow);

        double width = nameWidth + healthWidth + distanceWidth;
        double widthHalf = width / 2;

        
        drawNametagBackdrop(-widthHalf, -heightDown, width, heightDown);

        
        text.beginBig();
        double hX = -widthHalf;
        double hY = -heightDown;

        
        hX = text.render(nameText, hX, hY, nameColor.get(), shadow);

        
        if (displayHealth.get()) {
            hX = text.render(healthText, hX, hY, healthColor, shadow);
        }

        
        if (displayDistance.get()) {
            text.render(distanceText, hX, hY, distanceColor.get(), shadow);
        }

        text.end();
        NametagUtils.end();
    }

    private void drawNametagBackdrop(double x, double y, double width, double height) {
        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x - 1, y - 1, width + 2, height + 2, background.get());
        Renderer2D.COLOR.render();
    }

    @Override
    public String getInfoString() {
        return Integer.toString(chickenList.size());
    }
}
