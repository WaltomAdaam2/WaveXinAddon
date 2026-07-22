package me.waltom.wavexin.i18n;

public final class WaveXinI18nBehaviorTest {
    private WaveXinI18nBehaviorTest() {
    }

    public static void main(String[] args) {
        assertEquals("Hello A", WaveXinI18n.formatFallback("Hello %s", "A"), "%s formatting");
        assertEquals("Count 7", WaveXinI18n.formatFallback("Count %d", 7), "%d formatting");
        assertEquals("A", WaveXinI18n.formatFallback("%1$s", "A"), "explicit first argument");
        assertEquals("B 2", WaveXinI18n.formatFallback("%1$s %2$d", "B", 2), "explicit mixed arguments");
        assertEquals("A/A", WaveXinI18n.formatFallback("%1$s/%1$s", "A"), "repeated positional argument");
        assertEquals("B -> A", WaveXinI18n.formatFallback("%2$s -> %1$s", "A", "B"), "reordered arguments");
        assertEquals("Progress % done", WaveXinI18n.formatFallback("Progress %% %s", "done"), "escaped percent");
        assertEquals("    1.50", WaveXinI18n.formatFallback("%8.2f", 1.5d), "width and precision");
        assertEquals("", WaveXinI18n.formatFallback(null, "unused"), "null fallback");
        assertEquals("Plain", WaveXinI18n.formatFallback("Plain"), "zero arguments");
        assertEquals("%s %d", WaveXinI18n.formatFallback("%s %d", "only-one"), "too few arguments return fallback");
        assertEquals("%d", WaveXinI18n.formatFallback("%d", "wrong"), "wrong argument type returns fallback");
        assertEquals("%", WaveXinI18n.formatFallback("%", "bad"), "malformed format returns fallback");

        assertEquals("a_b", WaveXinI18n.keySegment("A-B"), "dash normalization");
        assertEquals("a_b", WaveXinI18n.keySegment("A B"), "space normalization");
        assertEquals("a_b", WaveXinI18n.keySegment("a_b"), "underscore normalization");
        assertEquals("camel_case", WaveXinI18n.keySegment("camelCase"), "camel case normalization");
        assertEquals("camel_case", WaveXinI18n.keySegment("camelCase"), "cached camel case normalization");
        assertEquals("unnamed", WaveXinI18n.keySegment("!!!"), "punctuation fallback");
        assertEquals("unnamed", WaveXinI18n.keySegment(null), "null key segment fallback");

        assertEquals("Unknown route", WaveXinI18n.enumLabelOr(null, "Unknown route"), "null route fallback");
        assertEquals("Unknown state", WaveXinI18n.enumLabelOr(null, "Unknown state"), "null state fallback");
        assertEquals("Unknown direction", WaveXinI18n.enumLabelOr(null, "Unknown direction"), "null direction fallback");
        assertEquals("Custom fallback", WaveXinI18n.enumLabelOr(null, "Custom fallback"), "custom null enum fallback");
        assertEquals("", WaveXinI18n.enumLabelOr(null, null), "null enum null fallback");
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected [" + expected + "] but got [" + actual + "]");
        }
    }
}
