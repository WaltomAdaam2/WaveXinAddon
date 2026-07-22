package me.waltom.wavexin.mixins.ui;

import me.waltom.wavexin.gui.WaveXinEnumDropdown;
import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.gui.DefaultSettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultSettingsWidgetFactory.class, remap = false)
public abstract class MixinDefaultSettingsWidgetFactory {
    @Redirect(method = "group", at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/settings/SettingGroup;name:Ljava/lang/String;"))
    private String onGroupName(SettingGroup group) {
        Module module = findModule(group);
        if (WaveXinI18n.isWaveXin(module)) WaveXinI18n.markUiPath("settings-group-name");
        return WaveXinI18n.groupName(module, group);
    }

    @Redirect(method = "group", at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/settings/Setting;title:Ljava/lang/String;", ordinal = 0))
    private String onSettingFilterTitle(Setting<?> setting) {
        if (WaveXinI18n.isWaveXin(setting.module)) WaveXinI18n.markUiPath("setting-filter-title");
        return WaveXinI18n.settingFilterText(setting);
    }

    @Redirect(method = "group", at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/settings/Setting;title:Ljava/lang/String;", ordinal = 1))
    private String onSettingDisplayTitle(Setting<?> setting) {
        if (WaveXinI18n.isWaveXin(setting.module)) WaveXinI18n.markUiPath("setting-display-title");
        return WaveXinI18n.settingTitle(setting);
    }

    @Redirect(method = "group", at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/settings/Setting;description:Ljava/lang/String;"))
    private String onSettingDescription(Setting<?> setting) {
        if (WaveXinI18n.isWaveXin(setting.module)) WaveXinI18n.markUiPath("setting-description");
        return WaveXinI18n.settingDescription(setting);
    }

    @Inject(method = "enumW", at = @At("HEAD"), cancellable = true)
    private <T extends Enum<?>> void onEnumW(WTable table, EnumSetting<T> setting, CallbackInfo ci) {
        if (!WaveXinI18n.isWaveXin(setting.module)) return;
        if (!(table.theme instanceof MeteorGuiTheme)) return;
        T current = setting.get();
        if (current == null) return;

        WaveXinI18n.markUiPath("setting-enum-dropdown");
        WDropdown<T> dropdown = table.add(new WaveXinEnumDropdown<>((T[]) current.getDeclaringClass().getEnumConstants(), current, setting.module)).expandCellX().widget();
        dropdown.action = () -> setting.set(dropdown.get());

        WButton reset = table.add(table.theme.button(GuiRenderer.RESET)).widget();
        reset.action = () -> {
            setting.reset();
            dropdown.set(setting.get());
        };
        reset.tooltip = WaveXinI18n.tr("tooltip.wavexin.common.reset", "Reset");

        ci.cancel();
    }

    private static Module findModule(SettingGroup group) {
        for (Module module : Modules.get().getAll()) {
            if (!WaveXinI18n.isWaveXin(module)) continue;
            for (SettingGroup candidate : module.settings) {
                if (candidate == group) return module;
            }
        }
        return null;
    }
}