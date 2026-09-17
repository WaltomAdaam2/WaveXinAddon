package me.waltom.wavexin.modules.containerrecorder;

/** Lightweight lifecycle checks that do not require a Minecraft client. */
public final class ContainerRecorderBehaviorTest {
    public static void main(String[] args) {
        defaultRadiusMatchesExistingScans();
        scanClaimsKeepTheRecorderAliveUntilTheLastScanStops();
        manualActivationIsStoppedByTheLastScanClaim();
        unlinkedScansDoNotStopTheRecorder();
        manualToggleDoesNotReleaseScanOwnership();
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

    private static void manualActivationIsStoppedByTheLastScanClaim() {
        ContainerRecorderClaimState claims = new ContainerRecorderClaimState();
        Object normal = new Object();
        expect(!claims.request(normal, true), "manual active recorder should not be toggled");
        expect(claims.release(normal, true), "the last scan claim should disable a manually active recorder");
    }

    private static void unlinkedScansDoNotStopTheRecorder() {
        ContainerRecorderClaimState claims = new ContainerRecorderClaimState();
        expect(!claims.release(new Object(), true), "a scan without a request cannot stop a manual recorder");
        Object linked = new Object();
        claims.request(linked, true);
        expect(!claims.release(new Object(), true), "an unrelated stop cannot release a linked claim");
        expect(claims.release(linked, true), "the linked scan must still own its request");
        expect(!claims.release(linked, true), "duplicate release cannot stop a newly enabled recorder");
    }

    private static void manualToggleDoesNotReleaseScanOwnership() {
        ContainerRecorderClaimState claims = new ContainerRecorderClaimState();
        Object linked = new Object();
        expect(claims.request(linked, false), "linked scan enables the recorder");
        expect(!claims.request(linked, true), "duplicate start must not toggle the recorder");
        // Recorder activation/deactivation callbacks do not release its owning scan's request.
        expect(claims.release(linked, true), "after a manual off/on the owning scan still closes it");
        claims.request(linked, false);
        expect(!claims.release(linked, false), "stopping a scan must not turn an already disabled recorder on");
        expect(!claims.release(linked, true), "inactive release must still discard the request");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
