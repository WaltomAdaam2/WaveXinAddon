package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.systems.hud.elements.ActiveModulesHud;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ActiveModulesHud.class, remap = false)
public abstract class MixinActiveModulesHud {
    @Redirect(method = {"tick", "renderModule", "getModuleWidth"}, at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/systems/modules/Module;title:Ljava/lang/String;"))
    private String onModuleTitle(Module module) {
        return WaveXinI18n.moduleTitle(module);
    }
}
