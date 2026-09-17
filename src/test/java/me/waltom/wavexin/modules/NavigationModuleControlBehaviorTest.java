package me.waltom.wavexin.modules;

import java.util.List;
import java.util.Set;

public final class NavigationModuleControlBehaviorTest {
    private NavigationModuleControlBehaviorTest() {
    }

    public static void main(String[] args) {
        List<String> modules = List.of("base", "path", "gateway");
        assertEquals(null, NavigationModuleControl.firstActiveOther("base", modules, value -> false), "no active conflict");
        assertEquals("path", NavigationModuleControl.firstActiveOther("base", modules, Set.of("base", "path")::contains), "self is ignored");
        assertEquals("base", NavigationModuleControl.firstActiveOther("gateway", modules, Set.of("base", "path")::contains), "first active module is reported");
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(description + ": expected=" + expected + " actual=" + actual);
    }
}
