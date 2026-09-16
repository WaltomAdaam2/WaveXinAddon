package me.waltom.wavexin.core;

import com.sun.jna.Platform;
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
        if (!Platform.isWindows()) return;
        byte[] license = EndGatewayFeatureAccess.createLicense(new byte[32]);
        assertTrue(EndGatewayFeatureAccess.validLicense(license), "encrypted license validates");
        license[license.length - 1] ^= 1;
        assertFalse(EndGatewayFeatureAccess.validLicense(license), "modified license is rejected");
    }

    private static void assertTrue(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    private static void assertFalse(boolean value, String description) {
        assertTrue(!value, description);
    }
}
