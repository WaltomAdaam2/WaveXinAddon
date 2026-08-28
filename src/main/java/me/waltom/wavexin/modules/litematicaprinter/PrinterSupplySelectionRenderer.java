package me.waltom.wavexin.modules.litematicaprinter;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

public final class PrinterSupplySelectionRenderer {
    private final LitematicaPrinter printer;
    private ClientWorld lastWorld;

    public PrinterSupplySelectionRenderer(LitematicaPrinter printer) {
        this.printer = printer;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        printer.renderSupplySelection(event);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientWorld currentWorld = MinecraftClient.getInstance().world;
        if (lastWorld != null && currentWorld != lastWorld) printer.clearSessionCache();
        else if (currentWorld != null) printer.monitorSessionCache();
        lastWorld = currentWorld;
    }
}
