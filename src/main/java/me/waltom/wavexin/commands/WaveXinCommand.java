package me.waltom.wavexin.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

import java.util.function.Consumer;

public final class WaveXinCommand extends Command {
    private final Runnable onRedeemed;
    private final Consumer<Boolean> onUpdateCheckChanged;
    private final Consumer<String> onLanguageChanged;

    public WaveXinCommand(Runnable onRedeemed, Consumer<Boolean> onUpdateCheckChanged, Consumer<String> onLanguageChanged) {
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
        if (!WaveRedeemCommand.matches(code)) {
            error(WaveXinI18n.tr("error.wavexin.waveredeem.invalid", "The redemption code is invalid."));
            return 0;
        }
        onRedeemed.run();
        info(WaveXinI18n.tr("message.wavexin.waveredeem.success", "Redeemed successfully. The optional scan module is now available."));
        return SINGLE_SUCCESS;
    }

    private int setLanguage(String language) {
        onLanguageChanged.accept(language);
        info(WaveXinI18n.tr("message.wavexin.language.setting_saved", "WaveXin language changed."));
        return SINGLE_SUCCESS;
    }
}
