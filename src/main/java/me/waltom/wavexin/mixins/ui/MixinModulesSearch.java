package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.util.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Mixin(value = Modules.class, remap = false)
public abstract class MixinModulesSearch {
    @Shadow public abstract Collection<Module> getAll();

    @Inject(method = "searchTitles", at = @At("HEAD"), cancellable = true)
    private void onSearchTitles(String text, CallbackInfoReturnable<List<Pair<Module, String>>> cir) {
        List<SearchResult> results = new ArrayList<>();

        for (Module module : getAll()) {
            int score = Integer.MAX_VALUE;
            for (String candidate : WaveXinI18n.moduleSearchCandidates(module)) {
                score = Math.min(score, Utils.searchLevenshteinDefault(candidate, text, false));
            }

            if (Config.get().moduleAliases.get() && module.aliases != null) {
                for (String alias : module.aliases) {
                    score = Math.min(score, Utils.searchLevenshteinDefault(alias, text, false));
                }
            }

            results.add(new SearchResult(module, WaveXinI18n.moduleTitle(module), score));
        }

        results.sort(Comparator.comparingInt(result -> result.score));
        List<Pair<Module, String>> modules = new ArrayList<>();
        for (SearchResult result : results) modules.add(new Pair<>(result.module, result.title));
        cir.setReturnValue(modules);
    }

    @Inject(method = "searchSettingTitles", at = @At("HEAD"), cancellable = true)
    private void onSearchSettingTitles(String text, CallbackInfoReturnable<Set<Module>> cir) {
        List<ModuleScore> scores = new ArrayList<>();

        for (Module module : getAll()) {
            int lowest = Integer.MAX_VALUE;
            for (SettingGroup group : module.settings) {
                for (Setting<?> setting : group) {
                    for (String candidate : WaveXinI18n.settingSearchCandidates(setting)) {
                        lowest = Math.min(lowest, Utils.searchLevenshteinDefault(candidate, text, false));
                    }
                }
            }
            scores.add(new ModuleScore(module, lowest));
        }

        scores.sort(Comparator.comparingInt(score -> score.score));
        Set<Module> modules = new LinkedHashSet<>();
        for (ModuleScore score : scores) modules.add(score.module);
        cir.setReturnValue(modules);
    }

    private record SearchResult(Module module, String title, int score) {}
    private record ModuleScore(Module module, int score) {}
}
