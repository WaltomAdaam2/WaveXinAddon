package me.waltom.wavexin.modules.endgateway;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.Text;

/** Keeps one persistent toast while rebuilding vanilla's multiline layout on updates. */
final class ScanProgressToast implements Toast {
    private final SystemToast.Type type;
    private SystemToast content;
    private Visibility visibility = Visibility.SHOW;

    ScanProgressToast(MinecraftClient client, SystemToast.Type type, Text title, Text description) {
        this.type = type;
        setContent(client, title, description);
    }

    void setContent(MinecraftClient client, Text title, Text description) {
        // SystemToast.setContent/show flatten the description into a single OrderedText.
        content = SystemToast.create(client, type, title, description);
        visibility = Visibility.SHOW;
    }

    void hide() {
        visibility = Visibility.HIDE;
    }

    @Override
    public Visibility getVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long time) {
        // The scan lifecycle, rather than a timeout, controls visibility.
    }

    @Override
    public void draw(DrawContext context, TextRenderer textRenderer, long time) {
        content.draw(context, textRenderer, time);
    }

    @Override
    public SystemToast.Type getType() {
        return type;
    }

    @Override
    public int getWidth() {
        return content.getWidth();
    }

    @Override
    public int getHeight() {
        return content.getHeight();
    }
}
