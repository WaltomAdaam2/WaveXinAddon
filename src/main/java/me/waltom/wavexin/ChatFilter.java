package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.regex.Pattern;

public class ChatFilter extends WaveXinModule {
    private static final int DEATH_MESSAGE_GREEN = 0x55FF55;
    private static final int DEATH_MESSAGE_RED = 0xFF5555;
    private static final Pattern LEGACY_FORMATTING_PATTERN = Pattern.compile("(?i)\\xA7[0-9A-FK-OR]");
    private static final Pattern PRIVATE_MESSAGE_HEADER_PATTERN = Pattern.compile("(?im)(?:^|\\R)\\s*(?:(?:from|to)\\s+[^\\r\\n:\\uFF1A]{1,64}\\s*[:\\uFF1A]|\\u6765\\u81ea\\s*[^\\r\\n:\\uFF1A]{1,64}\\s*[:\\uFF1A]|[^\\r\\n:\\uFF1A]{1,64}\\s+(?:whispers|tells you)\\b)");
    private static final Pattern PRIVATE_MESSAGE_TAG_PATTERN = Pattern.compile("(?i)\\[[^\\]]*(?:private\\s+message|\\u79c1\\u804a\\u4fe1\\u606f|\\u79c1\\u4fe1|\\u5bc6\\u8bed|\\u6084\\u6084\\u8bdd)[^\\]]*\\]");
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
        .description("Hides red and green server death messages")
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
        if (hideDeathMessages.get() && hasDeathMessageColors(message)) return true;
        return shouldHideRegularMessage(message.getString());
    }

    private boolean shouldHideRegularMessage(String message) {
        if (message == null) return false;
        String normalized = LEGACY_FORMATTING_PATTERN.matcher(message).replaceAll("").trim();
        if (normalized.isEmpty()) return false;

        if (hidePrivateMessages.get() && isPrivateMessage(normalized)) return true;
        return hidePublicMessages.get() && PUBLIC_MESSAGE_PATTERN.matcher(normalized).find();
    }

    private boolean hasDeathMessageColors(Text message) {
        boolean hasGreen = hasLegacyColor(message.getString(), 'a');
        boolean hasRed = hasLegacyColor(message.getString(), 'c');

        for (Text part : message.getWithStyle(Style.EMPTY)) {
            TextColor color = part.getStyle().getColor();
            if (color == null) continue;

            int rgb = color.getRgb();
            if (rgb == DEATH_MESSAGE_GREEN) hasGreen = true;
            if (rgb == DEATH_MESSAGE_RED) hasRed = true;
        }

        return hasGreen && hasRed;
    }

    private static boolean hasLegacyColor(String message, char color) {
        for (int i = 0; i < message.length() - 1; i++) {
            if (message.charAt(i) == '\u00A7' && Character.toLowerCase(message.charAt(i + 1)) == color) return true;
        }

        return false;
    }

    private boolean isPrivateMessage(String message) {
        return PRIVATE_MESSAGE_HEADER_PATTERN.matcher(message).find()
            || PRIVATE_MESSAGE_TAG_PATTERN.matcher(message).find();
    }
}