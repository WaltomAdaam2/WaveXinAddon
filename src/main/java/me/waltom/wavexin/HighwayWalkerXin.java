/*
package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;

public class HighwayWalkerXin extends WaveXinModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<HighwayDirection> highwayDirection = sgGeneral.add(new EnumSetting.Builder<HighwayDirection>()
        .name("Highway Direction")
        .description("Highway direction using Minecraft X/Z coordinates.")
        .defaultValue(HighwayDirection.POS_Z)
        .build()
    );

    private final Setting<Double> customYaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("Custom Yaw")
        .description("Custom target yaw.")
        .defaultValue(0.0)
        .min(-180.0)
        .max(180.0)
        .visible(() -> highwayDirection.get() == HighwayDirection.CUSTOM)
        .build()
    );

    private final Setting<Double> rotationSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("Rotation Speed")
        .description("Maximum yaw correction per tick.")
        .defaultValue(6.0)
        .min(0.1)
        .max(30.0)
        .sliderMin(0.1)
        .sliderMax(30.0)
        .build()
    );

    private final Setting<Double> deadzone = sgGeneral.add(new DoubleSetting.Builder()
        .name("Deadzone")
        .description("Yaw difference ignored by the soft lock.")
        .defaultValue(0.25)
        .min(0.0)
        .max(10.0)
        .sliderMin(0.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("Lock Pitch")
        .description("Softly locks pitch toward the target pitch.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> targetPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("Target Pitch")
        .description("Target pitch for pitch lock.")
        .defaultValue(0.0)
        .min(-90.0)
        .max(90.0)
        .visible(lockPitch::get)
        .build()
    );

    private final Setting<Double> pitchRotationSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("Pitch Rotation Speed")
        .description("Maximum pitch correction per tick.")
        .defaultValue(6.0)
        .min(0.1)
        .max(30.0)
        .sliderMin(0.1)
        .sliderMax(30.0)
        .visible(lockPitch::get)
        .build()
    );

    private final Setting<Boolean> autoWalk = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Walk")
        .description("Automatically holds the forward key.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sprint = sgGeneral.add(new BoolSetting.Builder()
        .name("Sprint")
        .description("Sprints while walking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnScreen = sgGeneral.add(new BoolSetting.Builder()
        .name("Pause On Screen")
        .description("Pauses rotation and walking while a screen is open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("Chat Feedback")
        .description("Shows the selected highway direction on activate.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> renderRoute = sgRender.add(new BoolSetting.Builder()
        .name("Render Route")
        .description("Renders the highway route.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> routeLength = sgRender.add(new IntSetting.Builder()
        .name("Route Length")
        .description("Route length in blocks.")
        .defaultValue(160)
        .min(16)
        .max(1024)
        .sliderMin(16)
        .sliderMax(1024)
        .build()
    );

    private final Setting<Integer> markerSpacing = sgRender.add(new IntSetting.Builder()
        .name("Marker Spacing")
        .description("Spacing between route markers.")
        .defaultValue(8)
        .min(2)
        .max(64)
        .sliderMin(2)
        .sliderMax(64)
        .build()
    );

    private final Setting<Double> routeWidth = sgRender.add(new DoubleSetting.Builder()
        .name("Route Width")
        .description("Width of route markers.")
        .defaultValue(2.5)
        .min(0.25)
        .max(8.0)
        .sliderMin(0.25)
        .sliderMax(8.0)
        .build()
    );

    private final Setting<Double> routeYOffset = sgRender.add(new DoubleSetting.Builder()
        .name("Route Y Offset")
        .description("Vertical render offset.")
        .defaultValue(0.02)
        .build()
    );

    private final Setting<SettingColor> routeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("Route Side Color")
        .description("Route marker side color.")
        .defaultValue(new SettingColor(0, 180, 255, 35))
        .build()
    );

    private final Setting<SettingColor> routeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("Route Line Color")
        .description("Route marker line color.")
        .defaultValue(new SettingColor(0, 220, 255, 180))
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("ShapeMode")
        .description("Route marker shape mode.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private boolean forcingForward;

    public HighwayWalkerXin() {
        super(WaveXinAddon.CATEGORY, "highway-walker-xin", "Softly follows Nether highway directions and renders a route.");
    }

    @Override
    public void onActivate() {
        forcingForward = false;

        if (chatFeedback.get()) {
            info("Highway direction: %s (%s)", highwayDirection.get(), formatYaw(getTargetYaw()));
        }
    }

    @Override
    public void onDeactivate() {
        releaseForward();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            releaseForward();
            return;
        }

        if (pauseOnScreen.get() && mc.currentScreen != null) {
            releaseForward();
            return;
        }

        rotateYaw();
        rotatePitch();

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

    @EventHandler
    private void handleWaveRender(Render3DEvent event) {
        if (!renderRoute.get() || mc.player == null || mc.world == null) return;

        double yaw = getTargetYaw();
        double radians = Math.toRadians(yaw);
        double dirX = -Math.sin(radians);
        double dirZ = Math.cos(radians);
        double startX = mc.player.getX();
        double startZ = mc.player.getZ();
        double y = Math.floor(mc.player.getY()) + routeYOffset.get();
        double halfWidth = routeWidth.get() / 2.0;

        for (int i = 0; i <= routeLength.get(); i += markerSpacing.get()) {
            double x = startX + dirX * i;
            double z = startZ + dirZ * i;

            event.renderer.box(
                x - halfWidth, y, z - halfWidth,
                x + halfWidth, y + 0.04, z + halfWidth,
                routeSideColor.get(), routeLineColor.get(), shapeMode.get(), 0
            );
        }
    }

    private void rotateYaw() {
        double targetYaw = getTargetYaw();
        double currentYaw = mc.player.getYaw();
        double difference = MathHelper.wrapDegrees(targetYaw - currentYaw);

        if (Math.abs(difference) <= deadzone.get()) return;

        double step = MathHelper.clamp(difference, -rotationSpeed.get(), rotationSpeed.get());
        mc.player.setYaw((float) (currentYaw + step));
    }

    private void rotatePitch() {
        if (!lockPitch.get()) return;

        double currentPitch = mc.player.getPitch();
        double difference = targetPitch.get() - currentPitch;

        if (Math.abs(difference) <= deadzone.get()) return;

        double step = MathHelper.clamp(difference, -pitchRotationSpeed.get(), pitchRotationSpeed.get());
        mc.player.setPitch((float) MathHelper.clamp(currentPitch + step, -90.0, 90.0));
    }

    private void releaseForward() {
        if (!forcingForward) return;

        mc.options.forwardKey.setPressed(false);
        forcingForward = false;
    }

    private double getTargetYaw() {
        HighwayDirection direction = highwayDirection.get();
        if (direction == null) return 0.0;
        if (direction == HighwayDirection.CUSTOM) return customYaw.get();
        return direction.yaw;
    }

    private String formatYaw(double yaw) {
        if (yaw == Math.rint(yaw)) return Integer.toString((int) yaw);
        return String.format(Locale.ROOT, "%.2f", yaw);
    }

    @Override
    public String getInfoString() {
        HighwayDirection direction = highwayDirection.get();
        String name = direction == null ? "Unknown" : direction.toString();
        return name + " (" + formatYaw(getTargetYaw()) + ")";
    }

    public enum HighwayDirection {
        POS_X("+X", -90.0),
        NEG_X("-X", 90.0),
        POS_Z("+Z", 0.0),
        NEG_Z("-Z", 180.0),
        POS_X_POS_Z("+X +Z", -45.0),
        POS_X_NEG_Z("+X -Z", -135.0),
        NEG_X_POS_Z("-X +Z", 45.0),
        NEG_X_NEG_Z("-X -Z", 135.0),
        CUSTOM("Custom", 0.0);

        private final String title;
        private final double yaw;

        HighwayDirection(String title, double yaw) {
            this.title = title;
            this.yaw = yaw;
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
*/