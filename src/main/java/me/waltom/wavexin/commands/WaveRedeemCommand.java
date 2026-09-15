package me.waltom.wavexin.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.waltom.wavexin.core.EndGatewayFeatureAccess;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

public final class WaveRedeemCommand extends Command {
    private final Runnable onRedeemed;

    public WaveRedeemCommand(Runnable onRedeemed) {
        super("waveredeem", "Redeems access to an optional WaveXin scan module.");
        this.onRedeemed = onRedeemed;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(argument("code", StringArgumentType.word()).executes(context -> redeem(StringArgumentType.getString(context, "code"))));
    }

    private int redeem(String code) {
        if (!matches(code)) {
            error(WaveXinI18n.tr("error.wavexin.waveredeem.invalid", "The redemption code is invalid."));
            return 0;
        }

        onRedeemed.run();
        info(WaveXinI18n.tr("message.wavexin.waveredeem.success", "Redeemed successfully. The optional scan module is now available."));
        return SINGLE_SUCCESS;
    }

    static boolean matches(String code) {
        return EndGatewayFeatureAccess.matches(code);
    }
}
