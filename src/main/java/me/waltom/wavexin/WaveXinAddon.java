package me.waltom.wavexin;

import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.systems.modules.misc.BetterChat;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class WaveXinAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("WaveXinAddon");
    private static final Identifier CHAT_AVATAR = Identifier.of("wavexin", "textures/icons/chat/wavexin.png");

    @Override
    public void onInitialize() {
        LOG.info("Initializing WaveXinAddon.");
        ChatUtils.registerCustomPrefix(getPackage(), WaveXinAddon::createChatPrefix);
        BetterChat.registerCustomHead("[WaveXin]", CHAT_AVATAR);
        MeteorClient.EVENT_BUS.subscribe(new WaveXinSettingsAutoSaver());
        Modules.get().add(new BetterElytraFly());
        Modules.get().add(new SimpleElytraFlyPath());
        Modules.get().add(new ChickenNametags());
        Modules.get().add(new SnifferNametags());
        Modules.get().add(new AutoLogin());
        Modules.get().add(new ChatFilter());
        Modules.get().add(new BaseFinder());
    }

    private static Text createChatPrefix() {
        return Text.empty()
            .setStyle(Style.EMPTY.withFormatting(Formatting.GRAY))
            .append("[")
            .append(Text.literal("WaveXin").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xA38C6F))))
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
