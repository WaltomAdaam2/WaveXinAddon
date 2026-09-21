package me.waltom.wavexin.modules.basefinder;

import java.util.ArrayList;
import java.util.List;

/** Exercises the reflection boundary without loading a Minecraft or Xaero session. */
public final class XaeroWaypointBridgeBehaviorTest {
    public static void run() throws ReflectiveOperationException {
        FakeWaypoint waypoint = new FakeWaypoint();
        FakeSet originalSet = new FakeSet();
        FakeSet otherSet = new FakeSet();
        FakeWaypoint userWaypoint = new FakeWaypoint();
        otherSet.waypoints.add(userWaypoint);
        var handle = XaeroWaypointBridge.addTemporary(waypoint, originalSet,
            FakeWaypoint.class.getMethod("setTemporary", boolean.class),
            FakeSet.class.getMethod("add", FakeWaypoint.class),
            FakeSet.class.getMethod("remove", FakeWaypoint.class));
        expect(waypoint.temporary && originalSet.waypoints.contains(waypoint), "temporary flag must be set before insertion");

        XaeroWaypointBridge bridge = new XaeroWaypointBridge();
        originalSet.failRemoval = true;
        expect(!bridge.remove(handle).removed(), "failed removal must be reported for retry");
        expect(originalSet.waypoints.contains(waypoint), "failed removal keeps the handle usable");
        originalSet.failRemoval = false;
        expect(bridge.remove(handle).removed(), "cleanup must work without a current Xaero session");
        expect(originalSet.waypoints.isEmpty(), "cleanup must remove from the original set");
        expect(otherSet.waypoints.contains(userWaypoint), "cleanup must not touch other sets or user waypoints");
        expect(bridge.remove(handle).removed(), "cleanup must be idempotent");

        FakeWaypoint unsupported = new FakeWaypoint();
        try {
            XaeroWaypointBridge.addTemporary(unsupported, originalSet,
                FakeWaypoint.class.getMethod("setTemporary", boolean.class),
                FakeSet.class.getMethod("add", FakeWaypoint.class), null);
            throw new AssertionError("unsupported removal must not create a waypoint");
        } catch (NoSuchMethodException expected) {
            expect(originalSet.waypoints.isEmpty(), "failure must happen before insertion");
        }
    }

    public static final class FakeWaypoint {
        boolean temporary;
        public void setTemporary(boolean value) { temporary = value; }
    }

    public static final class FakeSet {
        final List<FakeWaypoint> waypoints = new ArrayList<>();
        boolean failRemoval;
        public void add(FakeWaypoint waypoint) {
            expect(waypoint.temporary, "a waypoint must never enter the set as a persistent point");
            waypoints.add(waypoint);
        }
        public boolean remove(FakeWaypoint waypoint) {
            if (failRemoval) throw new IllegalStateException("test removal failure");
            return waypoints.remove(waypoint);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
