package me.waltom.wavexin;

import com.mojang.logging.LogUtils;
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
        AutoLogin.registerCommands();

        Modules.get().add(new ElytraFlyXin());
        Modules.get().add(new ElytraReplace());
        Modules.get().add(new SimpleElytraFlyPath());
        Modules.get().add(new ChickenNametags());
        Modules.get().add(new SnifferNametags());
        Modules.get().add(new AutoAnswerXin());
        Modules.get().add(new AutoLogin());
        Modules.get().add(new AutoRestockCoreXin());
        Modules.get().add(new BaseFinderXin());
        Modules.get().add(new NetherElytraPath());
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
