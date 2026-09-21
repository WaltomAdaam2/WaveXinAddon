package me.waltom.wavexin.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Checks the public GitHub release feed without blocking Minecraft's client thread. */
public final class UpdateChecker {
    private static final String REPOSITORY = "WaltomAdaam2/WaveXinAddon";
    private static final URI RELEASES_API = URI.create("https://api.github.com/repos/" + REPOSITORY + "/releases/latest");
    private static final List<URI> ENDPOINTS = List.of(
        RELEASES_API,
        URI.create("https://ghfast.top/" + RELEASES_API),
        URI.create("https://gh-proxy.com/" + RELEASES_API),
        URI.create("https://gh.3w.pm/" + RELEASES_API)
    );
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "wavexin-update-check");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile Future<?> inFlight;

    private UpdateChecker() {
    }

    public static void checkOnStartup() {
        if (!WaveXinSettingsStore.isUpdateCheckEnabled() || !STARTED.compareAndSet(false, true)) return;
        inFlight = EXECUTOR.submit(UpdateChecker::check);
    }

    public static void cancel() {
        Future<?> request = inFlight;
        if (request != null) request.cancel(true);
    }

    private static void check() {
        for (URI endpoint : ENDPOINTS) {
            if (Thread.currentThread().isInterrupted() || !WaveXinSettingsStore.isUpdateCheckEnabled()) return;
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) continue;
                Optional<Release> release = parseRelease(response.body());
                if (release.isPresent() && isNewer(release.get().tag(), localVersion())) {
                    notifyUpdate(release.get());
                    return;
                }
                if (release.isPresent()) return;
            } catch (Exception ignored) {
                // A public endpoint may be unavailable; try the next fallback.
            }
        }
    }

    private static void notifyUpdate(Release release) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (!WaveXinSettingsStore.isUpdateCheckEnabled()) return;
            String title = WaveXinI18n.tr("message.wavexin.update_check.available.title", "WaveXinAddon update available");
            String description = WaveXinI18n.tr("message.wavexin.update_check.available.description", "Version %s is available on GitHub Releases.", release.tag());
            SystemToast.show(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal(title), Text.literal(description));
            ChatUtils.sendMsg(Text.literal(title + ": " + description + " " + release.url()));
        });
    }

    static List<URI> endpoints() {
        return ENDPOINTS;
    }

    static Optional<Release> parseRelease(String body) {
        try {
            JsonObject release = JsonParser.parseString(body).getAsJsonObject();
            if (!release.has("tag_name") || !release.has("html_url")) return Optional.empty();
            String tag = release.get("tag_name").getAsString();
            URI url = URI.create(release.get("html_url").getAsString());
            if (!"https".equals(url.getScheme()) || !"github.com".equals(url.getHost())
                || !url.getPath().startsWith("/" + REPOSITORY + "/releases/tag/")) return Optional.empty();
            return Version.parse(tag).isPresent() ? Optional.of(new Release(tag, url)) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    static boolean isNewer(String remote, String local) {
        Optional<Version> remoteVersion = Version.parse(remote);
        Optional<Version> localVersion = Version.parse(local);
        return remoteVersion.isPresent() && localVersion.isPresent() && remoteVersion.get().compareTo(localVersion.get()) > 0;
    }

    private static String localVersion() {
        String version = FabricLoader.getInstance().getModContainer("wave-xin-addon")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("0.0.0");
        int minecraftSuffix = version.indexOf("+mc");
        return minecraftSuffix >= 0 ? version.substring(0, minecraftSuffix) : version;
    }

    record Release(String tag, URI url) {
    }

    private record Version(int major, int minor, int patch, boolean prerelease) implements Comparable<Version> {
        static Optional<Version> parse(String value) {
            try {
                if (value == null || !value.matches("v?\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?")) return Optional.empty();
                String normalized = value.startsWith("v") ? value.substring(1) : value;
                String[] parts = normalized.split("-", 2);
                String[] numbers = parts[0].split("\\.");
                return Optional.of(new Version(Integer.parseInt(numbers[0]), Integer.parseInt(numbers[1]), Integer.parseInt(numbers[2]), parts.length == 2));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }

        @Override
        public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            if (result == 0) result = Integer.compare(minor, other.minor);
            if (result == 0) result = Integer.compare(patch, other.patch);
            if (result == 0) result = Boolean.compare(other.prerelease, prerelease);
            return result;
        }
    }
}
