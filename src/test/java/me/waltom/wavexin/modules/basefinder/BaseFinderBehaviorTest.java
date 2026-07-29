package me.waltom.wavexin.modules.basefinder;

public final class BaseFinderBehaviorTest {
    private BaseFinderBehaviorTest() {
    }

    public static void main(String[] args) {
        testBlockToChunk();
        testTemporaryViewRestore();
        testLockedViewRestore();
        testViewStateTargetChanges();
        testViewStateWorldAndNullCleanup();
        testSprintRestore();
        testNormalDebugLogGate();
        testPerTargetDebugGate();
        testPearlWaypointLabels();
    }

    private static void testBlockToChunk() {
        assertEquals(0, BaseFinderStateLogic.blockToChunk(0), "chunk 0");
        assertEquals(0, BaseFinderStateLogic.blockToChunk(1), "chunk 1");
        assertEquals(0, BaseFinderStateLogic.blockToChunk(15), "chunk 15");
        assertEquals(1, BaseFinderStateLogic.blockToChunk(16), "chunk 16");
        assertEquals(1, BaseFinderStateLogic.blockToChunk(17), "chunk 17");
        assertEquals(-1, BaseFinderStateLogic.blockToChunk(-1), "chunk -1");
        assertEquals(-1, BaseFinderStateLogic.blockToChunk(-15), "chunk -15");
        assertEquals(-1, BaseFinderStateLogic.blockToChunk(-16), "chunk -16");
        assertEquals(-2, BaseFinderStateLogic.blockToChunk(-17), "chunk -17");
        assertEquals(7716049, BaseFinderStateLogic.blockToChunk(123456789), "large positive chunk");
        assertEquals(-7716050, BaseFinderStateLogic.blockToChunk(-123456789), "large negative chunk");
    }

    private static void testTemporaryViewRestore() {
        Object player = new Object();
        Object world = new Object();
        BaseFinderStateLogic.ViewRotationState state = new BaseFinderStateLogic.ViewRotationState();

        state.captureIfNeeded(player, world, 10.0f, 20.0f, 30.0f, 40.0f, true);
        assertTrue(state.pending(), "temporary view is pending");
        assertTrue(state.shouldRestoreAfterTick(), "temporary view restores after tick");

        BaseFinderStateLogic.Snapshot snapshot = state.consumeRestore(player, world);
        assertSnapshot(snapshot, 10.0f, 20.0f, 30.0f, 40.0f, "temporary view restore");
        assertFalse(state.pending(), "temporary view restored once");
        assertNull(state.consumeRestore(player, world), "temporary view second restore is empty");
    }

    private static void testLockedViewRestore() {
        Object player = new Object();
        Object world = new Object();
        BaseFinderStateLogic.ViewRotationState state = new BaseFinderStateLogic.ViewRotationState();

        state.captureIfNeeded(player, world, -45.0f, 12.0f, -44.0f, -43.0f, false);
        assertTrue(state.pending(), "locked view is pending until explicit stop");
        assertFalse(state.shouldRestoreAfterTick(), "locked view is not post-tick restored");

        BaseFinderStateLogic.Snapshot snapshot = state.consumeRestore(player, world);
        assertSnapshot(snapshot, -45.0f, 12.0f, -44.0f, -43.0f, "locked view restore on stop");
        assertFalse(state.pending(), "locked view restored once");
    }

    private static void testViewStateTargetChanges() {
        Object player = new Object();
        Object world = new Object();
        BaseFinderStateLogic.ViewRotationState state = new BaseFinderStateLogic.ViewRotationState();

        state.captureIfNeeded(player, world, 5.0f, 6.0f, 7.0f, 8.0f, true);
        state.captureIfNeeded(player, world, 50.0f, 60.0f, 70.0f, 80.0f, true);
        assertSnapshot(state.consumeRestore(player, world), 5.0f, 6.0f, 7.0f, 8.0f, "target change keeps original view until restore");

        state.captureIfNeeded(player, world, 50.0f, 60.0f, 70.0f, 80.0f, true);
        assertSnapshot(state.consumeRestore(player, world), 50.0f, 60.0f, 70.0f, 80.0f, "later scan captures fresh view");
    }

    private static void testViewStateWorldAndNullCleanup() {
        Object player = new Object();
        Object world = new Object();
        Object nextWorld = new Object();
        BaseFinderStateLogic.ViewRotationState state = new BaseFinderStateLogic.ViewRotationState();

        state.captureIfNeeded(player, world, 1.0f, 2.0f, 3.0f, 4.0f, true);
        assertNull(state.consumeRestore(player, nextWorld), "stale world view is not restored");
        assertFalse(state.pending(), "stale world clears view state");

        state.captureIfNeeded(player, world, 1.0f, 2.0f, 3.0f, 4.0f, true);
        state.captureIfNeeded(null, world, 9.0f, 9.0f, 9.0f, 9.0f, true);
        assertFalse(state.pending(), "null player clears view state");

        state.captureIfNeeded(player, world, 1.0f, 2.0f, 3.0f, 4.0f, true);
        state.captureIfNeeded(player, null, 9.0f, 9.0f, 9.0f, 9.0f, true);
        assertFalse(state.pending(), "null world clears view state");
    }

    private static void testSprintRestore() {
        Object player = new Object();
        Object world = new Object();
        Object nextWorld = new Object();
        BaseFinderStateLogic.SprintState state = new BaseFinderStateLogic.SprintState();

        state.captureIfNeeded(player, world, false);
        state.captureIfNeeded(player, world, true);
        assertEquals(Boolean.FALSE, state.consumeRestore(player, world), "initial non-sprint restored");
        assertNull(state.consumeRestore(player, world), "sprint restore is idempotent");

        state.captureIfNeeded(player, world, true);
        assertEquals(Boolean.TRUE, state.consumeRestore(player, world), "initial sprint restored");

        state.captureIfNeeded(player, world, false);
        assertNull(state.consumeRestore(player, nextWorld), "stale world sprint is not restored");
        assertFalse(state.pending(), "stale world clears sprint state");

        state.captureIfNeeded(player, world, true);
        state.captureIfNeeded(null, world, false);
        assertFalse(state.pending(), "null player clears sprint state");
    }

    private static void testNormalDebugLogGate() {
        assertFalse(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "CENTERING_TARGET_CHUNK"), "target centering is not warn-worthy");
        assertFalse(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "CENTERING_RESUME_CHUNK"), "resume centering is not warn-worthy");
        assertFalse(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "MOVING"), "normal movement is not warn-worthy");
        assertTrue(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "WAITING_PLAYER_OR_WORLD"), "missing player/world is warn-worthy");
        assertTrue(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "WAITING_CURRENT_CHUNK"), "current chunk wait is warn-worthy");
        assertTrue(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("DEACTIVATE", "MOVING"), "deactivate snapshot remains enabled");
    }

    private static void testPerTargetDebugGate() {
        BaseFinderStateLogic.PerTargetDebugGate gate = new BaseFinderStateLogic.PerTargetDebugGate();
        assertFalse(gate.shouldEmit(false, 1, 2, 3), "debug disabled emits nothing");
        assertTrue(gate.shouldEmit(true, 1, 2, 3), "first target emits");
        assertFalse(gate.shouldEmit(true, 1, 2, 3), "same target does not spam");
        assertTrue(gate.shouldEmit(true, 1, 2, 4), "segment change emits");
        assertTrue(gate.shouldEmit(true, 2, 2, 4), "target change emits");
        gate.clear();
        assertTrue(gate.shouldEmit(true, 2, 2, 4), "clear allows target again");
    }

    private static void testPearlWaypointLabels() {
        assertEquals("Pearl 1", BaseFinderStateLogic.pearlWaypointName(1), "first pearl waypoint name");
        assertEquals("P1", BaseFinderStateLogic.pearlWaypointAlias(1), "first pearl waypoint alias");
        assertEquals("Pearl 2", BaseFinderStateLogic.pearlWaypointName(2), "second pearl waypoint name");
        assertEquals("P2", BaseFinderStateLogic.pearlWaypointAlias(2), "second pearl waypoint alias");
        assertEquals("Pearl 1", BaseFinderStateLogic.pearlWaypointName(0), "pearl waypoint name clamps low values");
        assertEquals("P1", BaseFinderStateLogic.pearlWaypointAlias(0), "pearl waypoint alias clamps low values");
    }

    private static void assertSnapshot(BaseFinderStateLogic.Snapshot snapshot, float yaw, float pitch, float headYaw, float bodyYaw, String label) {
        if (snapshot == null) throw new AssertionError(label + " snapshot should not be null");
        assertEquals(yaw, snapshot.yaw(), label + " yaw");
        assertEquals(pitch, snapshot.pitch(), label + " pitch");
        assertEquals(headYaw, snapshot.headYaw(), label + " head yaw");
        assertEquals(bodyYaw, snapshot.bodyYaw(), label + " body yaw");
    }

    private static void assertTrue(boolean actual, String label) {
        if (!actual) throw new AssertionError(label + " should be true");
    }

    private static void assertFalse(boolean actual, String label) {
        if (actual) throw new AssertionError(label + " should be false");
    }

    private static void assertNull(Object actual, String label) {
        if (actual != null) throw new AssertionError(label + " should be null but got [" + actual + "]");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected [" + expected + "] but got [" + actual + "]");
    }
}
