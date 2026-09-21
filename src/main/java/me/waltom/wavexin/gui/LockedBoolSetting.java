package me.waltom.wavexin.gui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.nbt.NbtCompound;
import java.util.function.BooleanSupplier;

/** A visible switch whose value cannot change while its owning module is running. */
public final class LockedBoolSetting extends Setting<Boolean> {
    static {
        SettingsWidgetFactory.registerCustomFactory(LockedBoolSetting.class, theme -> (table, setting) -> {
            LockedBoolSetting locked = (LockedBoolSetting) setting;
            var checkbox = table.add(theme.checkbox(locked.get())).expandCellX().widget();
            checkbox.action = () -> {
                locked.set(checkbox.checked);
                checkbox.checked = locked.get();
            };
            var reset = table.add(theme.button(GuiRenderer.RESET)).widget();
            reset.action = () -> {
                locked.reset();
                checkbox.checked = locked.get();
            };
            reset.tooltip = WaveXinI18n.tr("tooltip.wavexin.common.reset", "Reset");
        });
    }

    private final BooleanSupplier locked;
    private final Runnable notice;

    public LockedBoolSetting(String name, String description, BooleanSupplier locked, Runnable notice) {
        super(name, description, false, null, null, null);
        this.locked = locked;
        this.notice = notice;
    }

    private boolean canEdit() {
        return TargetCoordinateSetting.canEdit(() -> !locked.getAsBoolean(), notice);
    }

    @Override
    public boolean set(Boolean value) { return canEdit() && super.set(value); }

    @Override
    public boolean parse(String value) { return canEdit() && super.parse(value); }

    @Override
    public void reset() { if (canEdit()) super.reset(); }

    @Override
    protected Boolean parseImpl(String value) {
        if (value.equalsIgnoreCase("true") || value.equals("1")) return true;
        if (value.equalsIgnoreCase("false") || value.equals("0")) return false;
        return value.equalsIgnoreCase("toggle") ? !get() : null;
    }

    @Override
    protected boolean isValueValid(Boolean value) { return value != null; }

    @Override
    protected NbtCompound save(NbtCompound tag) {
        tag.putBoolean("value", get());
        return tag;
    }

    @Override
    protected Boolean load(NbtCompound tag) {
        set(tag.getBoolean("value"));
        return get();
    }
}
