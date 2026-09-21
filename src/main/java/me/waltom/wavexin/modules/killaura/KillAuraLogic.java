package me.waltom.wavexin.modules.killaura;

final class KillAuraLogic {
    private KillAuraLogic() {}

    static double elapsedProgress(int ticks, double ticksPerAttack, double tps) {
        return Math.floor(Math.max(0, ticks) * Math.max(0, Math.min(1, tps / 20.0))) / Math.max(0.0001, ticksPerAttack);
    }

    static boolean ready(int ticks, double ticksPerAttack, double tps, double required) {
        return elapsedProgress(ticks, ticksPerAttack, tps) >= required;
    }

    static int elapsedTicks(long nowMillis, long resetMillis) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (nowMillis - resetMillis) / 50L));
    }

    static boolean inRange(double distance, boolean visible, double range, double wallRange) {
        return distance <= range && (visible || distance <= wallRange);
    }

    static boolean inFov(double yaw, double pitch, double targetYaw, double targetPitch, double fov) {
        double yawDelta = wrap(targetYaw - yaw);
        double pitchDelta = targetPitch - pitch;
        return yawDelta * yawDelta + pitchDelta * pitchDelta <= fov * fov;
    }

    static double wrap(double value) {
        value %= 360.0;
        if (value >= 180) value -= 360;
        if (value < -180) value += 360;
        return value;
    }

    static int channel(int from, int to, double progress) {
        return (int) Math.max(0, Math.min(255, Math.round(from + (to - from) * progress)));
    }

    static double cubicInOut(double progress) {
        double t = Math.max(0, Math.min(1, progress));
        return t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    enum AuraEase {
        LINEAR, SINE_OUT, SINE_IN_OUT, CUBIC_IN, CUBIC_OUT, CUBIC_IN_OUT,
        QUAD_IN, QUAD_OUT, QUAD_IN_OUT, QUART_IN, QUART_OUT, QUART_IN_OUT,
        QUINT_IN, QUINT_OUT, QUINT_IN_OUT, CIRC_IN, CIRC_OUT, CIRC_IN_OUT,
        EXPO, BACK_OUT, BACK_IN_OUT, BOUNCE;

        double apply(double progress) {
            double t = Math.max(0, Math.min(1, progress));
            return switch (this) {
                case LINEAR -> t;
                case SINE_OUT -> Math.sin(t * Math.PI / 2);
                case SINE_IN_OUT -> (1 - Math.cos(t * Math.PI)) / 2;
                case CUBIC_IN -> t * t * t;
                case CUBIC_OUT -> 1 - Math.pow(1 - t, 3);
                case CUBIC_IN_OUT -> cubicInOut(t);
                case QUAD_IN -> t * t;
                case QUAD_OUT -> 1 - (1 - t) * (1 - t);
                // Alien's QuadInOut uses the same quartic curve as QuartInOut.
                case QUAD_IN_OUT, QUART_IN_OUT -> t < .5 ? 8 * Math.pow(t, 4) : 1 - Math.pow(2 - 2 * t, 4) / 2;
                case QUART_IN -> Math.pow(t, 4);
                case QUART_OUT -> 1 - Math.pow(1 - t, 4);
                case QUINT_IN -> Math.pow(t, 5);
                case QUINT_OUT -> 1 - Math.pow(1 - t, 5);
                case QUINT_IN_OUT -> t < .5 ? 16 * Math.pow(t, 5) : 1 - Math.pow(2 - 2 * t, 5) / 2;
                case CIRC_IN -> 1 - Math.sqrt(1 - t * t);
                case CIRC_OUT -> Math.sqrt(1 - (t - 1) * (t - 1));
                case CIRC_IN_OUT -> t < .5 ? (1 - Math.sqrt(1 - 4 * t * t)) / 2 : (1 + Math.sqrt(1 - Math.pow(2 - 2 * t, 2))) / 2;
                case EXPO -> t == 0 || t == 1 ? t : t < .5 ? Math.pow(2, 20 * t - 10) / 2 : (2 - Math.pow(2, 10 - 20 * t)) / 2;
                case BACK_OUT -> 1 + 2.70158 * Math.pow(t - 1, 3) + 1.70158 * Math.pow(t - 1, 2);
                case BACK_IN_OUT -> {
                    double c = 1.70158 * 1.525;
                    yield t < .5 ? Math.pow(2 * t, 2) * ((c + 1) * 2 * t - c) / 2
                        : (Math.pow(2 * t - 2, 2) * ((c + 1) * (2 * t - 2) + c) + 2) / 2;
                }
                case BOUNCE -> bounce(t);
            };
        }

        private static double bounce(double t) {
            if (t < 1 / 2.75) return 7.5625 * t * t;
            if (t < 2 / 2.75) { t -= 1.5 / 2.75; return 7.5625 * t * t + .75; }
            if (t < 2.5 / 2.75) { t -= 2.25 / 2.75; return 7.5625 * t * t + .9375; }
            t -= 2.625 / 2.75;
            return 7.5625 * t * t + .984375;
        }
    }
}
