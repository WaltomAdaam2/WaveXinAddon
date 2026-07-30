package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.ValueComparableMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

@Mixin(value = Modules.class, remap = false)
public abstract class MixinModulesSearch {
    @Shadow public abstract Collection<Module> getAll();

    @Inject(method = "searchTitles", at = @At("HEAD"), cancellable = true)
    private void onSearchTitles(String text, CallbackInfoReturnable<Set<Module>> cir) {
        Map<Module, Integer> modules = new ValueComparableMap<>(Comparator.naturalOrder());
        for (Module module : getAll()) {
            int score = WaveXinI18n.moduleSearchScore(module, text);
            if (WaveXinI18n.isWaveXin(module)) WaveXinI18n.markUiPath("module-search-title");
            modules.put(module, score);
        }
        cir.setReturnValue(modules.keySet());
    }

    @Inject(method = "searchSettingTitles", at = @At("HEAD"), cancellable = true)
    private void onSearchSettingTitles(String text, CallbackInfoReturnable<Set<Module>> cir) {
        Map<Module, Integer> modules = new ValueComparableMap<>(Comparator.naturalOrder());
        for (Module module : getAll()) {
            int lowest = Integer.MAX_VALUE;
            for (SettingGroup group : module.settings) {
                for (Setting<?> setting : group) {
                    int score = WaveXinI18n.settingSearchScore(setting, text);
                    if (score < lowest) lowest = score;
                }
            }
            if (WaveXinI18n.isWaveXin(module)) WaveXinI18n.markUiPath("module-search-setting");
            modules.put(module, modules.getOrDefault(module, 0) + lowest);
        }
        cir.setReturnValue(modules.keySet());
    }
}
