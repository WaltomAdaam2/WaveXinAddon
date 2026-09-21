package me.waltom.wavexin.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class WaveXinCommand extends Command {
    public enum RedeemResult { INVALID, SAVE_FAILED, SCAN, KILL_AURA }

    private final Function<String, RedeemResult> onRedeemed;
    private final Consumer<Boolean> onUpdateCheckChanged;
    private final Consumer<String> onLanguageChanged;
    private final BiConsumer<String, Boolean> onDebugChanged;

    public WaveXinCommand(Function<String, RedeemResult> onRedeemed, Consumer<Boolean> onUpdateCheckChanged, Consumer<String> onLanguageChanged, BiConsumer<String, Boolean> onDebugChanged) {
        super("wavexin", "WaveXinAddon settings and access commands.");
        this.onRedeemed = onRedeemed;
        this.onUpdateCheckChanged = onUpdateCheckChanged;
        this.onLanguageChanged = onLanguageChanged;
        this.onDebugChanged = onDebugChanged;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("check-update").then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
            boolean enabled = BoolArgumentType.getBool(context, "enabled");
            onUpdateCheckChanged.accept(enabled);
            info(WaveXinI18n.tr("message.wavexin.update_check.setting_saved", "Update checks on startup: %s.", enabled));
            return SINGLE_SUCCESS;
        }))).then(literal("redeem").then(argument("code", StringArgumentType.word()).executes(context ->
            redeem(StringArgumentType.getString(context, "code")))))
            .then(literal("lang")
                .then(literal("Chinese").executes(context -> setLanguage("zh_cn")))
                .then(literal("English").executes(context -> setLanguage("en_us"))))
            .then(literal("debug")
                .then(debugModule("autologin"))
                .then(debugModule("basefinder"))
                .then(debugModule("elytraflypath"))
                .then(debugModule("container"))
                .then(debugModule("printer"))
                .then(debugModule("endbasefinder")));
    }

    private LiteralArgumentBuilder<CommandSource> debugModule(String name) {
        return literal(name).then(literal("on").executes(context -> setDebug(name, true)))
            .then(literal("off").executes(context -> setDebug(name, false)));
    }

    private int setDebug(String module, boolean enabled) {
        onDebugChanged.accept(module, enabled);
        String state = enabled ? WaveXinI18n.tr("status.wavexin.module.on", "on")
            : WaveXinI18n.tr("status.wavexin.module.off", "off");
        info(WaveXinI18n.tr("message.wavexin.debug.setting_saved", "WaveXin debug for %s: %s.", module, state));
        return SINGLE_SUCCESS;
    }

    private int redeem(String code) {
        RedeemResult result = onRedeemed.apply(code);
        if (result == RedeemResult.INVALID) {
            error(WaveXinI18n.tr("error.wavexin.redeem.invalid", "The redemption code is invalid."));
            return 0;
        }
        if (result == RedeemResult.SAVE_FAILED) {
            error(WaveXinI18n.tr("error.wavexin.redeem.license_save_failed", "Could not save the local license file."));
            return 0;
        }
        if (result == RedeemResult.KILL_AURA) {
            info(WaveXinI18n.tr("message.wavexin.redeem.killaura_success", "Redeemed successfully. KillAura+ is now available."));
        } else {
            info(WaveXinI18n.tr("message.wavexin.redeem.success", "Redeemed successfully. The optional scan module is now available."));
        }
        return SINGLE_SUCCESS;
    }

    private int setLanguage(String language) {
        onLanguageChanged.accept(language);
        info(WaveXinI18n.tr("message.wavexin.language.setting_saved", "WaveXin language changed."));
        return SINGLE_SUCCESS;
    }
}
