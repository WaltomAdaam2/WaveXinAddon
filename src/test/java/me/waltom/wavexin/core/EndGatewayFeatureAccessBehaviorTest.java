package me.waltom.wavexin.core;

import com.sun.jna.Platform;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EndGatewayFeatureAccessBehaviorTest {
    private EndGatewayFeatureAccessBehaviorTest() {
    }

    public static void main(String[] args) throws Exception {
        byte[] expected = MessageDigest.getInstance("SHA-256").digest("test-code".getBytes(StandardCharsets.UTF_8));
        assertTrue(EndGatewayFeatureAccess.matches(" test-code ", expected), "trimmed matching code");
        assertFalse(EndGatewayFeatureAccess.matches("wrong-code", expected), "invalid code");
        assertFalse(EndGatewayFeatureAccess.matches(null, expected), "null code");
        assertFalse(KillAuraFeatureAccess.matches(null), "null combat code");
        assertFalse(KillAuraFeatureAccess.matches("wrong-code"), "invalid combat code");
        assertFalse(EndGatewayFeatureAccess.validLicense(new byte[0]), "empty scan license");
        if (!Platform.isWindows()) return;
        byte[] license = EndGatewayFeatureAccess.createLicense(new byte[32]);
        assertTrue(EndGatewayFeatureAccess.validLicense(license), "encrypted license validates");
        Path directory = Files.createTempDirectory("wavexin-license-test-");
        Path scanFile = directory.resolve("scan.dat");
        Path auraFile = directory.resolve("aura.dat");
        try {
            Files.write(scanFile, license);
            assertTrue(EndGatewayFeatureAccess.validLicense(Files.readAllBytes(scanFile)), "scan persisted restart read");
            assertTrue(LocalFeatureLicense.valid(scanFile, "wavexin-license-v1\0".getBytes(StandardCharsets.UTF_8)), "original scan marker retained");
            assertFalse(LocalFeatureLicense.valid(scanFile, KillAuraFeatureAccess.LICENSE_MARKER), "scan cannot unlock aura");
            assertTrue(LocalFeatureLicense.issue(auraFile, KillAuraFeatureAccess.LICENSE_MARKER), "issue aura license");
            assertTrue(LocalFeatureLicense.valid(auraFile, KillAuraFeatureAccess.LICENSE_MARKER), "aura persisted restart read");
            assertFalse(EndGatewayFeatureAccess.validLicense(Files.readAllBytes(auraFile)), "aura cannot unlock scan");
            assertTrue(LocalFeatureLicense.issue(auraFile, KillAuraFeatureAccess.LICENSE_MARKER), "repeat aura redemption");
            assertTrue(EndGatewayFeatureAccess.validLicense(Files.readAllBytes(scanFile)), "aura issuance leaves scan unchanged");
            byte[] auraLicense = Files.readAllBytes(auraFile);
            auraLicense[auraLicense.length - 1] ^= 1;
            assertFalse(LocalFeatureLicense.valid(auraLicense, KillAuraFeatureAccess.LICENSE_MARKER), "modified aura license rejected");
        } finally {
            Files.deleteIfExists(auraFile);
            Files.deleteIfExists(scanFile);
            Files.deleteIfExists(directory);
        }
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
