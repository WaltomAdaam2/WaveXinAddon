package me.waltom.wavexin.modules.advancedtooltip;

import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/** Client-only screen: it never has a ScreenHandler and cannot send inventory clicks. */
final class ContainerPreviewScreen extends Screen {
    private static final int SLOT = 18;
    private final List<ItemStack> contents;
    private final int rows;
    private final boolean colored;
    private final boolean shortCounts;

    ContainerPreviewScreen(ItemStack source, int maximumRows, boolean colored, boolean shortCounts) {
        this(source.getName(), contents(source), maximumRows, colored, shortCounts);
    }

    ContainerPreviewScreen(Text title, List<ItemStack> contents, int maximumRows, boolean colored, boolean shortCounts) {
        super(title);
        this.contents = contents.stream().map(ItemStack::copy).toList();
        this.rows = AdvancedTooltip.rowsFor(contents.size(), maximumRows);
        this.colored = colored;
        this.shortCounts = shortCounts;
    }

    private static List<ItemStack> contents(ItemStack source) {
        ContainerComponent component = source.get(DataComponentTypes.CONTAINER);
        return component == null ? List.of() : component.stream().map(ItemStack::copy).toList();
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderInGameBackground(context);
        int width = 9 * SLOT + 16, height = rows * SLOT + 30;
        int left = (this.width - width) / 2, top = (this.height - height) / 2;
        context.fill(left, top, left + width, top + height, 0xE0101010);
        context.drawStrokedRectangle(left, top, width, height, colored ? 0xFFFFA000 : 0xFF808080);
        context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, top + 8, 0xFFFFFF);
        for (int i = 0; i < Math.min(contents.size(), rows * 9); i++) {
            int x = left + 8 + i % 9 * SLOT, y = top + 24 + i / 9 * SLOT;
            ItemStack stack = contents.get(i);
            context.drawItem(stack, x, y);
            context.drawStackOverlay(textRenderer, stack, x, y, shortCounts && stack.getCount() >= 1000 ? "%dk".formatted(stack.getCount() / 1000) : null);
            if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) context.drawItemTooltip(textRenderer, stack, mouseX, mouseY);
        }
        super.render(context, mouseX, mouseY, delta);
    }
}
