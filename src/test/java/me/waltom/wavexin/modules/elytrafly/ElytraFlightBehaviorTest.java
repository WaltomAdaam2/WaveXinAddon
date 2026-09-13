package me.waltom.wavexin.modules.elytrafly;

public final class ElytraFlightBehaviorTest {
    private ElytraFlightBehaviorTest() {
    }

    public static void main(String[] args) {
        testFixedSpeedWhenDisabled();
        testSpeedRampAndCap();
        testGlideReset();
        testLagbackCooldown();
        testCoordinateConversion();
        testWaypointGate();
    }

    private static void testFixedSpeedWhenDisabled() {
        ElytraSpeedRamp ramp = new ElytraSpeedRamp();
        for (int i = 0; i < 200; i++) ramp.tick(true);
        assertEquals(1.8, ramp.speed(false, 1.8, 0.1, 5.0), "disabled ramp keeps initial speed");
    }

    private static void testSpeedRampAndCap() {
        ElytraSpeedRamp ramp = new ElytraSpeedRamp();
        for (int i = 0; i < 20; i++) ramp.tick(true);
        assertEquals(1.9, ramp.speed(true, 1.8, 0.1, 5.0), "one active second increases speed");

        for (int i = 0; i < 400; i++) ramp.tick(true);
        assertEquals(2.0, ramp.speed(true, 1.8, 0.1, 2.0), "speed never exceeds cap");
        assertEquals(2.5, ramp.speed(true, 2.5, 0.1, 1.8), "maximum is constrained to initial speed");
    }

    private static void testGlideReset() {
        ElytraSpeedRamp ramp = new ElytraSpeedRamp();
        for (int i = 0; i < 40; i++) ramp.tick(true);
        assertEquals(2.0, ramp.speed(true, 1.8, 0.1, 5.0), "speed rises during a glide");
        ramp.tick(false);
        ramp.tick(true);
        assertEquals(1.805, ramp.speed(true, 1.8, 0.1, 5.0), "new glide resets elapsed speed");
    }

    private static void testLagbackCooldown() {
        ElytraSpeedRamp ramp = new ElytraSpeedRamp();
        for (int i = 0; i < 40; i++) ramp.tick(true);
        ramp.onLagback(true);
        assertEquals(ElytraSpeedRamp.LAGBACK_COOLDOWN_TICKS, ramp.cooldownTicks(), "lagback starts five second cooldown");
        assertEquals(1.8, ramp.speed(true, 1.8, 0.1, 5.0), "lagback resets speed immediately");
        for (int i = 0; i < ElytraSpeedRamp.LAGBACK_COOLDOWN_TICKS; i++) ramp.tick(true);
        assertEquals(1.8, ramp.speed(true, 1.8, 0.1, 5.0), "cooldown holds initial speed");
        ramp.tick(true);
        assertEquals(1.805, ramp.speed(true, 1.8, 0.1, 5.0), "ramp resumes after cooldown");

        ramp.onLagback(false);
        assertEquals(0, ramp.cooldownTicks(), "disabled lagback reset is ignored");
    }

    private static void testCoordinateConversion() {
        assertEquals(80, ElytraFlightLogic.targetCoordinate(640, true), "nether X conversion");
        assertEquals(-2, ElytraFlightLogic.targetCoordinate(-9, true), "negative coordinates use floor division");
        assertEquals(640, ElytraFlightLogic.targetCoordinate(640, false), "normal coordinates stay unchanged");
    }

    private static void testWaypointGate() {
        assertFalse(ElytraFlightLogic.shouldCreateWaypoint(false, true), "disabled waypoint does not create");
        assertFalse(ElytraFlightLogic.shouldCreateWaypoint(true, false), "missing Xaero does not create");
        assertTrue(ElytraFlightLogic.shouldCreateWaypoint(true, true), "available Xaero creates waypoint");
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static void assertFalse(boolean value, String label) {
        assertTrue(!value, label);
    }

    private static void assertEquals(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 0.000001) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": expected " + expected + " but got " + actual);
    }
}
