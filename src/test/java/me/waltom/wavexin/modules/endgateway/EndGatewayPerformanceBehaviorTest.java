package me.waltom.wavexin.modules.endgateway;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import me.waltom.wavexin.modules.endgateway.EndGatewayFinder.Gateway;

/** Compare optimized selection/rendering to the previous full scans, including ties. */
final class EndGatewayPerformanceBehaviorTest {
    static void run() {
        var cancelledScheduler = new EndGatewayFinder.TileScheduler(0, 0, 100000);
        int[] checked = {0};
        expect(cancelledScheduler.next(key -> { checked[0]++; return true; }, () -> checked[0] >= 10) == null && checked[0] == 10,
            "cancel a large cached range promptly instead of walking the whole area");
        Random random = new Random(7251);
        List<Gateway> gateways = new ArrayList<>();
        List<Integer> candidates = new ArrayList<>();
        EndGatewayFinder.GatewayRenderIndex index = new EndGatewayFinder.GatewayRenderIndex();
        for (int i = 0; i < 100_000; i++) {
            Gateway gateway = new Gateway(random.nextInt(200_001) - 100_000, random.nextInt(200_001) - 100_000,
                i % 2 == 0 ? EndGatewayFinder.GenerationVersion.V1_12 : EndGatewayFinder.GenerationVersion.V1_20_4);
            gateways.add(gateway);
            candidates.add(i);
            index.add(i, gateway);
        }
        // Exact negative tile boundaries and duplicate positions in Both mode.
        for (int x : new int[] {-1024, -512, -1, 0, 512, 1024}) {
            for (var version : EndGatewayFinder.scanVersions(EndGatewayFinder.GenerationVersion.BOTH)) {
                index.add(gateways.size(), new Gateway(x, 0, version));
                candidates.add(gateways.size());
                gateways.add(new Gateway(x, 0, version));
            }
        }
        Collections.shuffle(candidates, random);
        for (int limit : new int[] {0, 1, 256, gateways.size() + 1}) {
            expect(sortedWindow(gateways, candidates, 0, 0, limit).equals(EndGatewayFinder.nearestWindow(gateways, candidates, 0, 0, limit)), "stable nearest window " + limit);
        }
        IntArrayList actual = new IntArrayList();
        for (double x : new double[] {-30000000, -1024.5, -512, -.5, 0, 512.5, 30000000}) {
            for (int radius : new int[] {64, 512, 1024}) {
                index.collect(gateways, x, 0, radius, actual);
                List<Integer> expected = new ArrayList<>();
                for (int i = 0; i < gateways.size(); i++) {
                    Gateway gateway = gateways.get(i);
                    if (square(gateway.x(), gateway.z(), x, 0) <= (double) radius * radius) expected.add(i);
                }
                expect(expected.equals(actual), "render set and draw order at " + x + " radius " + radius);
            }
        }
        index.clear();
        index.collect(gateways, 0, 0, 1024, actual);
        expect(actual.isEmpty(), "range rebuild clears old render entries");

        for (int size : new int[] {0, 1, 2, 16, 64, 256}) {
            List<Integer> sample = new ArrayList<>(candidates.subList(0, size));
            expect(oldTsp(gateways, sample, 17, -23).equals(EndGatewayFinder.route(gateways, sample, 17, -23, EndGatewayFinder.PathAlgorithm.TSP)), "TSP route equivalence " + size);
        }
        List<Gateway> ties = List.of(new Gateway(-10, 0), new Gateway(10, 0), new Gateway(0, 10), new Gateway(0, -10), new Gateway(10, 0));
        List<Integer> tiedCandidates = List.of(3, 2, 4, 1, 0);
        expect(oldTsp(ties, tiedCandidates, 0, 0).equals(EndGatewayFinder.route(ties, tiedCandidates, 0, 0, EndGatewayFinder.PathAlgorithm.TSP)), "TSP ties and colocated versions");

        // Timings are informational, never a flaky pass/fail threshold.
        long[] oldTimes = new long[7], newTimes = new long[7];
        for (int i = 0; i < 9; i++) {
            long start = System.nanoTime();
            var old = sortedWindow(gateways, candidates, 17, -23, 256);
            long middle = System.nanoTime();
            var improved = EndGatewayFinder.nearestWindow(gateways, candidates, 17, -23, 256);
            long end = System.nanoTime();
            expect(old.equals(improved), "benchmark result equality");
            if (i >= 2) { oldTimes[i - 2] = middle - start; newTimes[i - 2] = end - middle; }
        }
        java.util.Arrays.sort(oldTimes); java.util.Arrays.sort(newTimes);
        System.out.printf("Nearest window (100k, median): full-sort=%.2f ms, bounded-heap=%.2f ms%n", oldTimes[3] / 1e6, newTimes[3] / 1e6);
    }

    private static List<Integer> sortedWindow(List<Gateway> gateways, List<Integer> candidates, double x, double z, int max) {
        List<Integer> result = new ArrayList<>(candidates);
        result.sort(Comparator.comparingDouble(i -> square(gateways.get(i).x() + .5, gateways.get(i).z() + .5, x, z)));
        return new ArrayList<>(result.subList(0, Math.min(max, result.size())));
    }

    private static List<Integer> oldTsp(List<Gateway> gateways, List<Integer> candidates, double x, double z) {
        if (candidates.size() <= 2) return new ArrayList<>(candidates);
        List<Integer> result = new ArrayList<>();
        int first = candidates.getFirst();
        double furthest = -1;
        for (int candidate : candidates) {
            Gateway gateway = gateways.get(candidate);
            double d = square(gateway.x() + .5, gateway.z() + .5, x, z);
            if (d > furthest) { furthest = d; first = candidate; }
        }
        result.add(first);
        while (result.size() < candidates.size()) {
            int selected = -1;
            double selectedDistance = -1;
            for (int candidate : candidates) {
                if (result.contains(candidate)) continue;
                double nearest = Double.POSITIVE_INFINITY;
                for (int current : result) nearest = Math.min(nearest, distance(gateways.get(candidate), gateways.get(current)));
                if (nearest > selectedDistance) { selectedDistance = nearest; selected = candidate; }
            }
            int insertion = 0;
            double increase = distance(gateways.get(selected), gateways.get(result.getFirst()));
            for (int i = 1; i < result.size(); i++) {
                double d = distance(gateways.get(result.get(i - 1)), gateways.get(selected)) + distance(gateways.get(selected), gateways.get(result.get(i))) - distance(gateways.get(result.get(i - 1)), gateways.get(result.get(i)));
                if (d < increase) { increase = d; insertion = i; }
            }
            if (distance(gateways.get(result.getLast()), gateways.get(selected)) < increase) insertion = result.size();
            result.add(insertion, selected);
        }
        return result;
    }

    private static double square(double x, double z, double tx, double tz) { double dx = x - tx, dz = z - tz; return dx * dx + dz * dz; }
    private static double distance(Gateway a, Gateway b) { return Math.sqrt(square(a.x(), a.z(), b.x(), b.z())); }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
