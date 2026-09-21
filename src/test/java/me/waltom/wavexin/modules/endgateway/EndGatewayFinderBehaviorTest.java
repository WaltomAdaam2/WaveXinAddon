package me.waltom.wavexin.modules.endgateway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class EndGatewayFinderBehaviorTest {
    private EndGatewayFinderBehaviorTest() {
    }

    public static void main(String[] args) {
        EndGatewayPerformanceBehaviorTest.run();
        testCoordinatePacking();
        testLegacyCandidatesUseJavaRandom();
        testVoidHeightIsExcluded();
        testSeedPathsAreIsolated();
        testRoutesRetainEveryCandidate();
        testNearestRouteStartsAtClosestGateway();
        testChunkScanStartsAtCenter();
        testCandidateSelectionDoesNotSpam();
        testArrivalMessageMergesNextTarget();
        testRollingBoundary();
        testTileSchedulerCoverageAndCaching();
        testCalculationBatches();
        testNearestWindowIsBounded();
        testGatewayProgressDuringCalculation();
        testFixedCenterAndLockedSettings();
    }

    private static void testFixedCenterAndLockedSettings() {
        assertEquals(-30000000, EndGatewayFinder.scanCenterCoordinate(true, -30000000, 123), "fixed world boundary center");
        assertEquals(-123, EndGatewayFinder.scanCenterCoordinate(false, 1000, -123), "ordinary mode uses player position");
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger notices = new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(me.waltom.wavexin.gui.TargetCoordinateSetting.canEdit(() -> !running.get(), notices::incrementAndGet), "stopped settings editable");
        running.set(true);
        assertTrue(!me.waltom.wavexin.gui.TargetCoordinateSetting.canEdit(() -> !running.get(), notices::incrementAndGet), "running settings locked");
        assertEquals(1, notices.get(), "blocked edit reports lock");
        running.set(false);
        assertTrue(me.waltom.wavexin.gui.TargetCoordinateSetting.canEdit(() -> !running.get(), notices::incrementAndGet), "stopped settings unlock");
        assertTrue(!EndGatewayFinder.shouldAdvanceView(true, -100, 200, 1000, 30000000, -30000000), "fixed center never advances even outside range");
        assertTrue(EndGatewayFinder.shouldAdvanceView(false, -100, 200, 1000, 30000000, -30000000), "ordinary center continues rolling");
    }

    private static void testGatewayProgressDuringCalculation() {
        var legacy = EndGatewayFinder.GenerationVersion.V1_12;
        var modern = EndGatewayFinder.GenerationVersion.V1_20_4;
        Set<EndGatewayFinder.Gateway> visited = new HashSet<>();
        for (int x = -77; x <= 77; x++) visited.add(new EndGatewayFinder.Gateway(x, 0, legacy));
        visited.add(new EndGatewayFinder.Gateway(0, 0, modern));
        visited.add(new EndGatewayFinder.Gateway(161, 0, legacy));
        int arrived = EndGatewayFinder.countVisitedGateways(visited, 0, 0, 10, legacy);
        assertEquals(155, arrived, "all in-range history available before any candidates");
        assertEquals("Arrived at #155", EndGatewayFinder.arrivalMessage(arrived, null), "startup uses historical arrival number");
        assertEquals(100L, EndGatewayFinder.gatewayProgressPercent(arrived, 0), "history before candidates is capped");
        assertEquals(100L, EndGatewayFinder.gatewayProgressPercent(arrived, 15), "history exceeding computed total is capped");
        assertEquals(50L, EndGatewayFinder.gatewayProgressPercent(arrived, 310), "growing denominator retains historical numerator");
        visited.add(new EndGatewayFinder.Gateway(160, 0, legacy));
        assertEquals(156, EndGatewayFinder.countVisitedGateways(visited, 0, 0, 10, legacy), "new arrival and inclusive circular boundary");
        assertEquals(157, EndGatewayFinder.countVisitedGateways(visited, 0, 0, 10, EndGatewayFinder.GenerationVersion.BOTH), "both includes both versions");
        assertEquals(1, EndGatewayFinder.countVisitedGateways(visited, 0, 0, 10, modern), "single version excludes other history");
        assertEquals(0, EndGatewayFinder.countVisitedGateways(visited, -1000, -1000, 10, legacy), "rolling center excludes old range");
        assertEquals(0L, EndGatewayFinder.gatewayProgressPercent(0, 0), "empty progress");
    }

    private static void testCoordinatePacking() {
        assertEquals(EndGatewayFinder.pack(-1, 17), EndGatewayFinder.pack(-1, 17), "stable coordinate packing");
        assertTrue(EndGatewayFinder.pack(-1, 17) != EndGatewayFinder.pack(17, -1), "coordinate order");
        long packed = EndGatewayFinder.pack(-7, -11);
        assertEquals(-7, (int) (packed >> 32), "negative packed x");
        assertEquals(-11, (int) packed, "negative packed z");
    }

    private static void testLegacyCandidatesUseJavaRandom() {
        long seed = 3763250021837776656L;
        int found = 0;
        for (int chunkZ = -32; chunkZ <= 32; chunkZ++) for (int chunkX = -32; chunkX <= 32; chunkX++) {
            EndGatewayFinder.Gateway expected = legacyCandidate(seed, chunkX, chunkZ);
            EndGatewayFinder.Gateway actual = EndGatewayFinder.legacyCandidate(seed, chunkX, chunkZ);
            assertEquals(expected, actual, "legacy candidate " + chunkX + "," + chunkZ);
            if (actual != null) found++;
        }
        assertTrue(found > 0, "legacy candidate sample has gateway positions");
    }

    private static EndGatewayFinder.Gateway legacyCandidate(long seed, int chunkX, int chunkZ) {
        Random random = new Random(seed);
        long xSeed = random.nextLong() / 2L * 2L + 1L;
        long zSeed = random.nextLong() / 2L * 2L + 1L;
        random.setSeed((long) chunkX * xSeed + (long) chunkZ * zSeed ^ seed);
        if (random.nextInt(700) != 0) return null;
        return new EndGatewayFinder.Gateway((chunkX << 4) + random.nextInt(16), (chunkZ << 4) + random.nextInt(16), EndGatewayFinder.GenerationVersion.V1_12);
    }

    private static void testVoidHeightIsExcluded() {
        assertTrue(!EndGatewayFinder.hasSurface(0, 0), "bottom world height is void");
        assertTrue(EndGatewayFinder.hasSurface(1, 0), "terrain above world bottom is valid");
    }

    private static void testSeedPathsAreIsolated() {
        assertTrue(!EndGatewayFinder.visitFilename(1L).equals(EndGatewayFinder.visitFilename(2L)), "visit history by seed");
        assertEquals(EndGatewayFinder.visitFilename(1L, EndGatewayFinder.GenerationVersion.V1_12), EndGatewayFinder.visitFilename(1L, EndGatewayFinder.GenerationVersion.V1_20_4), "visit history is unified by seed");
        assertEquals(List.of(EndGatewayFinder.GenerationVersion.V1_12, EndGatewayFinder.GenerationVersion.V1_20_4), EndGatewayFinder.scanVersions(EndGatewayFinder.GenerationVersion.BOTH), "both scans both generation versions");
        assertTrue(!new EndGatewayFinder.Gateway(12, 34, EndGatewayFinder.GenerationVersion.V1_12).equals(new EndGatewayFinder.Gateway(12, 34, EndGatewayFinder.GenerationVersion.V1_20_4)), "visit history distinguishes generation versions at the same coordinates");
    }

    private static void testRoutesRetainEveryCandidate() {
        List<EndGatewayFinder.Gateway> gateways = List.of(new EndGatewayFinder.Gateway(10, 0), new EndGatewayFinder.Gateway(30, 0), new EndGatewayFinder.Gateway(60, 0));
        List<Integer> candidates = List.of(0, 1, 2);
        for (EndGatewayFinder.PathAlgorithm algorithm : EndGatewayFinder.PathAlgorithm.values()) {
            List<Integer> route = EndGatewayFinder.route(gateways, candidates, 0, 0, algorithm);
            assertEquals(3, route.size(), algorithm + " route size");
            assertTrue(route.containsAll(candidates), algorithm + " contains candidates");
        }
    }

    private static void testNearestRouteStartsAtClosestGateway() {
        List<EndGatewayFinder.Gateway> gateways = List.of(new EndGatewayFinder.Gateway(100, 0), new EndGatewayFinder.Gateway(10, 0));
        List<Integer> route = EndGatewayFinder.route(gateways, List.of(0, 1), 0, 0, EndGatewayFinder.PathAlgorithm.NEAREST_NEIGHBOR);
        assertEquals(1, route.getFirst(), "nearest route first gateway");
    }

    private static void testChunkScanStartsAtCenter() {
        List<String> chunks = new ArrayList<>();
        EndGatewayFinder.visitChunksFromCenter(-2, 2, -2, 2, 0, 0, (x, z) -> chunks.add(x + "," + z));
        assertEquals("0,0", chunks.getFirst(), "center chunk is scanned first");
        assertEquals(25, chunks.size(), "all chunks scanned once");
        int previousRing = -1;
        for (String chunk : chunks) {
            String[] coordinates = chunk.split(",");
            int ring = Math.max(Math.abs(Integer.parseInt(coordinates[0])), Math.abs(Integer.parseInt(coordinates[1])));
            assertTrue(ring >= previousRing, "chunks expand outward by ring");
            previousRing = ring;
        }
    }

    private static void testCandidateSelectionDoesNotSpam() {
        assertTrue(EndGatewayFinder.shouldSelectTarget(false, -1, false), "first candidate selects a target");
        assertTrue(!EndGatewayFinder.shouldSelectTarget(true, 0, false), "active target suppresses candidate output");
        assertTrue(!EndGatewayFinder.shouldSelectTarget(true, -1, true), "visited candidate does not restart routing");
        assertTrue(EndGatewayFinder.shouldSelectTarget(true, -1, false), "new candidate restores an empty route");
    }

    private static void testArrivalMessageMergesNextTarget() {
        EndGatewayFinder.Gateway next = new EndGatewayFinder.Gateway(-12, 34);
        assertEquals("Arrived at #115 -> (-12, 34)", EndGatewayFinder.arrivalMessage(115, next), "arrival and next target share one line");
        assertEquals("Arrived at #115", EndGatewayFinder.arrivalMessage(115, null), "final arrival has no empty arrow");
    }

    private static void testRollingBoundary() {
        int radiusChunks = EndGatewayFinder.DEFAULT_ROLLING_RADIUS_CHUNKS;
        assertEquals(1000, radiusChunks, "rolling radius default");
        int threshold = radiusChunks * 16 - EndGatewayFinder.PREFETCH_DISTANCE_BLOCKS;
        assertTrue(!EndGatewayFinder.shouldAdvanceView(0, 0, radiusChunks, threshold - 1, 0), "view stays before prefetch boundary");
        assertTrue(EndGatewayFinder.shouldAdvanceView(0, 0, radiusChunks, threshold, 0), "view advances at prefetch boundary");
        assertTrue(!EndGatewayFinder.shouldAdvanceView(-1234, 5678, radiusChunks, -1234, 5678), "negative center stays stable");
    }

    private static void testTileSchedulerCoverageAndCaching() {
        int radiusChunks = 64;
        EndGatewayFinder.TileScheduler scheduler = new EndGatewayFinder.TileScheduler(0, 0, radiusChunks);
        Set<Long> completed = new HashSet<>();
        EndGatewayFinder.Tile first = scheduler.next(completed::contains);
        assertEquals(new EndGatewayFinder.Tile(0, 0), first, "scheduler starts at center tile");
        completed.add(EndGatewayFinder.pack(first.x(), first.z()));

        EndGatewayFinder.Tile tile;
        while ((tile = scheduler.next(completed::contains)) != null) {
            long key = EndGatewayFinder.pack(tile.x(), tile.z());
            assertTrue(completed.add(key), "scheduler emits each tile once");
            assertTrue(EndGatewayFinder.tileIntersectsCircle(0, 0, radiusChunks, tile.x(), tile.z()), "scheduled tile intersects view");
        }
        assertEquals(EndGatewayFinder.countTilesInCircle(0, 0, radiusChunks), (long) completed.size(), "scheduler covers every tile");
        assertEquals(null, new EndGatewayFinder.TileScheduler(0, 0, radiusChunks).next(completed::contains), "completed cache is reused");

        EndGatewayFinder.TileScheduler negativeScheduler = new EndGatewayFinder.TileScheduler(-1234, 5678, radiusChunks);
        Set<Long> negativeTiles = new HashSet<>();
        while ((tile = negativeScheduler.next(negativeTiles::contains)) != null) {
            assertTrue(negativeTiles.add(EndGatewayFinder.pack(tile.x(), tile.z())), "negative scheduler emits each tile once");
        }
        assertEquals(EndGatewayFinder.countTilesInCircle(-1234, 5678, radiusChunks), (long) negativeTiles.size(), "negative scheduler covers every tile");
    }

    private static void testCalculationBatches() {
        assertEquals(976, EndGatewayFinder.calculationTileBudget(1_000_000), "one million chunk budget rounds down to whole tiles");
        assertEquals(2_929, EndGatewayFinder.calculationTileBudget(3_000_000), "default batch budget rounds down to whole tiles");
        assertEquals(97_656, EndGatewayFinder.calculationTileBudget(100_000_000), "maximum batch budget rounds down to whole tiles");
        assertTrue((long) EndGatewayFinder.calculationTileBudget(3_000_000) * EndGatewayFinder.TILE_SIZE_CHUNKS * EndGatewayFinder.TILE_SIZE_CHUNKS <= 3_000_000,
            "batch never exceeds its configured chunk budget");

        EndGatewayFinder.TileScheduler scheduler = new EndGatewayFinder.TileScheduler(-1234, 5678, 96);
        Set<Long> calculated = new HashSet<>();
        int[] batches = {0, 0};
        for (int batch = 0; batch < batches.length; batch++) {
            for (int i = 0; i < 3; i++) {
                EndGatewayFinder.Tile tile = scheduler.next(calculated::contains);
                if (tile == null) break;
                assertTrue(calculated.add(EndGatewayFinder.pack(tile.x(), tile.z())), "batch emits no duplicate tiles");
                batches[batch]++;
            }
        }
        assertEquals(3, batches[0], "first batch stops at its tile budget");
        assertEquals(3, batches[1], "second batch resumes the same cursor");
        while (true) {
            int calculatedThisBatch = 0;
            for (int i = 0; i < 3; i++) {
                EndGatewayFinder.Tile tile = scheduler.next(calculated::contains);
                if (tile == null) break;
                assertTrue(calculated.add(EndGatewayFinder.pack(tile.x(), tile.z())), "later batch emits no duplicate tiles");
                calculatedThisBatch++;
            }
            if (calculatedThisBatch == 0) break;
        }
        assertEquals(EndGatewayFinder.countTilesInCircle(-1234, 5678, 96), (long) calculated.size(), "all batches cover the full range exactly once");
        assertTrue(EndGatewayFinder.shouldQueueNextBatch(10, false, calculated.size(), calculated.size() + 1), "ten unvisited gateways queues the next batch");
        assertTrue(!EndGatewayFinder.shouldQueueNextBatch(11, false, calculated.size(), calculated.size() + 1), "eleven unvisited gateways retains the queued state");
        assertTrue(!EndGatewayFinder.shouldQueueNextBatch(0, true, calculated.size(), calculated.size() + 1), "active calculation cannot queue a duplicate worker");
        assertTrue(!EndGatewayFinder.shouldQueueNextBatch(0, false, calculated.size(), calculated.size()), "completed range never queues another batch");
    }

    private static void testNearestWindowIsBounded() {
        List<EndGatewayFinder.Gateway> gateways = new ArrayList<>();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            gateways.add(new EndGatewayFinder.Gateway(i * 10, 0));
            candidates.add(i);
        }
        List<Integer> window = EndGatewayFinder.nearestWindow(gateways, candidates, 0, 0, 256);
        assertEquals(256, window.size(), "route window is bounded");
        assertEquals(0, window.getFirst(), "route window starts nearby");
        assertEquals(255, window.getLast(), "route window excludes distant candidates");
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(description + ": expected=" + expected + " actual=" + actual);
    }

    private static void assertTrue(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
