package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.WMeteorModule;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WMeteorModule.class, remap = false)
public abstract class MixinWMeteorModule {
    @Shadow @Final private Module module;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(Module module, CallbackInfo ci) {
        if (!WaveXinI18n.isWaveXin(module)) return;
        WaveXinI18n.markUiPath("module-card-tooltip");
        ((WMeteorModule) (Object) this).tooltip = WaveXinI18n.moduleDescription(module);
    }

    @Redirect(method = "onCalculateSize", at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/systems/modules/Module;title:Ljava/lang/String;"))
    private String onCalculateTitle(Module module) {
        if (WaveXinI18n.isWaveXin(module)) WaveXinI18n.markUiPath("module-card-title");
        return WaveXinI18n.moduleTitle(module);
    }

    @Redirect(method = "onRender", at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/systems/modules/Module;title:Ljava/lang/String;"))
    private String onRenderTitle(Module module) {
        if (WaveXinI18n.isWaveXin(module)) WaveXinI18n.markUiPath("module-card-title");
        return WaveXinI18n.moduleTitle(module);
    }
}
