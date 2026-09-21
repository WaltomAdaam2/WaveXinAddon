package me.waltom.wavexin.core;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Shared DPAPI envelope; each feature has a distinct authenticated payload marker. */
final class LocalFeatureLicense {
    private static final byte[] HEADER = "WXAUTH-DPAPI-V1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int RANDOM_LENGTH = 32;
    private static final int MAX_FILE_LENGTH = 16_384;

    private LocalFeatureLicense() {}

    static boolean issue(Path path, byte[] marker) {
        if (!Platform.isWindows()) return false;
        byte[] random = new byte[RANDOM_LENGTH];
        new SecureRandom().nextBytes(random);
        try {
            WaveXinSettingsStore.writeAtomically(path, create(random, marker));
            return valid(path, marker);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean valid(Path path, byte[] marker) {
        if (!Platform.isWindows()) return false;
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_LENGTH) return false;
            return valid(Files.readAllBytes(path), marker);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    static byte[] withHeader(byte[] protectedPayload) {
        byte[] output = Arrays.copyOf(HEADER, HEADER.length + protectedPayload.length);
        System.arraycopy(protectedPayload, 0, output, HEADER.length, protectedPayload.length);
        return output;
    }

    static byte[] create(byte[] random, byte[] marker) {
        if (!Platform.isWindows() || random == null || random.length != RANDOM_LENGTH) {
            throw new IllegalArgumentException("Invalid license data.");
        }
        byte[] payload = Arrays.copyOf(marker, marker.length + RANDOM_LENGTH);
        System.arraycopy(random, 0, payload, marker.length, random.length);
        return withHeader(Crypt32Util.cryptProtectData(payload));
    }

    static boolean valid(byte[] contents, byte[] marker) {
        if (!Platform.isWindows() || contents == null || contents.length <= HEADER.length || contents.length > MAX_FILE_LENGTH) return false;
        try {
            for (int index = 0; index < HEADER.length; index++) if (contents[index] != HEADER[index]) return false;
            byte[] payload = Crypt32Util.cryptUnprotectData(Arrays.copyOfRange(contents, HEADER.length, contents.length));
            return payload.length == marker.length + RANDOM_LENGTH
                && MessageDigest.isEqual(marker, Arrays.copyOf(payload, marker.length));
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
