package me.waltom.wavexin.gui;

import me.waltom.wavexin.i18n.WaveXinI18n;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.settings.*;
import net.minecraft.nbt.NbtCompound;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class TargetCoordinateSetting extends Setting<Integer> {
    static {
        SettingsWidgetFactory.registerCustomFactory(TargetCoordinateSetting.class, theme -> (table, setting) -> {
            TargetCoordinateSetting coordinate = (TargetCoordinateSetting) setting;
            WIntEdit edit = table.add(theme.intEdit(coordinate.get(), coordinate.min, coordinate.max, coordinate.sliderMin, coordinate.sliderMax, coordinate.noSlider)).expandX().widget();
            ((TargetCoordinateInput) edit).wavexin$setTargetCoordinateInput(true);

            edit.action = () -> {
                if (!coordinate.set(edit.get())) edit.set(coordinate.get());
            };

            var reset = table.add(theme.button(GuiRenderer.RESET)).widget();
            reset.action = () -> {
                coordinate.reset();
                edit.set(coordinate.get());
            };
            reset.tooltip = WaveXinI18n.tr("tooltip.wavexin.common.reset", "Reset");
        });
    }
    public final int min, max;
    public final int sliderMin, sliderMax;
    public final boolean noSlider;
    private BooleanSupplier editable = () -> true;
    private Runnable lockedNotice = () -> {};

    public TargetCoordinateSetting lockWhile(BooleanSupplier locked, Runnable notice) {
        editable = () -> !locked.getAsBoolean();
        lockedNotice = notice;
        return this;
    }

    private boolean canEdit() {
        return canEdit(editable, lockedNotice);
    }

    public static boolean canEdit(BooleanSupplier editable, Runnable lockedNotice) {
        if (editable.getAsBoolean()) return true;
        lockedNotice.run();
        return false;
    }

    @Override
    public boolean set(Integer value) { return canEdit() && super.set(value); }

    @Override
    public boolean parse(String value) { return canEdit() && super.parse(value); }

    @Override
    public void reset() { if (canEdit()) super.reset(); }

    private TargetCoordinateSetting(String name, String description, int defaultValue, Consumer<Integer> onChanged, Consumer<Setting<Integer>> onModuleActivated, IVisible visible, int min, int max, int sliderMin, int sliderMax, boolean noSlider) {
        super(name, description, defaultValue, onChanged, onModuleActivated, visible);

        this.min = min;
        this.max = max;
        this.sliderMin = sliderMin;
        this.sliderMax = sliderMax;
        this.noSlider = noSlider;
    }

    @Override
    protected Integer parseImpl(String str) {
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    protected boolean isValueValid(Integer value) {
        return value >= min && value <= max;
    }

    @Override
    protected NbtCompound save(NbtCompound tag) {
        tag.putInt("value", get());
        return tag;
    }

    @Override
    protected Integer load(NbtCompound tag) {
        set(tag.getInt("value", 0));
        return get();
    }

    public static class Builder extends SettingBuilder<Builder, Integer, TargetCoordinateSetting> {
        private int min = Integer.MIN_VALUE, max = Integer.MAX_VALUE;
        private int sliderMin = 0, sliderMax = 10;
        private boolean noSlider = false;

        public Builder() {
            super(0);
        }

        public Builder min(int min) {
            this.min = min;
            return this;
        }

        public Builder max(int max) {
            this.max = max;
            return this;
        }

        public Builder sliderMin(int min) {
            this.sliderMin = min;
            return this;
        }

        public Builder sliderMax(int max) {
            this.sliderMax = max;
            return this;
        }

        @Override
        public TargetCoordinateSetting build() {
            return new TargetCoordinateSetting(name, description, defaultValue, onChanged, onModuleActivated, visible, min, max, Math.max(sliderMin, min), Math.min(sliderMax, max), noSlider);
        }
    }
}
