package me.waltom.wavexin.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.waltom.wavexin.core.EndGatewayFeatureAccess;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class WaveXinCommand extends Command {
    private final BooleanSupplier onRedeemed;
    private final Consumer<Boolean> onUpdateCheckChanged;
    private final Consumer<String> onLanguageChanged;

    public WaveXinCommand(BooleanSupplier onRedeemed, Consumer<Boolean> onUpdateCheckChanged, Consumer<String> onLanguageChanged) {
        super("wavexin", "WaveXinAddon settings and access commands.");
        this.onRedeemed = onRedeemed;
        this.onUpdateCheckChanged = onUpdateCheckChanged;
        this.onLanguageChanged = onLanguageChanged;
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
                .then(literal("Simplified").then(literal("Chinese").executes(context -> setLanguage("zh_cn"))))
                .then(literal("English").executes(context -> setLanguage("en_us"))));
    }

    private int redeem(String code) {
        if (!EndGatewayFeatureAccess.matches(code)) {
            error(WaveXinI18n.tr("error.wavexin.redeem.invalid", "The redemption code is invalid."));
            return 0;
        }
        if (!onRedeemed.getAsBoolean()) {
            error(WaveXinI18n.tr("error.wavexin.redeem.license_save_failed", "Could not save the local license file."));
            return 0;
        }
        info(WaveXinI18n.tr("message.wavexin.redeem.success", "Redeemed successfully. The optional scan module is now available."));
        return SINGLE_SUCCESS;
    }

    private int setLanguage(String language) {
        onLanguageChanged.accept(language);
        info(WaveXinI18n.tr("message.wavexin.language.setting_saved", "WaveXin language changed."));
        return SINGLE_SUCCESS;
    }
}
