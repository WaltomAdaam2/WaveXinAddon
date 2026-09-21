package me.waltom.wavexin.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class EndGatewayFeatureAccess {
    private static final byte[] CODE_HASH = hex("1B8268C9CB0F4E5A2953F2BD8BD99726BEFAC9669C399C1207533BB10CBE4276");
    private static final byte[] LICENSE_MARKER = "wavexin-license-v1\0".getBytes(StandardCharsets.UTF_8);

    private EndGatewayFeatureAccess() {
    }

    public static boolean matches(String value) {
        return matches(value, CODE_HASH);
    }

    /** Writes a local encrypted license without retaining the redemption code. */
    public static boolean issueLicense() {
        return LocalFeatureLicense.issue(WaveXinDataPaths.LICENSE_PATH, LICENSE_MARKER);
    }

    /** Validates the encrypted local license during addon startup. */
    public static boolean hasValidLicense() {
        return LocalFeatureLicense.valid(WaveXinDataPaths.LICENSE_PATH, LICENSE_MARKER);
    }

    static boolean matches(String value, byte[] expectedHash) {
        if (value == null) return false;
        try {
            return MessageDigest.isEqual(expectedHash, MessageDigest.getInstance("SHA-256").digest(value.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static byte[] withHeader(byte[] protectedPayload) {
        return LocalFeatureLicense.withHeader(protectedPayload);
    }

    static byte[] createLicense(byte[] random) {
        return LocalFeatureLicense.create(random, LICENSE_MARKER);
    }

    static boolean validLicense(byte[] fileContents) {
        return LocalFeatureLicense.valid(fileContents, LICENSE_MARKER);
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        return bytes;
    }
}
