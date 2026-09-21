package me.waltom.wavexin.core;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class KillAuraFeatureAccess {
    private static final byte[] CODE_HASH = HexFormat.of().parseHex("C676D3C842BADCA398551605B5BBA4D09DFA1F24EE1423087A4F4DFFCE3600B8");
    static final byte[] LICENSE_MARKER = "wavexin-killaura-license-v1\0".getBytes(StandardCharsets.UTF_8);

    private KillAuraFeatureAccess() {}

    public static boolean matches(String value) {
        return EndGatewayFeatureAccess.matches(value, CODE_HASH);
    }

    public static boolean issueLicense() {
        return LocalFeatureLicense.issue(WaveXinDataPaths.KILL_AURA_LICENSE_PATH, LICENSE_MARKER);
    }

    public static boolean hasValidLicense() {
        return LocalFeatureLicense.valid(WaveXinDataPaths.KILL_AURA_LICENSE_PATH, LICENSE_MARKER);
    }
}
