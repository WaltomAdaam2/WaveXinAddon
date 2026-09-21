package me.waltom.wavexin.i18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline guard for the current source conventions. It intentionally parses only the
 * setting-builder forms used by registered WaveXin modules; an unknown group or enum is
 * a failure, not a skipped setting. It does not instantiate Meteor or Minecraft classes.
 */
public final class WaveXinUiTranslationCoverageTest {
    private static final Pattern IMPORT = Pattern.compile("import\\s+([\\w.]+)\\.([A-Z]\\w*);");
    private static final Pattern NEW_CLASS = Pattern.compile("new\\s+(\\w+)\\s*\\(");
    private static final Pattern MODULE = Pattern.compile("super\\(\\s*WaveXinAddon\\.CATEGORY,\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern GROUP = Pattern.compile("SettingGroup\\s+(\\w+)\\s*=\\s*settings\\.(getDefaultGroup\\(\\)|createGroup\\(\\\"([^\\\"]+)\\\"\\))");
    private static final Pattern GROUP_ALIAS = Pattern.compile("SettingGroup\\s+(\\w+)\\s*=\\s*(\\w+)\\s*;");
    private static final Pattern DIRECT_SETTING = Pattern.compile("(?s)(\\w+)\\.add\\(new\\s+[\\w.]+\\.Builder(?:<[^>]+>)?\\s*\\(\\).*?\\.name\\(\\\"([^\\\"]*)\\\"\\).*?\\.build\\(\\)");
    private static final Pattern HELPER_SETTING = Pattern.compile("(?:add|bool|color)\\(\\s*(\\w+)\\s*,\\s*\\\"([^\\\"]+)\\\"\\s*,");
    private static final Pattern BUILDER_HELPER_SETTING = Pattern.compile("(\\w+)\\.add\\(color\\(\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern CONSTRUCTOR_SETTING = Pattern.compile("(\\w+)\\.add\\(new\\s+\\w+Setting\\(\\s*\\\"([^\\\"]+)\\\"\\s*,");
    private static final Pattern DELAY_SETTING = Pattern.compile("(?m)^\\s*public\\s+final\\s+Setting<Integer>\\s+\\w+\\s*=\\s*delay\\(\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern RESTOCK_SETTING = Pattern.compile("(?m)^\\s*private\\s+final\\s+Setting<\\w+>\\s+\\w+\\s*=\\s*(?:coordinate|storedCorner)\\(\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SETTING_FIELD = Pattern.compile("(?m)^\\s*(?:private|public|protected)\\s+(?:final\\s+)?(?:Setting<.*?>|\\w+Setting)\\s+\\w+\\s*=");
    private static final Pattern ENUM_BUILDER = Pattern.compile("new\\s+EnumSetting\\.Builder<([^>]+)>");
    private static final Pattern ENUM_DECLARATION = Pattern.compile("(?s)enum\\s+(\\w+)\\s*\\{([^;}]*)");
    private static final Map<String, List<String>> EXTERNAL_ENUMS = Map.of(
        "ShapeMode", List.of("LINES", "SIDES", "BOTH"),
        "Hand", List.of("MAIN_HAND", "OFF_HAND")
    );

    private WaveXinUiTranslationCoverageTest() {}

    public static void main(String[] args) throws IOException {
        Path root = projectRoot();
        Path main = root.resolve("src/main/java");
        Path lang = root.resolve("src/main/resources/assets/wavexin/lang");
        Set<String> en = jsonKeys(lang.resolve("en_us.json"));
        Set<String> zh = jsonKeys(lang.resolve("zh_cn.json"));
        Set<String> expected = expectedKeys(lang.resolve("expected_keys.txt"));
        Map<String, Path> registered = registeredModules(main.resolve("me/waltom/wavexin/WaveXinAddon.java"), main);
        if (registered.isEmpty()) throw new AssertionError("No registered module constructors were parsed.");

        Set<String> required = new LinkedHashSet<>();
        Set<String> enumTypes = new LinkedHashSet<>();
        int settings = 0;
        int groupCount = 0;
        for (Map.Entry<String, Path> entry : registered.entrySet()) {
            String source = Files.readString(entry.getValue());
            String slug = one(MODULE, source, entry.getValue() + " module slug");
            String module = keySegment(slug);
            required.add("module.wavexin." + module + ".title");
            required.add("module.wavexin." + module + ".description");

            Map<String, String> groups = groups(source, entry.getValue());
            for (String group : groups.values()) required.add("group.wavexin." + module + "." + group);
            groupCount += groups.size();
            settings += addSettings(required, enumTypes, source, groups, module, entry.getValue());
        }

        Map<String, List<String>> enumConstants = enumConstants(main);
        for (String type : enumTypes) {
            String simple = type.substring(type.lastIndexOf('.') + 1).trim();
            List<String> constants = enumConstants.get(simple);
            if (constants == null) constants = EXTERNAL_ENUMS.get(simple);
            if (constants == null || constants.isEmpty()) throw new AssertionError("Unsupported enum parser type: " + type);
            for (String constant : constants) required.add("enum.wavexin." + keySegment(simple) + "." + keySegment(constant));
        }

        assertCovered("en_us", required, en, expected);
        assertCovered("zh_cn", required, zh, expected);
        System.out.println("WaveXin UI translation coverage: modules=" + registered.size() + ", groups=" + groupCount
            + ", settings=" + settings + ", enumTypes=" + enumTypes.size() + ", keys=" + required.size());
    }

    private static Map<String, Path> registeredModules(Path addon, Path main) throws IOException {
        String source = Files.readString(addon);
        Map<String, Path> imports = new HashMap<>();
        Matcher imported = IMPORT.matcher(source);
        while (imported.find()) imports.put(imported.group(2), main.resolve((imported.group(1) + "." + imported.group(2)).replace('.', '/') + ".java"));
        Map<String, Path> result = new LinkedHashMap<>();
        Matcher created = NEW_CLASS.matcher(source);
        while (created.find()) {
            String simple = created.group(1);
            Path path = imports.get(simple);
            if (path == null || !Files.isRegularFile(path)) continue;
            String candidate = Files.readString(path);
            if (candidate.contains("extends WaveXinModule")) {
                if (!MODULE.matcher(candidate).find()) throw new AssertionError("Unrecognized registered module constructor: " + path);
                result.put(simple, path);
            }
        }
        return result;
    }

    private static Map<String, String> groups(String source, Path file) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = GROUP.matcher(source);
        while (matcher.find()) result.put(matcher.group(1), matcher.group(3) == null ? "general" : keySegment(matcher.group(3)));
        Matcher aliases = GROUP_ALIAS.matcher(source);
        while (aliases.find()) {
            String group = result.get(aliases.group(2));
            if (group == null) throw new AssertionError("Unsupported setting group alias '" + aliases.group(2) + "' in " + file);
            result.put(aliases.group(1), group);
        }
        if (result.isEmpty()) throw new AssertionError("No setting groups parsed: " + file);
        return result;
    }

    private static int addSettings(Set<String> required, Set<String> enumTypes, String source,
                                    Map<String, String> groups, String module, Path file) {
        int nestedClass = source.indexOf("private static class");
        String definitions = nestedClass < 0 ? source : source.substring(0, nestedClass);
        Set<String> seen = new HashSet<>();
        int declarations = 0;
        Matcher direct = DIRECT_SETTING.matcher(definitions);
        while (direct.find()) {
            addSetting(required, groups, module, direct.group(1), direct.group(2), file, seen);
            declarations++;
        }
        Matcher helper = HELPER_SETTING.matcher(definitions);
        while (helper.find()) { addSetting(required, groups, module, helper.group(1), helper.group(2), file, seen); declarations++; }
        Matcher builderHelper = BUILDER_HELPER_SETTING.matcher(definitions);
        while (builderHelper.find()) { addSetting(required, groups, module, builderHelper.group(1), builderHelper.group(2), file, seen); declarations++; }
        Matcher password = CONSTRUCTOR_SETTING.matcher(definitions);
        while (password.find()) { addSetting(required, groups, module, password.group(1), password.group(2), file, seen); declarations++; }
        Matcher delay = DELAY_SETTING.matcher(definitions);
        while (delay.find()) { addSetting(required, groups, module, "sgDelays", delay.group(1), file, seen); declarations++; }
        Matcher restock = RESTOCK_SETTING.matcher(definitions);
        while (restock.find()) { addSetting(required, groups, module, "sgRestock", restock.group(1), file, seen); declarations++; }
        Matcher enumBuilder = ENUM_BUILDER.matcher(definitions);
        while (enumBuilder.find()) enumTypes.add(enumBuilder.group(1));
        if (seen.isEmpty()) throw new AssertionError("No settings parsed: " + file);
        int fields = 0;
        Matcher field = SETTING_FIELD.matcher(definitions);
        while (field.find()) fields++;
        if (seen.size() != fields) throw new AssertionError("Unsupported, duplicate, or skipped setting assignment in " + file + ": fields=" + fields + " parsed=" + seen.size());
        return fields;
    }

    private static void addSetting(Set<String> required, Map<String, String> groups, String module,
                                   String groupVariable, String title, Path file, Set<String> seen) {
        String group = groups.get(groupVariable);
        if (group == null) throw new AssertionError("Unsupported setting group '" + groupVariable + "' in " + file);
        String setting = keySegment(title);
        String prefix = "setting.wavexin." + module + "." + group + "." + setting;
        if (seen.add(prefix)) {
            required.add(prefix + ".title");
            required.add(prefix + ".description");
        }
    }

    private static Map<String, List<String>> enumConstants(Path main) throws IOException {
        Map<String, Set<String>> collected = new HashMap<>();
        try (var files = Files.walk(main)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = ENUM_DECLARATION.matcher(Files.readString(file));
                while (matcher.find()) {
                    List<String> constants = enumValues(matcher.group(2));
                    if (!constants.isEmpty()) collected.computeIfAbsent(matcher.group(1), ignored -> new LinkedHashSet<>()).addAll(constants);
                }
            }
        }
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : collected.entrySet()) result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        return result;
    }

    private static List<String> enumValues(String body) {
        List<String> result = new ArrayList<>();
        for (String token : topLevelCommaParts(body.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", ""))) {
            Matcher name = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_]*)").matcher(token);
            if (!name.find()) throw new AssertionError("Unsupported enum constant syntax: " + token.strip());
            result.add(name.group(1));
        }
        return result;
    }

    private static List<String> topLevelCommaParts(String text) {
        List<String> result = new ArrayList<>();
        int start = 0, depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) { result.add(text.substring(start, i)); start = i + 1; }
        }
        String last = text.substring(start);
        if (!last.isBlank()) result.add(last);
        return result;
    }

    private static Set<String> jsonKeys(Path file) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?m)^\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"\\s*:").matcher(Files.readString(file));
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static Set<String> expectedKeys(Path file) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        for (String line : Files.readAllLines(file)) if (!line.isBlank() && !line.strip().startsWith("#")) result.add(line.strip());
        return result;
    }

    private static void assertCovered(String language, Set<String> required, Set<String> resource, Set<String> expected) {
        Set<String> missingResource = new LinkedHashSet<>(required); missingResource.removeAll(resource);
        Set<String> missingExpected = new LinkedHashSet<>(required); missingExpected.removeAll(expected);
        if (!missingResource.isEmpty() || !missingExpected.isEmpty()) throw new AssertionError(
            language + " missing resource=" + missingResource + " expected_keys=" + missingExpected
        );
    }

    private static String one(Pattern pattern, String source, String label) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) throw new AssertionError("Could not parse " + label);
        return matcher.group(1);
    }

    private static Path projectRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("src/main/java/me/waltom/wavexin/WaveXinAddon.java"))) path = path.getParent();
        if (path == null) throw new AssertionError("Run from the WaveXinAddon-SNAPSHOT project tree.");
        return path;
    }

    private static String keySegment(String value) {
        if (value == null || value.isBlank()) return "unnamed";
        String result = value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return result.isEmpty() ? "unnamed" : result;
    }
}
