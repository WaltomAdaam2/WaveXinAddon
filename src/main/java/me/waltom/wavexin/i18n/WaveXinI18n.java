package me.waltom.wavexin.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.waltom.wavexin.WaveXinAddon;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WaveXinI18n {
    public static final String PACKAGE_PREFIX = "me.waltom.wavexin.";

    private static final boolean DEVELOPMENT = Boolean.getBoolean("fabric.development");
    private static final Pattern FORMAT_PATTERN = Pattern.compile(
        "%(?:([1-9]\\d*)\\$)?([-#+ 0,(<]*)(\\d*)(?:\\.(\\d+))?([a-zA-Z%])"
    );

    private static final Set<String> LOGGED_UI_PATHS = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_FORMAT_FAILURES = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> KEY_SEGMENT_CACHE = new ConcurrentHashMap<>();
    private static final Map<Setting<?>, SettingGroup> SETTING_GROUP_CACHE =
        Collections.synchronizedMap(new IdentityHashMap<>());

    private WaveXinI18n() {
    }

    public static MutableText text(String key, String fallback, Object... args) {
        try {
            if (I18n.hasTranslation(key)) return Text.translatable(key, args);
        } catch (RuntimeException e) {
            logFormatFailure("text-translation:" + key, key, e);
        }
        return Text.literal(formatFallback(fallback, args));
    }

    public static String tr(String key, String fallback, Object... args) {
        try {
            if (I18n.hasTranslation(key)) return I18n.translate(key, args);
        } catch (RuntimeException e) {
            logFormatFailure("translation:" + key, key, e);
        }

        return formatFallback(fallback, args);
    }

    public static String formatFallback(String fallback, Object... args) {
        if (fallback == null) return "";
        if (args == null || args.length == 0) return fallback;

        try {
            return String.format(Locale.ROOT, fallback, args);
        } catch (RuntimeException e) {
            logFormatFailure("fallback:" + fallback, fallback, e);
            return fallback;
        }
    }

    public static boolean isWaveXin(Module module) {
        return module != null && module.getClass().getName().startsWith(PACKAGE_PREFIX);
    }

    public static String moduleKey(Module module, String suffix) {
        return "module.wavexin." + keySegment(module.name) + "." + suffix;
    }

    public static String moduleTitle(Module module) {
        if (!isWaveXin(module)) return module == null ? "" : module.title;
        return tr(moduleKey(module, "title"), module.title);
    }

    public static String moduleDescription(Module module) {
        if (!isWaveXin(module)) return module == null ? "" : module.description;
        return tr(moduleKey(module, "description"), module.description);
    }

    public static String decorateModuleTitle(Module module, String title) {
        if (!isWaveXin(module) || title == null) return title;

        String translated = moduleTitle(module);
        if (title.equals(module.title)) return translated;

        String prefix = module.title + " (";
        if (title.startsWith(prefix) && title.endsWith(")")) {
            return translated + title.substring(module.title.length());
        }

        return title;
    }

    public static String groupName(Module module, SettingGroup group) {
        if (!isWaveXin(module) || group == null) return group == null ? "" : group.name;
        return tr(groupKey(module, group), group.name);
    }

    public static String groupKey(Module module, SettingGroup group) {
        return "group.wavexin." + keySegment(module.name) + "." + keySegment(group.name);
    }

    public static String settingTitle(Setting<?> setting) {
        if (setting == null) return "";

        Module module = setting.module;
        if (!isWaveXin(module)) return setting.title;
        return tr(settingKey(module, setting, "title"), setting.title);
    }

    public static String settingDescription(Setting<?> setting) {
        if (setting == null) return "";

        Module module = setting.module;
        if (!isWaveXin(module)) return setting.description;
        return tr(settingKey(module, setting, "description"), setting.description);
    }

    public static String settingFilterText(Setting<?> setting) {
        if (setting == null) return "";
        if (!isWaveXin(setting.module)) return setting.title;
        return setting.title + " " + setting.name + " " + settingTitle(setting);
    }

    public static int moduleSearchScore(Module module, String text) {
        if (!isWaveXin(module)) return Utils.searchLevenshteinDefault(module.title, text, false);

        int score = Utils.searchLevenshteinDefault(module.title, text, false);
        score = Math.min(score, Utils.searchLevenshteinDefault(module.name, text, false));
        score = Math.min(score, Utils.searchLevenshteinDefault(moduleTitle(module), text, false));
        return score;
    }

    public static int settingSearchScore(Setting<?> setting, String text) {
        if (setting == null) return Integer.MAX_VALUE;

        int score = Utils.searchLevenshteinDefault(setting.title, text, false);
        if (isWaveXin(setting.module)) {
            score = Math.min(score, Utils.searchLevenshteinDefault(setting.name, text, false));
            score = Math.min(score, Utils.searchLevenshteinDefault(settingTitle(setting), text, false));
        }
        return score;
    }

    public static String settingKey(Module module, Setting<?> setting, String suffix) {
        SettingGroup group = findGroup(module, setting);
        if (group == null) {
            return "setting.wavexin." + keySegment(module.name) + "." + keySegment(setting.name) + "." + suffix;
        }

        return "setting.wavexin."
            + keySegment(module.name) + "."
            + keySegment(group.name) + "."
            + keySegment(setting.name) + "."
            + suffix;
    }

    public static SettingGroup findGroup(Module module, Setting<?> setting) {
        if (module == null || setting == null) return null;

        SettingGroup cached = SETTING_GROUP_CACHE.get(setting);
        if (cached != null) return cached;

        for (SettingGroup group : module.settings) {
            for (Setting<?> candidate : group) {
                if (candidate == setting) {
                    SETTING_GROUP_CACHE.put(setting, group);
                    return group;
                }
            }
        }

        return null;
    }

    public static String enumLabel(Module module, Enum<?> value) {
        if (!isWaveXin(module) || value == null) return value == null ? "" : value.toString();
        return enumLabel(value, value.toString());
    }

    public static String enumLabel(Enum<?> value, String fallback) {
        if (value == null) return fallback == null ? "" : fallback;
        return tr(enumKey(value), fallback == null ? value.toString() : fallback);
    }

    public static String enumLabelOr(Enum<?> value, String fallback) {
        if (value != null) return enumLabel(value, fallback);
        return translateKnownUnknownFallback(fallback);
    }

    private static String translateKnownUnknownFallback(String fallback) {
        if (fallback == null || fallback.isBlank()) return "";

        return switch (fallback) {
            case "Unknown state" -> tr("status.wavexin.common.unknown_state", fallback);
            case "Unknown route" -> tr("status.wavexin.common.unknown_route", fallback);
            case "Unknown direction" -> tr("status.wavexin.common.unknown_direction", fallback);
            default -> fallback;
        };
    }

    public static String enumKey(Enum<?> value) {
        return "enum.wavexin."
            + keySegment(value.getDeclaringClass().getSimpleName())
            + "."
            + keySegment(value.name());
    }

    public static List<String> moduleSearchCandidates(Module module) {
        List<String> candidates = new ArrayList<>();
        if (module == null) return candidates;

        candidates.add(module.title);
        if (isWaveXin(module)) {
            candidates.add(module.name);
            candidates.add(moduleTitle(module));
        }

        if (Config.get().moduleAliases.get() && module.aliases != null) {
            Collections.addAll(candidates, module.aliases);
        }

        return candidates;
    }

    public static List<String> settingSearchCandidates(Setting<?> setting) {
        List<String> candidates = new ArrayList<>();
        if (setting == null) return candidates;

        candidates.add(setting.title);
        if (isWaveXin(setting.module)) {
            candidates.add(setting.name);
            candidates.add(settingTitle(setting));
        }

        return candidates;
    }

    public static void markUiPath(String path) {
        if (!DEVELOPMENT || path == null || path.isBlank()) return;
        LOGGED_UI_PATHS.add(path);
    }

    public static void validateResources(Iterable<Module> modules) {
        if (!DEVELOPMENT) return;

        Map<String, String> en = readLanguage("en_us");
        Map<String, String> zh = readLanguage("zh_cn");
        int errors = 0;

        if (en.isEmpty()) errors++;
        if (zh.isEmpty()) errors++;
        if (errors > 0) {
            WaveXinAddon.LOG.warn("WaveXin i18n runtime validation FAIL: {} resource files unavailable.", errors);
            return;
        }

        Set<String> required = requiredKeys(modules);
        errors += reportMissing("en_us", en.keySet(), required);
        errors += reportMissing("zh_cn", zh.keySet(), required);
        errors += reportMismatchedResourceKeys(en.keySet(), zh.keySet());
        errors += reportPlaceholderMismatches(en, zh);

        if (errors != 0) {
            WaveXinAddon.LOG.warn(
                "WaveXin i18n runtime validation FAIL: {} errors across {} required keys.",
                errors,
                required.size()
            );
        }
    }

    private static Set<String> requiredKeys(Iterable<Module> modules) {
        Set<String> keys = new LinkedHashSet<>(readExpectedKeys());
        keys.add("meta.wavexin.language");

        for (Module module : modules) {
            if (!isWaveXin(module)) continue;

            keys.add(moduleKey(module, "title"));
            keys.add(moduleKey(module, "description"));

            for (SettingGroup group : module.settings) {
                keys.add(groupKey(module, group));
                for (Setting<?> setting : group) {
                    keys.add(settingKey(module, setting, "title"));
                    keys.add(settingKey(module, setting, "description"));

                    Object value = setting.get();
                    if (value instanceof Enum<?> enumValue) {
                        for (Object constant : enumValue.getDeclaringClass().getEnumConstants()) {
                            if (constant instanceof Enum<?> constantEnum) keys.add(enumKey(constantEnum));
                        }
                    }
                }
            }
        }

        return keys;
    }

    private static Set<String> readExpectedKeys() {
        String path = "assets/wavexin/lang/expected_keys.txt";
        try (InputStream stream = WaveXinI18n.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                WaveXinAddon.LOG.warn("WaveXin i18n validation could not find {}.", path);
                return Set.of();
            }

            Set<String> keys = new LinkedHashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) keys.add(trimmed);
                }
            }
            return keys;
        } catch (Exception e) {
            WaveXinAddon.LOG.warn("WaveXin i18n validation could not read {}.", path, e);
            return Set.of();
        }
    }

    private static Map<String, String> readLanguage(String language) {
        String path = "assets/wavexin/lang/" + language + ".json";
        try (InputStream stream = WaveXinI18n.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                WaveXinAddon.LOG.warn("WaveXin i18n validation could not find {}.", path);
                return Map.of();
            }

            JsonObject object = JsonParser.parseReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
            ).getAsJsonObject();

            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                values.put(entry.getKey(), entry.getValue().getAsString());
            }
            return values;
        } catch (Exception e) {
            WaveXinAddon.LOG.warn("WaveXin i18n validation could not read {}.", path, e);
            return Map.of();
        }
    }

    private static int reportMissing(String language, Set<String> actual, Set<String> required) {
        int errors = 0;
        for (String key : required) {
            if (!actual.contains(key)) {
                WaveXinAddon.LOG.warn("WaveXin i18n missing {} key: {}", language, key);
                errors++;
            }
        }
        return errors;
    }

    private static int reportMismatchedResourceKeys(Set<String> en, Set<String> zh) {
        int errors = 0;
        for (String key : en) {
            if (!zh.contains(key)) {
                WaveXinAddon.LOG.warn("WaveXin i18n zh_cn is missing en_us key: {}", key);
                errors++;
            }
        }
        for (String key : zh) {
            if (!en.contains(key)) {
                WaveXinAddon.LOG.warn("WaveXin i18n en_us is missing zh_cn key: {}", key);
                errors++;
            }
        }
        return errors;
    }

    private static int reportPlaceholderMismatches(Map<String, String> en, Map<String, String> zh) {
        int errors = 0;
        for (Map.Entry<String, String> entry : en.entrySet()) {
            String key = entry.getKey();
            String zhValue = zh.get(key);
            if (zhValue == null) continue;

            String enPlaceholders = placeholders(entry.getValue());
            String zhPlaceholders = placeholders(zhValue);
            if (!enPlaceholders.equals(zhPlaceholders)) {
                WaveXinAddon.LOG.warn(
                    "WaveXin i18n placeholder mismatch for {}: en_us={}, zh_cn={}",
                    key,
                    enPlaceholders,
                    zhPlaceholders
                );
                errors++;
            }
        }
        return errors;
    }

    private static String placeholders(String value) {
        if (value == null) return "";

        Matcher matcher = FORMAT_PATTERN.matcher(value);
        List<String> placeholders = new ArrayList<>();
        int implicitIndex = 1;
        int previousIndex = -1;

        while (matcher.find()) {
            String conversion = matcher.group(5);
            if ("%".equals(conversion)) continue;

            String explicitIndex = matcher.group(1);
            String flags = matcher.group(2);
            int argumentIndex;

            if (explicitIndex != null) {
                argumentIndex = Integer.parseInt(explicitIndex);
            } else if (flags != null && flags.indexOf('<') >= 0 && previousIndex > 0) {
                argumentIndex = previousIndex;
            } else {
                argumentIndex = implicitIndex++;
            }

            previousIndex = argumentIndex;
            placeholders.add(argumentIndex + ":" + conversion);
        }

        Collections.sort(placeholders);
        return String.join(",", placeholders);
    }

    private static void logFormatFailure(String id, String keyOrFallback, RuntimeException e) {
        if (!DEVELOPMENT) return;
        if (LOGGED_FORMAT_FAILURES.add(id)) {
            WaveXinAddon.LOG.warn(
                "WaveXin i18n format fallback used for {}: {}",
                keyOrFallback,
                e.toString()
            );
        }
    }

    public static String keySegment(String value) {
        if (value == null || value.isBlank()) return "unnamed";

        return KEY_SEGMENT_CACHE.computeIfAbsent(value, raw -> {
            String segment = raw
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
            return segment.isEmpty() ? "unnamed" : segment;
        });
    }
}
