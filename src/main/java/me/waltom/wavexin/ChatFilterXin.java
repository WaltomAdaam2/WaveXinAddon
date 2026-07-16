package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

import meteordevelopment.orbit.EventHandler;

import java.util.regex.Pattern;

public class ChatFilterXin extends WaveXinModule {
    private static final Pattern PRIVATE_MESSAGE_PATTERN = Pattern.compile("(?i)(?:^\\s*(?:from|to)\\s+[^:]+:|^\\s*\\u6765\\u81ea\\s*[^:]+:|\\[[^\\]]*(?:\\u79c1\\u804a\\u4fe1\\u606f|\\u79c1\\u4fe1|\\u5bc6\\u8bed|\\u6084\\u6084\\u8bdd)[^\\]]*\\]|\\b(?:whispers|tells you|private message)\\b)");
    private static final Pattern ENGLISH_DEATH_MESSAGE_PATTERN = Pattern.compile("(?i)\\b.+\\s(?:was slain by|was shot by|was blown up by|was killed by|died|fell(?: from| off)?|burned to death|went up in flames|drowned|hit the ground too hard|was squashed by|was struck by lightning|starved to death|withered away|froze to death|experienced kinetic energy)\\b.*");
    private static final Pattern CHINESE_DEATH_MESSAGE_PATTERN = Pattern.compile(".*(?:\\u6b7b\\u4ea1|\\u6b7b\\u4e86|\\u88ab.*\\u6740\\u6b7b|\\u88ab.*\\u51fb\\u6740|\\u6454\\u6b7b|\\u6dfa\\u6b7b|\\u70e7\\u6b7b|\\u70e7\\u70ed|\\u7206\\u70b8|\\u4ece.*\\u8dcc\\u843d|\\u7a92\\u606f|\\u51bb\\u6b7b|\\u997f\\u6b7b).*");
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

    @EventHandler
    private void filterIncomingMessage(ReceiveMessageEvent event) {
        if (event.getMessage() == null) return;

        String message = event.getMessage().getString();
        if (hidePrivateMessages.get() && PRIVATE_MESSAGE_PATTERN.matcher(message).find()) {
            event.cancel();
            return;
        }
        if (hideDeathMessages.get() && isDeathMessage(message)) {
            event.cancel();
            return;
        }
        if (hidePublicMessages.get() && PUBLIC_MESSAGE_PATTERN.matcher(message).find()) event.cancel();
    }

    private boolean isDeathMessage(String message) {
        return ENGLISH_DEATH_MESSAGE_PATTERN.matcher(message).matches()
            || CHINESE_DEATH_MESSAGE_PATTERN.matcher(message).matches();
    }
}
