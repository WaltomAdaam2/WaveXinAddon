package me.waltom.wavexin;

import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class WaveXinAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("WaveXinAddon");

    @Override
    public void onInitialize() {
        LOG.info("Initializing WaveXinAddon.");
        ChatUtils.registerCustomPrefix(getPackage(), WaveXinAddon::createChatPrefix);
        Modules.get().add(new ElytraFlyXin());
        Modules.get().add(new SimpleElytraFlyPath());
        Modules.get().add(new ChickenNametags());
        Modules.get().add(new SnifferNametags());
        Modules.get().add(new AutoLogin());
        Modules.get().add(new ChatFilterXin());
        Modules.get().add(new BaseFinderXin());
    }

    private static Text createChatPrefix() {
        return Text.empty()
            .setStyle(Style.EMPTY.withFormatting(Formatting.GRAY))
            .append("[")
            .append(Text.literal("WaveXin").setStyle(Style.EMPTY.withFormatting(Formatting.LIGHT_PURPLE)))
            .append("] ");
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "me.waltom.wavexin";
    }
}
