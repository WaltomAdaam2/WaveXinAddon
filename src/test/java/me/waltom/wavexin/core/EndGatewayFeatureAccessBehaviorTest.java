package me.waltom.wavexin.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class EndGatewayFeatureAccessBehaviorTest {
    private EndGatewayFeatureAccessBehaviorTest() {
    }

    public static void main(String[] args) throws Exception {
        byte[] expected = MessageDigest.getInstance("SHA-256").digest("test-code".getBytes(StandardCharsets.UTF_8));
        assertTrue(EndGatewayFeatureAccess.matches(" test-code ", expected), "trimmed matching code");
        assertFalse(EndGatewayFeatureAccess.matches("wrong-code", expected), "invalid code");
        assertFalse(EndGatewayFeatureAccess.matches(null, expected), "null code");
        assertFalse(WaveXinSettingsStore.endGatewayFeatureFromJson("{\"version\":1,\"modules\":{}}"), "legacy settings default disabled");
        assertTrue(WaveXinSettingsStore.endGatewayFeatureFromJson("{\"version\":2,\"modules\":{},\"features\":{\"endGatewayFinder\":true}}"), "saved feature flag");
    }

    private static void assertTrue(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    private static void assertFalse(boolean value, String description) {
        assertTrue(!value, description);
    }
}
