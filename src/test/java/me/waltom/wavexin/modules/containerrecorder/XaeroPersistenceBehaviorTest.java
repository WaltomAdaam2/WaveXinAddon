package me.waltom.wavexin.modules.containerrecorder;

import java.util.ArrayList;
import java.util.List;

/** Tests the production insert/save boundary without a Minecraft renderer. */
public final class XaeroPersistenceBehaviorTest {
    public static void run() throws ReflectiveOperationException {
        FakeSet owner = new FakeSet();
        FakeSet otherWorld = new FakeSet();
        FakeWaypoint existing = new FakeWaypoint();
        owner.points.add(existing);
        FakeWaypoint pearl = new FakeWaypoint();
        int[] attempts = {0};
        var result = XaeroWaypointBridge.addPersistent(pearl, owner,
            FakeSet.class.getMethod("add", FakeWaypoint.class),
            FakeWaypoint.class.getMethod("setTemporary", boolean.class), () -> {
                if (++attempts[0] == 1) throw new java.lang.reflect.InvocationTargetException(new java.io.IOException("disk unavailable"));
                owner.saved = List.copyOf(owner.points);
            });
        expect(!result.created() && result.handle() != null, "failed save retains the inserted object for retry");
        expect(!pearl.temporary, "recorder points must be permanent");
        expect(owner.points.size() == 2 && owner.points.contains(existing), "existing points never removed");
        expect(XaeroWaypointBridge.retrySave(result.handle()).created(), "persistence recovers without reinserting");
        expect(XaeroWaypointBridge.retrySave(result.handle()).created(), "repeat save is idempotent");
        expect(owner.saved.size() == 2 && owner.points.size() == 2, "retry cannot duplicate a pearl or container point");
        expect(otherWorld.points.isEmpty(), "retry stays bound to original owner");
    }

    public static final class FakeWaypoint {
        boolean temporary = true;
        public void setTemporary(boolean value) { temporary = value; }
    }

    public static final class FakeSet {
        final List<FakeWaypoint> points = new ArrayList<>();
        List<FakeWaypoint> saved = List.of();
        public void add(FakeWaypoint point) { points.add(point); }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
