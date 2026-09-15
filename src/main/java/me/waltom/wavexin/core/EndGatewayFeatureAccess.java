package me.waltom.wavexin.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class EndGatewayFeatureAccess {
    private static final byte[] CODE_HASH = hex("1B8268C9CB0F4E5A2953F2BD8BD99726BEFAC9669C399C1207533BB10CBE4276");

    private EndGatewayFeatureAccess() {
    }

    public static boolean matches(String value) {
        return matches(value, CODE_HASH);
    }

    static boolean matches(String value, byte[] expectedHash) {
        if (value == null) return false;
        try {
            return MessageDigest.isEqual(expectedHash, MessageDigest.getInstance("SHA-256").digest(value.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        return bytes;
    }
}
