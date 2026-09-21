package me.waltom.wavexin.modules.elytrafly;

/**
 * Per-glide elytra speed state shared by the manual and target-path flight modules.
 */
public final class ElytraSpeedRamp {
    public static final int LAGBACK_COOLDOWN_TICKS = 20 * 5;

    private int activeGlideTicks;
    private int cooldownTicks;
    private boolean gliding;

    public void reset() {
        activeGlideTicks = 0;
        cooldownTicks = 0;
        gliding = false;
    }

    public void tick(boolean isGliding) {
        tick(isGliding, true);
    }

    public void tick(boolean isGliding, boolean advanceAcceleration) {
        if (!isGliding) {
            activeGlideTicks = 0;
            cooldownTicks = 0;
            gliding = false;
            return;
        }

        if (!gliding) {
            activeGlideTicks = 0;
            gliding = true;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        if (advanceAcceleration) activeGlideTicks++;
    }

    public void onLagback(boolean resetAfterLagback) {
        if (!resetAfterLagback) return;
        activeGlideTicks = 0;
        cooldownTicks = LAGBACK_COOLDOWN_TICKS;
    }

    public double speed(boolean enabled, double initialSpeed, double increasePerSecond, double maxSpeed) {
        double initial = Math.max(0.1, initialSpeed);
        double maximum = Math.max(initial, maxSpeed);
        if (!enabled || cooldownTicks > 0) return initial;

        return Math.min(maximum, initial + Math.max(0.0, increasePerSecond) * activeGlideTicks / 20.0);
    }

    public int cooldownTicks() {
        return cooldownTicks;
    }
}
