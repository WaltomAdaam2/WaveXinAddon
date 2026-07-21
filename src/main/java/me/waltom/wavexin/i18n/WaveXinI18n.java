package me.waltom.wavexin.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.waltom.wavexin.WaveXinAddon;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WaveXinI18n {
    public static final String PACKAGE_PREFIX = "me.waltom.wavexin.";
    private static final boolean DEVELOPMENT = Boolean.getBoolean("fabric.development");
    private static final Pattern FORMAT_PATTERN = Pattern.compile("%(?:\\d+\\$)?[sd]");
    private static final Set<String> LOGGED_UI_PATHS = new HashSet<>();

    private WaveXinI18n() {
    }

    public static MutableText text(String key, String fallback, Object... args) {
        return Text.literal(tr(key, fallback, args));
    }

    public static String tr(String key, String fallback, Object... args) {
        if (I18n.hasTranslation(key)) {
            return I18n.translate(key, args);
        }

        return formatFallback(fallback, args);
    }

    public static String formatFallback(String fallback, Object... args) {
        if (fallback == null) return "";
        if (args == null || args.length == 0) return fallback;

        try {
            String format = fallback.replaceAll("%(\\d+)\\$", "%");
            return String.format(Locale.ROOT, format, args);
        } catch (IllegalArgumentException ignored) {
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
        for (SettingGroup group : module.settings) {
            for (Setting<?> candidate : group) {
                if (candidate == setting) return group;
            }
        }
        return null;
    }

    public static String enumLabel(Module module, Enum<?> value) {
        if (!isWaveXin(module) || value == null) return value == null ? "" : value.toString();
        return tr(enumKey(value), value.toString());
    }

    public static String enumKey(Enum<?> value) {
        return "enum.wavexin." + keySegment(value.getDeclaringClass().getSimpleName()) + "." + keySegment(value.name());
    }

    public static List<String> moduleSearchCandidates(Module module) {
        List<String> candidates = new ArrayList<>();
        if (module == null) return candidates;
        candidates.add(module.name);
        candidates.add(module.title);
        if (isWaveXin(module)) candidates.add(moduleTitle(module));
        if (module.aliases != null) {
            for (String alias : module.aliases) candidates.add(alias);
        }
        return candidates;
    }

    public static List<String> settingSearchCandidates(Setting<?> setting) {
        List<String> candidates = new ArrayList<>();
        if (setting == null) return candidates;
        candidates.add(setting.name);
        candidates.add(setting.title);
        if (isWaveXin(setting.module)) candidates.add(settingTitle(setting));
        return candidates;
    }

    public static void markUiPath(String path) {
        if (!DEVELOPMENT || path == null || path.isBlank()) return;
        if (LOGGED_UI_PATHS.add(path)) {
            WaveXinAddon.LOG.info("WaveXin i18n UI path active: {}", path);
        }
    }

    public static void validateResources(Iterable<Module> modules) {
        if (!DEVELOPMENT) return;

        Map<String, String> en = readLanguage("en_us");
        Map<String, String> zh = readLanguage("zh_cn");
        if (en.isEmpty() || zh.isEmpty()) return;

        Set<String> required = requiredKeys(modules);
        reportMissing("en_us", en.keySet(), required);
        reportMissing("zh_cn", zh.keySet(), required);
        reportMismatchedResourceKeys(en.keySet(), zh.keySet());
        reportPlaceholderMismatches(en, zh);
        WaveXinAddon.LOG.info("WaveXin i18n validation checked {} required keys.", required.size());
    }

    private static Set<String> requiredKeys(Iterable<Module> modules) {
        Set<String> keys = new LinkedHashSet<>();
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

    private static Map<String, String> readLanguage(String language) {
        String path = "assets/wavexin/lang/" + language + ".json";
        try (InputStream stream = WaveXinI18n.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                WaveXinAddon.LOG.warn("WaveXin i18n validation could not find {}.", path);
                return Map.of();
            }

            JsonObject object = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                values.put(entry.getKey(), entry.getValue().getAsString());
            }
            return values;
        } catch (Exception e) {
            WaveXinAddon.LOG.warn("WaveXin i18n validation could not read {}.", path, e);
            return Map.of();
        }
    }

    private static void reportMissing(String language, Set<String> actual, Set<String> required) {
        for (String key : required) {
            if (!actual.contains(key)) WaveXinAddon.LOG.warn("WaveXin i18n missing {} key: {}", language, key);
        }
    }

    private static void reportMismatchedResourceKeys(Set<String> en, Set<String> zh) {
        for (String key : en) {
            if (!zh.contains(key)) WaveXinAddon.LOG.warn("WaveXin i18n zh_cn is missing en_us key: {}", key);
        }
        for (String key : zh) {
            if (!en.contains(key)) WaveXinAddon.LOG.warn("WaveXin i18n en_us is missing zh_cn key: {}", key);
        }
    }

    private static void reportPlaceholderMismatches(Map<String, String> en, Map<String, String> zh) {
        for (Map.Entry<String, String> entry : en.entrySet()) {
            String key = entry.getKey();
            String zhValue = zh.get(key);
            if (zhValue == null) continue;
            int enCount = countPlaceholders(entry.getValue());
            int zhCount = countPlaceholders(zhValue);
            if (enCount != zhCount) {
                WaveXinAddon.LOG.warn("WaveXin i18n placeholder mismatch for {}: en_us={}, zh_cn={}", key, enCount, zhCount);
            }
        }
    }

    private static int countPlaceholders(String value) {
        if (value == null) return 0;
        Matcher matcher = FORMAT_PATTERN.matcher(value);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    public static String keySegment(String value) {
        if (value == null || value.isBlank()) return "unnamed";
        String segment = value
            .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return segment.isEmpty() ? "unnamed" : segment;
    }
}