package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.WMeteorModule;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MeteorGuiTheme.class, remap = false)
public abstract class MixinMeteorGuiTheme {
    @Inject(method = "module", at = @At("HEAD"), cancellable = true)
    private void onModule(Module module, String title, CallbackInfoReturnable<WWidget> cir) {
        if (!WaveXinI18n.isWaveXin(module)) return;

        WMeteorModule widget = new WMeteorModule(module, WaveXinI18n.moduleTitle(module));
        widget.theme = (GuiTheme) (Object) this;
        cir.setReturnValue(widget);
    }
}
