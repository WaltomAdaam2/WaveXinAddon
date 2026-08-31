package me.waltom.wavexin.modules.litematicaprinter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PrinterRestockPlanner {
    private PrinterRestockPlanner() {
    }

    static <T> Plan<T> plan(List<Demand<T>> demands, int emptySlots, int obsoleteSlots) {
        List<Demand<T>> needed = demands.stream()
            .filter(demand -> demand.required() > demand.current())
            .toList();
        if (needed.isEmpty()) return new Plan<>(Map.of(), false);

        Map<T, Integer> targetSlots = new LinkedHashMap<>();
        for (Demand<T> demand : needed) {
            int occupied = Math.min(demand.currentStacks(), demand.requiredStacks());
            if (occupied > 0) targetSlots.put(demand.item(), occupied);
        }

        int available = Math.max(0, emptySlots);
        Comparator<Demand<T>> coverageOrder = Comparator
            .comparingInt(Demand<T>::servedStacks)
            .thenComparingDouble(Demand<T>::nearestDistanceSquared)
            .thenComparing(Comparator.comparingInt(Demand<T>::requiredStacks).reversed())
            .thenComparingInt(Demand<T>::order);
        List<Demand<T>> uncovered = needed.stream()
            .filter(demand -> demand.currentStacks() == 0)
            .sorted(coverageOrder)
            .toList();
        for (Demand<T> demand : uncovered) {
            if (available == 0) break;
            targetSlots.put(demand.item(), 1);
            available--;
        }

        List<Demand<T>> volumeOrder = new ArrayList<>(needed);
        volumeOrder.sort(Comparator
            .comparingInt(Demand<T>::requiredStacks).reversed()
            .thenComparingDouble(Demand<T>::nearestDistanceSquared)
            .thenComparingInt(Demand<T>::order));
        for (Demand<T> demand : volumeOrder) {
            int allocated = targetSlots.getOrDefault(demand.item(), 0);
            int add = Math.min(available, Math.max(0, demand.requiredStacks() - allocated));
            if (add <= 0) continue;
            targetSlots.put(demand.item(), allocated + add);
            available -= add;
            if (available == 0) break;
        }

        List<Demand<T>> transferOrder = new ArrayList<>(needed);
        transferOrder.sort(Comparator
            .<Demand<T>>comparingInt(demand -> targetSlots.getOrDefault(demand.item(), 0)).reversed()
            .thenComparing(Comparator.comparingInt(Demand<T>::requiredStacks).reversed())
            .thenComparingDouble(Demand<T>::nearestDistanceSquared)
            .thenComparingInt(Demand<T>::order));
        Map<T, Integer> targets = new LinkedHashMap<>();
        for (Demand<T> demand : transferOrder) {
            int slots = targetSlots.getOrDefault(demand.item(), 0);
            if (slots == 0) continue;
            targets.put(demand.item(), slots * demand.maxStack());
        }

        boolean unrepresented = uncovered.stream().anyMatch(demand -> !targetSlots.containsKey(demand.item()));
        return new Plan<>(targets, unrepresented && emptySlots == 0 && obsoleteSlots > 0);
    }

    record Demand<T>(
        T item,
        int required,
        int current,
        int currentStacks,
        int maxStack,
        double nearestDistanceSquared,
        int order,
        int servedStacks
    ) {
        Demand {
            required = Math.max(0, required);
            current = Math.max(0, current);
            currentStacks = Math.max(0, currentStacks);
            maxStack = Math.max(1, maxStack);
            order = Math.max(0, order);
            servedStacks = Math.max(0, servedStacks);
        }

        int requiredStacks() {
            return (required + maxStack - 1) / maxStack;
        }
    }

    record Plan<T>(Map<T, Integer> targetCounts, boolean requiresCleanup) {
        Plan {
            targetCounts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(targetCounts));
        }
    }
}
