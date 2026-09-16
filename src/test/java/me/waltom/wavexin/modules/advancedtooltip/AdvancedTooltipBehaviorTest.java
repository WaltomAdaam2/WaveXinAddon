package me.waltom.wavexin.modules.advancedtooltip;

public final class AdvancedTooltipBehaviorTest {
    private AdvancedTooltipBehaviorTest() {}
    public static void main(String[] args) {
        assertEquals(1, AdvancedTooltip.rowsFor(0, 9), "empty preview row");
        assertEquals(1, AdvancedTooltip.rowsFor(9, 9), "one full row");
        assertEquals(2, AdvancedTooltip.rowsFor(10, 9), "second row");
        assertEquals(9, AdvancedTooltip.rowsFor(200, 9), "row cap");
    }
    private static void assertEquals(int expected, int actual, String message) { if (expected != actual) throw new AssertionError(message); }
}
