package me.waltom.wavexin.modules.advancedtooltip;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinModule;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Opens a local, read-only grid for container data already included in an item stack. */
public final class AdvancedTooltip extends WaveXinModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Keybind> fullPreviewKey = sgGeneral.add(new KeybindSetting.Builder().name("Full Preview Key")
        .description("Hold this key to open a read-only preview of the held container item.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_LEFT_ALT)).build());
    private final Setting<Keybind> previewKey = sgGeneral.add(new KeybindSetting.Builder().name("Preview Key")
        .description("Optional additional key required when Always On is disabled.").defaultValue(Keybind.none()).build());
    private final Setting<Keybind> lockTooltipKey = sgGeneral.add(new KeybindSetting.Builder().name("Lock Tooltip Key")
        .description("Key reserved for tooltip locking, matching the configured preview key.").defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_LEFT_ALT)).build());
    private final Setting<Boolean> alwaysOn = sgGeneral.add(new BoolSetting.Builder().name("Always On")
        .description("Allows the full preview key without an additional preview key.").defaultValue(true).build());
    private final Setting<Boolean> coloredPreview = sgGeneral.add(new BoolSetting.Builder().name("Colored Preview")
        .description("Draws the read-only preview with a highlighted border.").defaultValue(true).build());
    private final Setting<Boolean> genericContainerPreview = sgGeneral.add(new BoolSetting.Builder().name("Generic Container Preview")
        .description("Enables preview for any item stack carrying vanilla container contents.").defaultValue(true).build());
    private final Setting<Boolean> shortItemCounts = sgGeneral.add(new BoolSetting.Builder().name("Short Item Counts")
        .description("Keeps item counts compact in the read-only grid.").defaultValue(true).build());
    private final Setting<Integer> maximumRows = sgGeneral.add(new IntSetting.Builder().name("Maximum Rows")
        .description("Maximum rows drawn by the full preview.").defaultValue(9).range(1, 9).sliderRange(1, 9).build());
    private boolean fullPreviewPressed;

    public AdvancedTooltip() {
        super(WaveXinAddon.CATEGORY, "advanced-tooltip", "Displays expanded read-only previews for container items.");
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        boolean pressed = fullPreviewKey.get().isPressed();
        if (pressed && !fullPreviewPressed && (alwaysOn.get() || previewKey.get().isPressed())) openHeldPreview();
        fullPreviewPressed = pressed;
    }

    private void openHeldPreview() {
        if (mc.player == null) return;
        if (mc.currentScreen instanceof HandledScreen<?> screen) {
            ScreenHandler handler = screen.getScreenHandler();
            if (handler != mc.player.playerScreenHandler) {
                mc.setScreen(new ContainerPreviewScreen(screen.getTitle(), handler.slots.stream().map(slot -> slot.getStack().copy()).toList(), maximumRows.get(), coloredPreview.get(), shortItemCounts.get()));
            }
            return;
        }
        if (mc.currentScreen != null) return;
        ItemStack stack = mc.player.getMainHandStack();
        if (!canPreview(stack, genericContainerPreview.get())) return;
        mc.setScreen(new ContainerPreviewScreen(stack, maximumRows.get(), coloredPreview.get(), shortItemCounts.get()));
    }

    static boolean canPreview(ItemStack stack, boolean genericEnabled) {
        return genericEnabled && stack != null && !stack.isEmpty() && stack.get(DataComponentTypes.CONTAINER) != null;
    }

    static int rowsFor(int contents, int maximumRows) {
        return Math.max(1, Math.min(maximumRows, (contents + 8) / 9));
    }
}
