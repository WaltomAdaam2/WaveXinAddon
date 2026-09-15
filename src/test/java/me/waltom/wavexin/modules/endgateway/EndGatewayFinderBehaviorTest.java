package me.waltom.wavexin.modules.endgateway;

import java.util.List;

public final class EndGatewayFinderBehaviorTest {
    private EndGatewayFinderBehaviorTest() {
    }

    public static void main(String[] args) {
        testCoordinatePacking();
        testVoidHeightIsExcluded();
        testSeedPathsAreIsolated();
        testRoutesRetainEveryCandidate();
        testNearestRouteStartsAtClosestGateway();
    }

    private static void testCoordinatePacking() {
        assertEquals(EndGatewayFinder.pack(-1, 17), EndGatewayFinder.pack(-1, 17), "stable coordinate packing");
        assertTrue(EndGatewayFinder.pack(-1, 17) != EndGatewayFinder.pack(17, -1), "coordinate order");
    }

    private static void testVoidHeightIsExcluded() {
        assertTrue(!EndGatewayFinder.hasSurface(0, 0), "bottom world height is void");
        assertTrue(EndGatewayFinder.hasSurface(1, 0), "terrain above world bottom is valid");
    }

    private static void testSeedPathsAreIsolated() {
        assertTrue(!EndGatewayFinder.visitFilename(1L).equals(EndGatewayFinder.visitFilename(2L)), "visit history by seed");
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

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) throw new AssertionError(description + ": expected=" + expected + " actual=" + actual);
    }

    private static void assertTrue(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
