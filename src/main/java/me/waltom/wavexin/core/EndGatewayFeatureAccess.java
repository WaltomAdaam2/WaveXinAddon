package me.waltom.wavexin.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;

public final class EndGatewayFeatureAccess {
    private static final byte[] CODE_HASH = hex("1B8268C9CB0F4E5A2953F2BD8BD99726BEFAC9669C399C1207533BB10CBE4276");
    private static final byte[] LICENSE_HEADER = "WXAUTH-DPAPI-V1\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LICENSE_MARKER = "wavexin-license-v1\0".getBytes(StandardCharsets.UTF_8);
    private static final int LICENSE_RANDOM_LENGTH = 32;

    private EndGatewayFeatureAccess() {
    }

    public static boolean matches(String value) {
        return matches(value, CODE_HASH);
    }

    /** Writes a local encrypted license without retaining the redemption code. */
    public static boolean issueLicense() {
        if (!Platform.isWindows()) return false;
        byte[] random = new byte[LICENSE_RANDOM_LENGTH];
        new SecureRandom().nextBytes(random);
        try {
            WaveXinSettingsStore.writeAtomically(WaveXinDataPaths.LICENSE_PATH, createLicense(random));
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /** Validates the encrypted local license during addon startup. */
    public static boolean hasValidLicense() {
        if (!Platform.isWindows()) return false;
        try {
            if (!java.nio.file.Files.exists(WaveXinDataPaths.LICENSE_PATH)) return false;
            return validLicense(java.nio.file.Files.readAllBytes(WaveXinDataPaths.LICENSE_PATH));
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
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
        byte[] output = Arrays.copyOf(LICENSE_HEADER, LICENSE_HEADER.length + protectedPayload.length);
        System.arraycopy(protectedPayload, 0, output, LICENSE_HEADER.length, protectedPayload.length);
        return output;
    }

    static byte[] createLicense(byte[] random) {
        if (!Platform.isWindows() || random == null || random.length != LICENSE_RANDOM_LENGTH) throw new IllegalArgumentException("Invalid license data.");
        byte[] payload = Arrays.copyOf(LICENSE_MARKER, LICENSE_MARKER.length + LICENSE_RANDOM_LENGTH);
        System.arraycopy(random, 0, payload, LICENSE_MARKER.length, random.length);
        return withHeader(Crypt32Util.cryptProtectData(payload));
    }

    static boolean validLicense(byte[] fileContents) {
        try {
            if (fileContents == null || fileContents.length <= LICENSE_HEADER.length) return false;
            for (int index = 0; index < LICENSE_HEADER.length; index++) if (fileContents[index] != LICENSE_HEADER[index]) return false;
            byte[] payload = Crypt32Util.cryptUnprotectData(Arrays.copyOfRange(fileContents, LICENSE_HEADER.length, fileContents.length));
            return payload.length == LICENSE_MARKER.length + LICENSE_RANDOM_LENGTH
                && MessageDigest.isEqual(LICENSE_MARKER, Arrays.copyOf(payload, LICENSE_MARKER.length));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        return bytes;
    }
}
