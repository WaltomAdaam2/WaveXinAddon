package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.ModuleScreen;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.ModifyArgs;

@Mixin(value = ModuleScreen.class, remap = false)
public abstract class MixinModuleScreen {
    @Shadow @Final private Module module;

    @ModifyArgs(method = "<init>", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/WindowScreen;<init>(Lmeteordevelopment/meteorclient/gui/GuiTheme;Lmeteordevelopment/meteorclient/gui/widgets/WWidget;Ljava/lang/String;)V"))
    private static void onWindowInit(Args args, GuiTheme theme, Module module) {
        if (WaveXinI18n.isWaveXin(module)) args.set(2, WaveXinI18n.moduleTitle(module));
    }

    @ModifyArg(method = "initWidgets", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/GuiTheme;label(Ljava/lang/String;D)Lmeteordevelopment/meteorclient/gui/widgets/WLabel;", ordinal = 0), index = 0)
    private String onDescription(String description) {
        return WaveXinI18n.moduleDescription(module);
    }
}
