package me.waltom.wavexin.modules.chatfilter;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinModule;
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
    private static final String PLAYER_NAME = "[^\\s:\\[\\]<>\\uFF08\\uFF09()]{1,64}";
    private static final String DEATH_DETAIL = "[^\\r\\n]+?";
    private static final String DEATH_COUNT = "(?:\\s*[\\(\\uFF08]\\d+[\\)\\uFF09])?";
    private static final Pattern CHINESE_DEATH_MESSAGE_PATTERN = Pattern.compile(
        "^" + PLAYER_NAME + "\\s+(?:"
            + "(?:自杀|在\\S{1,16}中自杀|撞墙自杀|跳入\\S{1,16}自杀|使用重生锚炸死自己|用TNT炸死了自己|炸死了自己|毒死了自己|饿死了自己|被自己的弓箭射死)"
            + "|被\\s+" + DEATH_DETAIL + "\\s+(?:击杀|杀死|射杀|射死|炸死|刺死|火球击杀|推下悬崖而死亡|推到了虚空|用岩浆烧死)"
            + "|被\\s+" + DEATH_DETAIL + "\\s+的荆棘反杀"
            + "|(?:着火烧死|被烧死|窒息而亡|冻死|饿死|从高处摔死|摔死|摔得过猛|被魔法杀死)"
            + ")" + DEATH_COUNT + "$"
    );
    private static final Pattern ENGLISH_DEATH_MESSAGE_PATTERN = Pattern.compile(
        "^" + PLAYER_NAME + "\\s+(?:"
            + "was (?:impaled|shot|slain|bogged by) .+"
            + "|was (?:slain|shot|blown up|killed|doomed to fall|squashed|impaled|fireballed|stung|pummeled)"
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
        if (event.getMessage() != null && shouldHideServerMessageInternal(event.getMessage())) event.cancel();
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

    static String normalize(String message) {
        return LEGACY_FORMATTING_PATTERN.matcher(message).replaceAll("").trim();
    }

    static boolean isDeathMessage(String message) {
        if (message.isEmpty() || message.contains("\n") || message.contains("\r")) return false;
        return CHINESE_DEATH_MESSAGE_PATTERN.matcher(message).matches()
            || ENGLISH_DEATH_MESSAGE_PATTERN.matcher(message).matches();
    }

    private boolean isPrivateMessage(String message) {
        return PRIVATE_MESSAGE_HEADER_PATTERN.matcher(message).find()
            || PRIVATE_MESSAGE_TAG_PATTERN.matcher(message).find();
    }
}
