package me.waltom.wavexin;

import me.waltom.wavexin.commands.PrinterSelectionCommand;
import me.waltom.wavexin.commands.WaveXinCommand;
import me.waltom.wavexin.core.EndGatewayFeatureAccess;
import me.waltom.wavexin.core.KillAuraFeatureAccess;
import me.waltom.wavexin.modules.killaura.KillAuraPlus;
import me.waltom.wavexin.core.UpdateChecker;
import me.waltom.wavexin.core.WaveXinSettingsStore;
import me.waltom.wavexin.modules.sniffernametags.SnifferNametags;
import me.waltom.wavexin.modules.elytraflypath.ElytraFlyPath;
import me.waltom.wavexin.modules.chickennametags.ChickenNametags;
import me.waltom.wavexin.modules.chatfilter.ChatFilter;
import me.waltom.wavexin.modules.betterelytrafly.BetterElytraFly;
import me.waltom.wavexin.modules.basefinder.BaseFinder;
import me.waltom.wavexin.modules.containerrecorder.ContainerRecorderModule;
import me.waltom.wavexin.modules.endgateway.EndGatewayFinder;
import me.waltom.wavexin.modules.litematicaprinter.LitematicaPrinter;
import me.waltom.wavexin.modules.litematicaprinter.PrinterSupplySelectionRenderer;
import me.waltom.wavexin.modules.autologin.AutoLogin;
import me.waltom.wavexin.core.WaveXinSettingsAutoSaver;
import me.waltom.wavexin.i18n.WaveXinI18n;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Commands;
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
    private EndGatewayFinder endGatewayFinder;
    private KillAuraPlus killAuraPlus;
    private ContainerRecorderModule containerRecorder;

    @Override
    public void onInitialize() {
        LOG.info("Initializing WaveXinAddon.");
        WaveXinSettingsStore.loadFeatureFlags();
        ChatUtils.registerCustomPrefix(getPackage(), WaveXinAddon::createChatPrefix);
        BetterChat.registerCustomHead("[WaveXin]", CHAT_AVATAR);
        MeteorClient.EVENT_BUS.subscribe(WaveXinSettingsAutoSaver.INSTANCE);
        Modules.get().add(new BetterElytraFly());
        Modules.get().add(new ElytraFlyPath());
        Modules.get().add(new ChickenNametags());
        Modules.get().add(new SnifferNametags());
        Modules.get().add(new AutoLogin());
        Modules.get().add(new ChatFilter());
        containerRecorder = new ContainerRecorderModule();
        Modules.get().add(containerRecorder);
        Modules.get().add(new BaseFinder(containerRecorder));
        LitematicaPrinter printer = new LitematicaPrinter();
        Modules.get().add(printer);
        Commands.add(new PrinterSelectionCommand(printer));
        Commands.add(new WaveXinCommand(this::redeemFeature, this::setUpdateCheckEnabled, this::setLanguage, this::setDebugMode));
        registerEndGatewayFinderIfEnabled();
        registerKillAuraIfEnabled();
        MeteorClient.EVENT_BUS.subscribe(new PrinterSupplySelectionRenderer(printer));
        WaveXinI18n.validateResources(Modules.get().getAll());
        UpdateChecker.checkOnStartup();
    }

    private WaveXinCommand.RedeemResult redeemFeature(String code) {
        if (EndGatewayFeatureAccess.matches(code)) {
            if (!EndGatewayFeatureAccess.issueLicense()) return WaveXinCommand.RedeemResult.SAVE_FAILED;
            registerEndGatewayFinderIfEnabled();
            return WaveXinCommand.RedeemResult.SCAN;
        }
        if (KillAuraFeatureAccess.matches(code)) {
            if (!KillAuraFeatureAccess.issueLicense()) return WaveXinCommand.RedeemResult.SAVE_FAILED;
            registerKillAuraIfEnabled();
            return WaveXinCommand.RedeemResult.KILL_AURA;
        }
        return WaveXinCommand.RedeemResult.INVALID;
    }

    private void registerKillAuraIfEnabled() {
        if (killAuraPlus != null || !KillAuraFeatureAccess.hasValidLicense()) return;
        killAuraPlus = new KillAuraPlus();
        Modules.get().add(killAuraPlus);
        Modules.get().sortModules();
    }

    private void registerEndGatewayFinderIfEnabled() {
        if (!EndGatewayFeatureAccess.hasValidLicense() || endGatewayFinder != null) return;
        endGatewayFinder = new EndGatewayFinder(containerRecorder);
        Modules.get().add(endGatewayFinder);
        Modules.get().sortModules();
    }

    private void setUpdateCheckEnabled(boolean enabled) {
        WaveXinSettingsStore.setUpdateCheckEnabled(enabled, Modules.get().getGroup(CATEGORY));
        if (!enabled) UpdateChecker.cancel();
    }

    private void setLanguage(String language) {
        WaveXinSettingsStore.setLanguage(language, Modules.get().getGroup(CATEGORY));
    }

    private void setDebugMode(String module, boolean enabled) {
        switch (module) {
            case "autologin" -> Modules.get().get(AutoLogin.class).setDebugMode(enabled);
            case "basefinder" -> Modules.get().get(BaseFinder.class).setDebugMode(enabled);
            case "elytraflypath" -> Modules.get().get(ElytraFlyPath.class).setDebugMode(enabled);
            case "container" -> Modules.get().get(ContainerRecorderModule.class).setDebugMode(enabled);
            case "printer" -> Modules.get().get(LitematicaPrinter.class).setDebugMode(enabled);
            case "endbasefinder" -> { if (endGatewayFinder != null) endGatewayFinder.setDebugMode(enabled); }
            default -> throw new IllegalArgumentException("Unsupported WaveXin debug module: " + module);
        }
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
