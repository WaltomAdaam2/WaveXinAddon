package me.waltom.wavexin.modules.elytrafly;

public final class ElytraFlightLogic {
    private ElytraFlightLogic() {
    }

    public static int targetCoordinate(int enteredCoordinate, boolean netherConversion) {
        return netherConversion ? Math.floorDiv(enteredCoordinate, 8) : enteredCoordinate;
    }

    public static boolean shouldCreateWaypoint(boolean enabled, boolean xaeroAvailable) {
        return enabled && xaeroAvailable;
    }
}
