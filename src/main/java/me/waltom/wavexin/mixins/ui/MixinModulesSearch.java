package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.ValueComparableMap;
import net.minecraft.util.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mixin(value = Modules.class, remap = false)
public abstract class MixinModulesSearch {
    @Shadow public abstract Collection<Module> getAll();

    @Inject(method = "searchTitles", at = @At("HEAD"), cancellable = true)
    private void onSearchTitles(String text, CallbackInfoReturnable<List<Pair<Module, String>>> cir) {
        Map<Pair<Module, String>, Integer> modules = new HashMap<>();

        for (Module module : getAll()) {
            String title = WaveXinI18n.isWaveXin(module) ? WaveXinI18n.moduleTitle(module) : module.title;
            int score = WaveXinI18n.moduleSearchScore(module, text);

            if (Config.get().moduleAliases.get()) {
                for (String alias : module.aliases) {
                    int aliasScore = Utils.searchLevenshteinDefault(alias, text, false);
                    if (aliasScore < score) {
                        title = WaveXinI18n.decorateModuleTitle(module, module.title + " (" + alias + ")");
                        score = aliasScore;
                    }
                }
            }

            if (WaveXinI18n.isWaveXin(module)) WaveXinI18n.markUiPath("module-search-title");
            modules.put(new Pair<>(module, title), score);
        }

        List<Pair<Module, String>> list = new ArrayList<>(modules.keySet());
        list.sort(Comparator.comparingInt(modules::get));
        cir.setReturnValue(list);
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