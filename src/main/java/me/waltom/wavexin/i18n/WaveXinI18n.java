package me.waltom.wavexin.i18n;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.Locale;

public final class WaveXinI18n {
    public static final String PACKAGE_PREFIX = "me.waltom.wavexin.";

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
        return tr("group.wavexin." + keySegment(module.name) + "." + keySegment(group.name), group.name);
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
        return tr(
            "enum.wavexin." + keySegment(value.getDeclaringClass().getSimpleName()) + "." + keySegment(value.name()),
            value.toString()
        );
    }

    public static String keySegment(String value) {
        if (value == null || value.isBlank()) return "unnamed";
        String segment = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return segment.isEmpty() ? "unnamed" : segment;
    }
}
