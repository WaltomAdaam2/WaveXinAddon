package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.WMeteorModule;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WMeteorModule.class, remap = false)
public abstract class MixinWMeteorModule {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(Module module, String title, CallbackInfo ci) {
        if (WaveXinI18n.isWaveXin(module)) {
            WaveXinI18n.markUiPath("module-card-tooltip");
            ((WMeteorModule) (Object) this).tooltip = WaveXinI18n.moduleDescription(module);
        }
    }
}
