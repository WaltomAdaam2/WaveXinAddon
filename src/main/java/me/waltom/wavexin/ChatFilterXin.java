package me.waltom.wavexin;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.Locale;
import java.util.regex.Pattern;

public class ChatFilterXin extends Module {
    private static final Pattern PRIVATE_MESSAGE_PATTERN = Pattern.compile("(?i)(?:^\\s*(?:from|to)\\s+[^:]+:|\\b(?:whispers|tells you|private message|私信|密语|悄悄话)\\b)");
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
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains(" was slain")
            || lower.contains(" died")
            || lower.contains(" fell")
            || lower.contains(" burned")
            || lower.contains(" blew up")
            || lower.contains(" drowned")
            || lower.contains(" hit the ground")
            || message.contains("死亡")
            || message.contains("被") && message.contains("杀死")
            || message.contains("摔死")
            || message.contains("淹死")
            || message.contains("烧死")
            || message.contains("爆炸");
    }
}