package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

import meteordevelopment.orbit.EventHandler;

import java.util.regex.Pattern;

public class ChatFilterXin extends WaveXinModule {
    private static final Pattern PRIVATE_MESSAGE_HEADER_PATTERN = Pattern.compile("(?im)(?:^|\\R)\\s*(?:(?:from|to)\\s+[^\\r\\n:\\uFF1A]{1,64}\\s*[:\\uFF1A]|\\u6765\\u81ea\\s*[^\\r\\n:\\uFF1A]{1,64}\\s*[:\\uFF1A]|[^\\r\\n:\\uFF1A]{1,64}\\s+(?:whispers|tells you)\\b)");
    private static final Pattern PRIVATE_MESSAGE_TAG_PATTERN = Pattern.compile("(?i)\\[[^\\]]*(?:private\\s+message|\\u79c1\\u804a\\u4fe1\\u606f|\\u79c1\\u4fe1|\\u5bc6\\u8bed|\\u6084\\u6084\\u8bdd)[^\\]]*\\]");
    private static final Pattern ENGLISH_DEATH_MESSAGE_PATTERN = Pattern.compile("(?i)\\b.+\\s(?:was slain by|was shot by|was blown up by|was killed by|died|fell(?: from| off)?|burned to death|went up in flames|drowned|hit the ground too hard|was squashed by|was struck by lightning|starved to death|withered away|froze to death|experienced kinetic energy)\\b.*");
    private static final Pattern CHINESE_DEATH_MESSAGE_PATTERN = Pattern.compile(".*(?:\\u6b7b\\u4ea1|\\u6b7b\\u4e86|\\u88ab.*\\u6740\\u6b7b|\\u88ab.*\\u51fb\\u6740|\\u88ab.*\\u5c04\\u6740|\\u88ab.*\\u5c04\\u6b7b|\\u88ab.*\\u6740\\u5bb3|\\u88ab.*\\u51fb\\u8d25|\\u6454\\u6b7b|\\u6dfa\\u6b7b|\\u70e7\\u6b7b|\\u70e7\\u70ed|\\u7206\\u70b8|\\u4ece.*\\u8dcc\\u843d|\\u7a92\\u606f|\\u51bb\\u6b7b|\\u997f\\u6b7b).*");
    private static final Pattern PUBLIC_MESSAGE_PATTERN = Pattern.compile("(?:^\\s*<[^>]+>\\s+.+|^\\s*[^\\s:]{1,32}:\\s+.+)");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> hidePrivateMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("Hide MSG Private Messages")
        .description("Hides private MSG messages")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> hidePublicMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("Hide Public Messages")
        .description("Hides normal public chat messages")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideDeathMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("Hide Death Messages")
        .description("Hides common player death messages")
        .defaultValue(false)
        .build()
    );

    public ChatFilterXin() {
        super(WaveXinAddon.CATEGORY, "chat-filter", "Filters selected chat message types");
    }

    public static boolean shouldHideServerMessage(String message) {
        ChatFilterXin module = Modules.get().get(ChatFilterXin.class);
        return module != null && module.isActive() && module.shouldHideMessage(message);
    }

    @EventHandler
    private void filterIncomingMessage(ReceiveMessageEvent event) {
        if (event.getMessage() != null && shouldHideMessage(event.getMessage().getString())) event.cancel();
    }

    private boolean shouldHideMessage(String message) {
        if (message == null) return false;
        if (hidePrivateMessages.get() && isPrivateMessage(message)) return true;
        if (hideDeathMessages.get() && isDeathMessage(message)) return true;
        return hidePublicMessages.get() && PUBLIC_MESSAGE_PATTERN.matcher(message).find();
    }

    private boolean isPrivateMessage(String message) {
        return PRIVATE_MESSAGE_HEADER_PATTERN.matcher(message).find()
            || PRIVATE_MESSAGE_TAG_PATTERN.matcher(message).find();
    }

    private boolean isDeathMessage(String message) {
        return ENGLISH_DEATH_MESSAGE_PATTERN.matcher(message).matches()
            || CHINESE_DEATH_MESSAGE_PATTERN.matcher(message).matches();
    }
}
