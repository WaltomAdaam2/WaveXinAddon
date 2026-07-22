package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.systems.hud.elements.ActiveModulesHud;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ActiveModulesHud.class, remap = false)
public abstract class MixinActiveModulesHud {
    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/systems/modules/Module;title:Ljava/lang/String;"))
    private String onSortTitle(Module module) {
        if (WaveXinI18n.isWaveXin(module)) WaveXinI18n.markUiPath("active-modules-hud-sort");
        return WaveXinI18n.moduleTitle(module);
    }

    @Redirect(method = "renderModule", at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/systems/modules/Module;title:Ljava/lang/String;"))
    private String onRenderTitle(Module module) {
        if (WaveXinI18n.isWaveXin(module)) WaveXinI18n.markUiPath("active-modules-hud-render");
        return WaveXinI18n.moduleTitle(module);
    }

    @Redirect(method = "getModuleWidth", at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/systems/modules/Module;title:Ljava/lang/String;"))
    private String onWidthTitle(Module module) {
        if (WaveXinI18n.isWaveXin(module)) WaveXinI18n.markUiPath("active-modules-hud-width");
        return WaveXinI18n.moduleTitle(module);
    }
}