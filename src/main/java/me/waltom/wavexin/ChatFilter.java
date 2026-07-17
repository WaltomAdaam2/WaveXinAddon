package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;

import java.util.regex.Pattern;

public class ChatFilter extends WaveXinModule {
    private static final Pattern LEGACY_FORMATTING_PATTERN = Pattern.compile("(?i)\\xA7[0-9A-FK-OR]");
    private static final String PLAYER_NAME = "[^\\s:：\\[\\]<>（）()]{1,64}";
    private static final String DEATH_COUNT = "(?:\\s*[（(]\\d+[）)])?";
    private static final Pattern CHINESE_DEATH_MESSAGE_PATTERN = Pattern.compile(
        "^" + PLAYER_NAME + "\\s+(?:"
            + "自杀"
            + "|被\\s+.+?\\s+(?:击杀|杀死|射杀|射死|炸死|摔死|淹死|烧死|窒息|冻死|饿死)"
            + "|被\\s+.+?(?:而死亡|死亡)"
            + "|(?:因|从)\\s+.+?(?:死亡|摔死|淹死|烧死|窒息|冻死|饿死)"
            + ")" + DEATH_COUNT + "$"
    );
    private static final Pattern ENGLISH_DEATH_MESSAGE_PATTERN = Pattern.compile(
        "^" + PLAYER_NAME + "\\s+(?:"
            + "was (?:slain|shot|blown up|killed|doomed to fall|squashed|impaled|fireballed|stung|pummeled)"
            + "|was (?:killed|blown up|shot|slain) by .+"
            + "|drowned|fell from a high place|hit the ground too hard|went up in flames|burned to death|starved to death|froze to death"
            + ")" + DEATH_COUNT + "$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PRIVATE_MESSAGE_HEADER_PATTERN = Pattern.compile("(?im)(?:^|\\R)\\s*(?:(?:from|to)\\s+[^\\r\\n:：]{1,64}\\s*[:：]|来自\\s*[^\\r\\n:：]{1,64}\\s*[:：]|[^\\r\\n:：]{1,64}\\s+(?:whispers|tells you)\\b)");
    private static final Pattern PRIVATE_MESSAGE_TAG_PATTERN = Pattern.compile("(?i)\\[[^\\]]*(?:private\\s+message|私聊信息|私信|密语|悄悄话)[^\\]]*\\]");
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
        .description("Hides structured server death announcements")
        .defaultValue(false)
        .build()
    );

    public ChatFilter() {
        super(WaveXinAddon.CATEGORY, "chat-filter", "Filters selected chat message types");
    }

    public static boolean shouldHideServerMessage(Text message) {
        ChatFilter module = Modules.get().get(ChatFilter.class);
        return module != null && module.isActive() && module.shouldHideServerMessageInternal(message);
    }

    @EventHandler
    private void filterIncomingMessage(ReceiveMessageEvent event) {
        if (event.getMessage() != null && shouldHideRegularMessage(event.getMessage().getString())) event.cancel();
    }

    private boolean shouldHideServerMessageInternal(Text message) {
        if (message == null) return false;
        String normalized = normalize(message.getString());
        if (hideDeathMessages.get() && isDeathMessage(normalized)) return true;
        return shouldHideRegularMessage(normalized);
    }

    private boolean shouldHideRegularMessage(String message) {
        if (message == null) return false;
        String normalized = normalize(message);
        if (normalized.isEmpty()) return false;

        if (hidePrivateMessages.get() && isPrivateMessage(normalized)) return true;
        return hidePublicMessages.get() && PUBLIC_MESSAGE_PATTERN.matcher(normalized).find();
    }

    private static String normalize(String message) {
        return LEGACY_FORMATTING_PATTERN.matcher(message).replaceAll("").trim();
    }

    private static boolean isDeathMessage(String message) {
        if (message.isEmpty() || message.contains("\n") || message.contains("\r")) return false;
        return CHINESE_DEATH_MESSAGE_PATTERN.matcher(message).matches()
            || ENGLISH_DEATH_MESSAGE_PATTERN.matcher(message).matches();
    }

    private boolean isPrivateMessage(String message) {
        return PRIVATE_MESSAGE_HEADER_PATTERN.matcher(message).find()
            || PRIVATE_MESSAGE_TAG_PATTERN.matcher(message).find();
    }
}