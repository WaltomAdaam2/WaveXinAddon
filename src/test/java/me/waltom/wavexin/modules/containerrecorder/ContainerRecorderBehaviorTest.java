package me.waltom.wavexin.modules.containerrecorder;

/** Lightweight lifecycle checks that do not require a Minecraft client. */
public final class ContainerRecorderBehaviorTest {
    public static void main(String[] args) {
        defaultRadiusMatchesExistingScans();
        scanClaimsKeepTheRecorderAliveUntilTheLastScanStops();
        manualActivationIsNeverStoppedByAScanClaim();
    }

    private static void defaultRadiusMatchesExistingScans() {
        expect(ContainerRecorderModule.DEFAULT_SCAN_RADIUS == 4, "default scan radius must remain 4 chunks");
    }

    private static void scanClaimsKeepTheRecorderAliveUntilTheLastScanStops() {
        ContainerRecorderClaimState claims = new ContainerRecorderClaimState();
        Object normal = new Object();
        Object spiral = new Object();
        expect(claims.request(normal, false), "first scan should activate the recorder");
        expect(!claims.request(spiral, true), "parallel scan must not re-activate an active recorder");
        expect(!claims.release(normal, true), "first completed scan must keep recorder active");
        expect(claims.release(spiral, true), "last completed scan should stop auto-activated recorder");
    }

    private static void manualActivationIsNeverStoppedByAScanClaim() {
        ContainerRecorderClaimState claims = new ContainerRecorderClaimState();
        Object normal = new Object();
        expect(!claims.request(normal, true), "manual active recorder should not be toggled");
        expect(!claims.release(normal, true), "scan stop must not disable manually active recorder");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
