package me.waltom.wavexin.modules.craftinggrid;

import java.util.LinkedHashMap;
import java.util.Map;

/** The recovered kit format uses 500..503 or 101..104 for the four grid slots. */
final class CraftingKit {
    private CraftingKit() {}

    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,64}");
    }

    static Map<Integer, String> parse(String text) {
        Map<Integer, String> layout = new LinkedHashMap<>();
        for (String line : text.lines().toList()) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            try {
                int encoded = Integer.parseInt(line.substring(0, colon).trim());
                int slot = gridSlot(encoded);
                String item = line.substring(colon + 1).trim();
                if (slot != -1 && !item.isEmpty()) layout.put(slot, item);
            } catch (NumberFormatException ignored) {
                // Invalid records cannot target an inventory or result slot.
            }
        }
        return layout;
    }

    static int gridSlot(int encoded) {
        if (encoded >= 500 && encoded <= 503) return encoded - 499;
        if (encoded >= 101 && encoded <= 104) return encoded - 100;
        return -1;
    }

    static boolean canRefill(boolean ownHandler, boolean cursorEmpty, boolean nearbyNonFriend, long elapsedMs, int delayMs) {
        return ownHandler && cursorEmpty && !nearbyNonFriend && elapsedMs >= delayMs;
    }

    static boolean donorSufficient(int presentCount, int maxCount, int donorCount) {
        return presentCount < maxCount && donorCount >= maxCount - presentCount;
    }
}
