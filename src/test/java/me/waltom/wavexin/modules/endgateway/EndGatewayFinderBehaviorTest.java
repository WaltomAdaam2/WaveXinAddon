package me.waltom.wavexin.modules.endgateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class EndGatewayFinderBehaviorTest {
    private EndGatewayFinderBehaviorTest() {
    }

    public static void main(String[] args) {
        testCoordinatePacking();
        testLegacyCandidatesUseJavaRandom();
        testVoidHeightIsExcluded();
        testSeedPathsAreIsolated();
        testRoutesRetainEveryCandidate();
        testNearestRouteStartsAtClosestGateway();
        testChunkScanStartsAtCenter();
    }

    private static void testCoordinatePacking() {
        assertEquals(EndGatewayFinder.pack(-1, 17), EndGatewayFinder.pack(-1, 17), "stable coordinate packing");
        assertTrue(EndGatewayFinder.pack(-1, 17) != EndGatewayFinder.pack(17, -1), "coordinate order");
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
        assertTrue(!EndGatewayFinder.visitFilename(1L, EndGatewayFinder.GenerationVersion.V1_12).equals(EndGatewayFinder.visitFilename(1L, EndGatewayFinder.GenerationVersion.V1_20_4)), "visit history by generation version");
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

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(description + ": expected=" + expected + " actual=" + actual);
    }

    private static void assertTrue(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
