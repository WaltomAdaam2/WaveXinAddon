package me.waltom.wavexin.modules.killaura;

public final class KillAuraPlusBehaviorTest {
    private KillAuraPlusBehaviorTest() {}
    public static void main(String[] args) {
        check(!KillAuraLogic.ready(10, 10, 20, 1.1), "delay waits beyond vanilla clamp");
        check(KillAuraLogic.ready(11, 10, 20, 1.1), "default delay reaches 1.1");
        equal(1.2, KillAuraLogic.elapsedProgress(12, 10, 20), "progress remains unbounded");
        equal(1, KillAuraLogic.elapsedTicks(1050, 1000), "elapsed timing is independent of pre and post handlers");
        equal(1, KillAuraLogic.elapsedTicks(1050, 1000), "a second phase observes rather than increments elapsed time");
        check(!KillAuraLogic.inRange(6.1, false, 8, 6), "wall limit applies inside target search");
        check(KillAuraLogic.inRange(6, false, 8, 6), "wall limit boundary allowed");
        check(KillAuraLogic.inFov(179, 0, -179, 0, 3), "fov uses wrapped yaw");
        check(!KillAuraLogic.inFov(0, 0, 30, 0, 20), "fov rejects wide target");
        equal(128, KillAuraLogic.channel(0, 255, .5), "cubic midpoint blends colors");
        equal(11, KillAuraLogic.elapsedTicks(550, 0), "pre phase reads wall time");
        equal(11, KillAuraLogic.elapsedTicks(550, 0), "post phase cannot double elapsed time");
        check(!KillAuraLogic.ready(11, 10, 10, 1.1), "half TPS delays attacks");
        check(KillAuraLogic.ready(22, 10, 10, 1.1), "half TPS eventually attacks");
        check(!KillAuraLogic.inRange(6.1, true, 6, 6), "fallback target outside attack range cannot attack");
        check(!KillAuraLogic.inFov(9, 0, 90, 0, 20), "one yaw step does not bypass look gate");
        check(KillAuraLogic.inFov(81, 0, 90, 0, 20), "continued steps reach look gate");
        for (KillAuraLogic.AuraEase ease : KillAuraLogic.AuraEase.values()) {
            equal(0, ease.apply(0), "easing begins at zero");
            equal(1, ease.apply(1), "easing ends at one");
        }
        equal(.03125, KillAuraLogic.AuraEase.QUAD_IN_OUT.apply(.25), "source quad-in-out behavior");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static void equal(double expected, double actual, String label) { if (Math.abs(expected - actual) > .00001) throw new AssertionError(label + ": " + actual); }
}
