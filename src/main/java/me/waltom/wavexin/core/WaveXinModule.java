package me.waltom.wavexin.core;

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
            ChatUtils.sendMsg(hashCode(), Formatting.GRAY, "%s toggled %s.", title, isActive() ? Formatting.GREEN + "on" : Formatting.RED + "off");
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
}