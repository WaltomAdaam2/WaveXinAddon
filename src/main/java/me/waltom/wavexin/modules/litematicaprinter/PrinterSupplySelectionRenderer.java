package me.waltom.wavexin.modules.litematicaprinter;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

public final class PrinterSupplySelectionRenderer {
    private final LitematicaPrinter printer;
    private ClientPlayNetworkHandler lastNetworkHandler;

    public PrinterSupplySelectionRenderer(LitematicaPrinter printer) {
        this.printer = printer;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        printer.renderSupplySelection(event);
        printer.renderNextBatch(event);
        printer.renderManualCorrections(event);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayNetworkHandler current = mc.getNetworkHandler();
        if (lastNetworkHandler != null && current != lastNetworkHandler) printer.clearSessionCache();
        else if (current != null && mc.world != null) printer.monitorSessionCache();
        lastNetworkHandler = current;
    }
}
