package me.waltom.wavexin.core;

import java.net.URI;
import java.util.List;

/** Verifies update endpoint fallback order and untrusted release-response handling without network access. */
public final class UpdateCheckerBehaviorTest {
    public static void main(String[] args) {
        List<URI> endpoints = UpdateChecker.endpoints();
        expect(endpoints.size() == 4, "direct GitHub plus three fallbacks");
        expect("api.github.com".equals(endpoints.get(0).getHost()), "GitHub must be first");
        expect("ghfast.top".equals(endpoints.get(1).getHost()), "first proxy order");
        expect("gh-proxy.com".equals(endpoints.get(2).getHost()), "second proxy order");
        expect("gh.3w.pm".equals(endpoints.get(3).getHost()), "third proxy order");

        expect(WaveXinSettingsStore.updateCheckFeatureFromJson("{\"version\":2,\"modules\":{}}"), "legacy settings default enabled");
        expect(!WaveXinSettingsStore.updateCheckFeatureFromJson("{\"features\":{\"updateCheck\":false}}"), "saved disabled setting");
        expect(WaveXinSettingsStore.updateCheckFeatureFromJson("{\"features\":{\"updateCheck\":true}}"), "saved enabled setting");

        String valid = "{\"tag_name\":\"v1.7.4\",\"html_url\":\"https://github.com/WaltomAdaam2/WaveXinAddon/releases/tag/v1.7.4\"}";
        expect(UpdateChecker.parseRelease(valid).isPresent(), "valid GitHub release");
        expect(UpdateChecker.parseRelease("{\"tag_name\":\"v1.7.4\",\"html_url\":\"https://example.com/release\"}").isEmpty(), "untrusted release host");
        expect(UpdateChecker.parseRelease("{\"tag_name\":\"latest\",\"html_url\":\"https://github.com/WaltomAdaam2/WaveXinAddon/releases/tag/latest\"}").isEmpty(), "invalid release version");

        expect(UpdateChecker.isNewer("v1.7.4", "1.7.3-SNAPSHOT"), "higher numeric version");
        expect(UpdateChecker.isNewer("v1.7.3", "1.7.3-SNAPSHOT"), "stable release follows matching snapshot");
        expect(!UpdateChecker.isNewer("v1.7.3", "1.7.3"), "same version");
        expect(!UpdateChecker.isNewer("v1.7.2", "1.7.3-SNAPSHOT"), "older version");
        expect(!UpdateChecker.isNewer("latest", "1.7.3"), "malformed version");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
