package me.waltom.wavexin.modules.basefinder;

public final class BaseFinderBehaviorTest {
    private BaseFinderBehaviorTest() {
    }

    public static void main(String[] args) {
        testBlockToChunk();
        testTurnDelayRecovery();
        testCoordinateDistance();
        testSpiralProgressCalculation();
        testTemporaryViewRestore();
        testLockedViewRestore();
        testViewStateTargetChanges();
        testViewStateWorldAndNullCleanup();
        testSprintRestore();
        testSpiralLockViewCancellation();
        testNormalDebugLogGate();
        testNormalRouteCheckpointValidation();
        testPerTargetDebugGate();
        testPearlWaypointLabels();
        testNormalRenderPriority();
        testXaeroWaypointColorIds();
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

    private static void testTurnDelayRecovery() {
        assertEquals(0, BaseFinderStateLogic.clearTurnDelayOutsideTarget(false, 40), "turn delay clears after leaving target");
        assertEquals(40, BaseFinderStateLogic.clearTurnDelayOutsideTarget(true, 40), "turn delay remains while still at target");
        assertEquals(0, BaseFinderStateLogic.clearTurnDelayOutsideTarget(false, 0), "zero turn delay remains zero");
    }

    private static void testCoordinateDistance() {
        assertEquals(100000L, BaseFinderStateLogic.coordinateDistance(-50000, 50000), "coordinate distance keeps sign");
        assertEquals(0L, BaseFinderStateLogic.coordinateDistance(-50000, -50000), "same coordinate distance");
    }

    private static void testSpiralProgressCalculation() {
        ScanProgressManager.ScanProgress firstCorner = ScanProgressManager.calculateProgressFromPosition(6, 0, 0, 0, 6);
        assertEquals(1, firstCorner.totalSegments, "first spiral corner completed segments");
        assertEquals(MapScanDirection.NORTH.ordinal(), firstCorner.currentDir, "first spiral corner next direction");

        ScanProgressManager.ScanProgress farCorner = ScanProgressManager.calculateProgressFromPosition(50004, -50004, 0, 0, 6);
        var completed = ScanProgressManager.calculateCompletedChunkCoordinates(
            farCorner.startX,
            farCorner.startZ,
            farCorner.totalSegments,
            farCorner.chunkStep
        );
        assertEquals(50004, completed.x(), "large-coordinate spiral corner X");
        assertEquals(-50004, completed.z(), "large-coordinate spiral corner Z");

        var completedAfterFour = ScanProgressManager.calculateCompletedChunkCoordinates(0, 0, 4, 6);
        assertEquals(-6, completedAfterFour.x(), "four-segment completed corner X");
        assertEquals(6, completedAfterFour.z(), "four-segment completed corner Z");

        ScanProgressManager.ScanProgress initial = new ScanProgressManager.ScanProgress(0, 0, 0, MapScanDirection.EAST.ordinal(), 0, 1, 6);
        var firstTarget = ScanProgressManager.calculateTargetChunkCoordinates(initial, 6);
        assertEquals(6, firstTarget.x(), "initial spiral target X");
        assertEquals(0, firstTarget.z(), "initial spiral target Z");

        ScanProgressManager.ScanProgress offsetCorner = ScanProgressManager.calculateProgressFromPosition(-49904, 50104, 100, 100, 6);
        var offsetCompleted = ScanProgressManager.calculateCompletedChunkCoordinates(
            offsetCorner.startX,
            offsetCorner.startZ,
            offsetCorner.totalSegments,
            offsetCorner.chunkStep
        );
        assertEquals(-49904, offsetCompleted.x(), "offset large-coordinate spiral corner X");
        assertEquals(50104, offsetCompleted.z(), "offset large-coordinate spiral corner Z");
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

    private static void testSpiralLockViewCancellation() {
        assertFalse(BaseFinderStateLogic.shouldCancelSpiralRotation(true, true, true), "enabled lock view keeps active rotation");
        assertFalse(BaseFinderStateLogic.shouldCancelSpiralRotation(false, false, false), "disabled lock view with no rotation needs no cleanup");
        assertTrue(BaseFinderStateLogic.shouldCancelSpiralRotation(false, true, false), "disabling lock view cancels active rotation");
        assertTrue(BaseFinderStateLogic.shouldCancelSpiralRotation(false, false, true), "disabling lock view cancels pending initial rotation");
    }

    private static void testNormalDebugLogGate() {
        assertFalse(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "CENTERING_TARGET_CHUNK"), "target centering is not warn-worthy");
        assertFalse(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "CENTERING_RESUME_CHUNK"), "resume centering is not warn-worthy");
        assertFalse(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "MOVING"), "normal movement is not warn-worthy");
        assertTrue(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "WAITING_PLAYER_OR_WORLD"), "missing player/world is warn-worthy");
        assertFalse(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "WAITING_CURRENT_CHUNK"), "ordinary chunk wait is not a warning");
        assertFalse(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("STATE", "WAITING_RESUME_CURRENT_CHUNK"), "ordinary resume wait is not a warning");
        assertFalse(BaseFinderStateLogic.shouldLogNormalDebugSnapshot("DEACTIVATE", "MOVING"), "normal deactivation is not a warning");
    }

    private static void testNormalRouteCheckpointValidation() {
        BaseFinderStateLogic.NormalRouteCheckpoint upRight = BaseFinderStateLogic.normalRouteTarget(50763, 51597, 11, 8, BaseFinder.SweepRoute.UP_LEFT_TO_UP_RIGHT);
        assertEquals(50939, upRight.x(), "ring 11 up-right target X");
        assertEquals(51421, upRight.z(), "ring 11 up-right target Z");
        assertTrue(BaseFinderStateLogic.isNormalRouteCheckpoint(50763, 51597, 50939, 51421, 11, 8, BaseFinder.SweepRoute.UP_LEFT_TO_UP_RIGHT), "valid saved checkpoint");

        assertFalse(BaseFinderStateLogic.isNormalRouteCheckpoint(50763, 51597, 50756, 51581, 1, 8, BaseFinder.SweepRoute.UP_LEFT_TO_UP_RIGHT), "mid-route chunk is not a checkpoint");
        assertFalse(BaseFinderStateLogic.isNormalRouteCheckpoint(50624, 50624, 0, 0, 31, 8, BaseFinder.SweepRoute.DOWN_RIGHT_TO_DOWN_LEFT), "login origin chunk is not a checkpoint");
        assertFalse(BaseFinderStateLogic.isNormalRouteCheckpoint(50763, 51597, 50939, 51421, 0, 8, BaseFinder.SweepRoute.UP_LEFT_TO_UP_RIGHT), "ring zero checkpoint is invalid");
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

    private static void testNormalRenderPriority() {
        assertEquals(BaseFinderStateLogic.NormalRenderState.RESUME_CHECKPOINT,
            BaseFinderStateLogic.normalRenderState(true, true, true),
            "resume checkpoint overrides every normal route color");
        assertEquals(BaseFinderStateLogic.NormalRenderState.VISITED,
            BaseFinderStateLogic.normalRenderState(false, true, true),
            "visited chunk immediately overrides current path color");
        assertEquals(BaseFinderStateLogic.NormalRenderState.CURRENT_PATH,
            BaseFinderStateLogic.normalRenderState(false, false, true),
            "unvisited current path keeps current path color");
        assertEquals(BaseFinderStateLogic.NormalRenderState.TARGET,
            BaseFinderStateLogic.normalRenderState(false, false, false),
            "other prepared chunks use target color");
    }

    private static void testXaeroWaypointColorIds() {
        assertEquals(BaseFinder.XaeroWaypointColor.BLACK, BaseFinder.XaeroWaypointColor.fromColorId(0), "Xaero color 0");
        assertEquals(BaseFinder.XaeroWaypointColor.GOLD, BaseFinder.XaeroWaypointColor.fromColorId(6), "Xaero color 6");
        assertEquals(BaseFinder.XaeroWaypointColor.BLUE, BaseFinder.XaeroWaypointColor.fromColorId(9), "Xaero color 9");
        assertEquals(BaseFinder.XaeroWaypointColor.RED, BaseFinder.XaeroWaypointColor.fromColorId(12), "Xaero color 12");
        assertEquals(BaseFinder.XaeroWaypointColor.WHITE, BaseFinder.XaeroWaypointColor.fromColorId(15), "Xaero color 15");
        assertEquals(BaseFinder.XaeroWaypointColor.RANDOM, BaseFinder.XaeroWaypointColor.fromColorId(-1), "unknown Xaero color fallback");
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
