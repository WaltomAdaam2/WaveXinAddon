package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.ModifyArgs;

@Mixin(value = MeteorGuiTheme.class, remap = false)
public abstract class MixinMeteorGuiTheme {
    @ModifyArgs(method = "module", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/themes/meteor/widgets/WMeteorModule;<init>(Lmeteordevelopment/meteorclient/systems/modules/Module;Ljava/lang/String;)V"))
    private void onModule(Args args, Module module, String title) {
        if (!WaveXinI18n.isWaveXin(module)) return;
        WaveXinI18n.markUiPath("module-card-title");
        args.set(1, WaveXinI18n.decorateModuleTitle(module, title));
    }
}