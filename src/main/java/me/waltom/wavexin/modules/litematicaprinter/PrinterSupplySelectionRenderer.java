package me.waltom.wavexin.modules.litematicaprinter;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.orbit.EventHandler;

public final class PrinterSupplySelectionRenderer {
    private final LitematicaPrinter printer;

    public PrinterSupplySelectionRenderer(LitematicaPrinter printer) {
        this.printer = printer;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        printer.renderSupplySelection(event);
    }
}
