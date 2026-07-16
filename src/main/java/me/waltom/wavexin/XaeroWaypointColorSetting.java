package me.waltom.wavexin;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorWidget;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class XaeroWaypointColorSetting extends Setting<BaseFinder.XaeroWaypointColor> {
    private final BaseFinder.XaeroWaypointColor[] values;
    private final List<String> suggestions;

    static {
        SettingsWidgetFactory.registerCustomFactory(XaeroWaypointColorSetting.class, theme -> (table, setting) -> {
            XaeroWaypointColorSetting colorSetting = (XaeroWaypointColorSetting) setting;
            WColorDropdown dropdown = table.add(new WColorDropdown(colorSetting.values, colorSetting.get())).expandCellX().widget();
            dropdown.action = () -> colorSetting.set(dropdown.get());

            WButton reset = table.add(theme.button(GuiRenderer.RESET)).widget();
            reset.action = () -> {
                colorSetting.reset();
                dropdown.set(colorSetting.get());
            };
            reset.tooltip = "Reset";
        });
    }

    public XaeroWaypointColorSetting(String name, String description, BaseFinder.XaeroWaypointColor defaultValue, Consumer<BaseFinder.XaeroWaypointColor> onChanged, Consumer<Setting<BaseFinder.XaeroWaypointColor>> onModuleActivated, IVisible visible) {
        super(name, description, defaultValue, onChanged, onModuleActivated, visible);

        values = BaseFinder.XaeroWaypointColor.values();
        suggestions = new ArrayList<>(values.length);
        for (BaseFinder.XaeroWaypointColor value : values) suggestions.add(value.toString());
    }

    @Override
    protected BaseFinder.XaeroWaypointColor parseImpl(String str) {
        for (BaseFinder.XaeroWaypointColor value : values) {
            if (str.equalsIgnoreCase(value.toString())) return value;
        }
        return null;
    }

    @Override
    protected boolean isValueValid(BaseFinder.XaeroWaypointColor value) {
        return value != null;
    }

    @Override
    public List<String> getSuggestions() {
        return suggestions;
    }

    @Override
    protected NbtCompound save(NbtCompound tag) {
        tag.putString("value", get().toString());
        return tag;
    }

    @Override
    protected BaseFinder.XaeroWaypointColor load(NbtCompound tag) {
        parse(tag.getString("value", ""));
        return get();
    }

    public static class Builder extends SettingBuilder<Builder, BaseFinder.XaeroWaypointColor, XaeroWaypointColorSetting> {
        public Builder() {
            super(null);
        }

        @Override
        public XaeroWaypointColorSetting build() {
            return new XaeroWaypointColorSetting(name, description, defaultValue, onChanged, onModuleActivated, visible);
        }
    }

    private static class WColorDropdown extends WDropdown<BaseFinder.XaeroWaypointColor> implements MeteorWidget {
        public WColorDropdown(BaseFinder.XaeroWaypointColor[] values, BaseFinder.XaeroWaypointColor value) {
            super(values, value);
        }

        @Override
        protected WDropdownRoot createRootWidget() {
            return new WRoot();
        }

        @Override
        protected WDropdownValue createValueWidget() {
            return new WValue();
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            MeteorGuiTheme theme = theme();
            double pad = pad();
            double size = theme.textHeight();

            renderBackground(renderer, this, pressed, mouseOver);

            String text = get().toString();
            double width = theme.textWidth(text);
            renderer.text(text, x + pad + maxValueWidth / 2 - width / 2, y + pad, get().displayColor(), false);
            renderer.rotatedQuad(x + pad + maxValueWidth + pad, y + pad, size, size, 0, GuiRenderer.TRIANGLE, theme.textColor.get());
        }

        private static class WRoot extends WDropdownRoot implements MeteorWidget {
            @Override
            protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
                MeteorGuiTheme theme = theme();
                double size = theme.scale(2);
                Color color = theme.outlineColor.get();

                renderer.quad(x, y + height - size, width, size, color);
                renderer.quad(x, y, size, height - size, color);
                renderer.quad(x + width - size, y, size, height - size, color);
            }
        }

        private class WValue extends WDropdownValue implements MeteorWidget {
            @Override
            protected void onCalculateSize() {
                double pad = pad();
                width = pad + theme.textWidth(value.toString()) + pad;
                height = pad + theme.textHeight() + pad;
            }

            @Override
            protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
                MeteorGuiTheme theme = theme();
                Color background = theme.backgroundColor.get(pressed, mouseOver, true);
                int alpha = background.a;
                background.a += background.a / 2;
                background.validate();
                renderer.quad(this, background);
                background.a = alpha;

                String text = value.toString();
                renderer.text(text, x + width / 2 - theme.textWidth(text) / 2, y + pad(), value.displayColor(), false);
            }
        }
    }
}