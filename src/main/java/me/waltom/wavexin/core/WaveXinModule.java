package me.waltom.wavexin.core;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public abstract class WaveXinModule extends Module {
    protected WaveXinModule(Category category, String name, String description, String... aliases) {
        super(category, name, description, aliases);
    }

    @Override
    public void sendToggledMsg() {
        if (Config.get().chatFeedback.get() && chatFeedback) {
            boolean active = isActive();
            String state = active
                ? Formatting.GREEN + WaveXinI18n.tr("status.wavexin.module.on", "on")
                : Formatting.RED + WaveXinI18n.tr("status.wavexin.module.off", "off");
            String key = active ? "message.wavexin.module.enabled" : "message.wavexin.module.disabled";
            ChatUtils.sendMsg(hashCode(), Formatting.GRAY, WaveXinI18n.tr(key, "%s toggled %s.", WaveXinI18n.moduleTitle(this), state));
        }
    }

    @Override
    public void info(Text message) {
        ChatUtils.sendMsg(message);
    }

    @Override
    public void info(String message, Object... args) {
        ChatUtils.info(message, args);
    }

    @Override
    public void warning(String message, Object... args) {
        ChatUtils.warning(message, args);
    }

    @Override
    public void error(String message, Object... args) {
        ChatUtils.error(message, args);
    }

    protected final void infoKey(String key, String fallback, Object... args) {
        info(WaveXinI18n.tr(key, fallback, args));
    }

    protected final void warningKey(String key, String fallback, Object... args) {
        warning(WaveXinI18n.tr(key, fallback, args));
    }

    protected final void errorKey(String key, String fallback, Object... args) {
        error(WaveXinI18n.tr(key, fallback, args));
    }
}
