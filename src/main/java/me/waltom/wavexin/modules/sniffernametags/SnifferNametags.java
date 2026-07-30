package me.waltom.wavexin.modules.sniffernametags;

import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class SnifferNametags extends WaveXinModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Nametag scale.")
        .defaultValue(1.1)
        .min(0.1)
        .build()
    );

    private final Setting<Boolean> displayHealth = sgGeneral.add(new BoolSetting.Builder()
        .name("display-health")
        .description("Shows sniffer health.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> displayDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("display-distance")
        .description("Shows distance to the sniffer.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> maxRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("render-range")
        .description("Only renders sniffer nametags within this range.")
        .defaultValue(64)
        .min(1)
        .sliderMax(256)
        .build()
    );

    private final Setting<SettingColor> background = sgRender.add(new ColorSetting.Builder()
        .name("background")
        .description("The nametag background color.")
        .defaultValue(new SettingColor(0, 0, 0, 75))
        .build()
    );

    private final Setting<SettingColor> nameColor = sgRender.add(new ColorSetting.Builder()
        .name("name-color")
        .description("The nametag text color.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> distanceColor = sgRender.add(new ColorSetting.Builder()
        .name("distance-color")
        .description("The distance text color.")
        .defaultValue(new SettingColor(150, 150, 150))
        .visible(displayDistance::get)
        .build()
    );

    private final Color RED = new Color(255, 25, 25);
    private final Color AMBER = new Color(255, 105, 25);
    private final Color GREEN = new Color(25, 252, 25);

    private final Vector3d pos = new Vector3d();
    private final List<SnifferEntity> snifferList = new ArrayList<>();

    public SnifferNametags() {
        super(WaveXinAddon.CATEGORY, "sniffer-nametags", "Displays custom nametags for sniffer entities.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            snifferList.clear();
            return;
        }

        snifferList.clear();
        Vec3d cameraPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() != EntityType.SNIFFER) continue;
            if (!(entity instanceof SnifferEntity sniffer)) continue;
            if (!isValid(sniffer)) continue;

            double distance = PlayerUtils.distanceToCamera(sniffer);
            if (distance <= maxRange.get()) {
                snifferList.add(sniffer);
            }
        }

        snifferList.sort(Comparator.comparing(e -> e.squaredDistanceTo(cameraPos)));
    }

    @EventHandler
    private void handleWaveRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null) {
            snifferList.clear();
            return;
        }

        boolean shadow = Config.get().customFont.get();

        for (Iterator<SnifferEntity> it = snifferList.iterator(); it.hasNext();) {
            SnifferEntity sniffer = it.next();
            if (!isValid(sniffer)) {
                it.remove();
                continue;
            }

            Utils.set(pos, sniffer, event.tickDelta);
            pos.add(0, getHeight(sniffer), 0);

            if (NametagUtils.to2D(pos, scale.get())) {
                renderSnifferNametag(sniffer, shadow, event.drawContext.getMatrices());
            }
        }
    }

    private boolean isValid(SnifferEntity sniffer) {
        return sniffer != null && !sniffer.isRemoved() && sniffer.isAlive();
    }

    private double getHeight(Entity entity) {
        return entity.getEyeHeight(entity.getPose()) + 0.5;
    }

    private void renderSnifferNametag(SnifferEntity sniffer, boolean shadow, MatrixStack matrices) {
        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos);

        String nameText = WaveXinI18n.tr("entity.wavexin.sniffer_nametags.sniffer", "Sniffer");
        if (sniffer.hasCustomName() && sniffer.getCustomName() != null) {
            nameText = sniffer.getCustomName().getString();
        }

        String healthText = "";
        Color healthColor = GREEN;
        if (displayHealth.get()) {
            float absorption = sniffer.getAbsorptionAmount();
            int health = Math.round(sniffer.getHealth() + absorption);
            double healthPercentage = health / (sniffer.getMaxHealth() + absorption);

            healthText = " " + health;

            if (healthPercentage <= 0.333) healthColor = RED;
            else if (healthPercentage <= 0.666) healthColor = AMBER;
            else healthColor = GREEN;
        }

        String distanceText = "";
        if (displayDistance.get()) {
            double dist = Math.round(PlayerUtils.distanceToCamera(sniffer) * 10.0) / 10.0;
            distanceText = " [" + dist + "m]";
        }

        double nameWidth = text.getWidth(nameText, shadow);
        double healthWidth = displayHealth.get() ? text.getWidth(healthText, shadow) : 0;
        double distanceWidth = displayDistance.get() ? text.getWidth(distanceText, shadow) : 0;
        double heightDown = text.getHeight(shadow);

        double width = nameWidth + healthWidth + distanceWidth;
        double widthHalf = width / 2;

        drawNametagBackdrop(-widthHalf, -heightDown, width, heightDown, matrices);

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

    private void drawNametagBackdrop(double x, double y, double width, double height, MatrixStack matrices) {
        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x - 1, y - 1, width + 2, height + 2, background.get());
        Renderer2D.COLOR.render(matrices);
    }

    @Override
    public String getInfoString() {
        return Integer.toString(snifferList.size());
    }
}
