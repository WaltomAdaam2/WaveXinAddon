package me.waltom.wavexin.modules.litematicaprinter;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

interface PrinterNavigator {
    void configure();

    void goTo(BlockPos pos, int range);

    boolean isNavigating();

    void cancel();

    void restore();

    static boolean isBaritoneInstalled() {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("baritone-meteor") && !loader.isModLoaded("baritone")) return false;

        try {
            Class.forName("baritone.api.BaritoneAPI", false, PrinterNavigator.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    static PrinterNavigator create() {
        if (!isBaritoneInstalled()) return null;

        try {
            return (PrinterNavigator) Class.forName(
                "me.waltom.wavexin.modules.litematicaprinter.BaritoneNavigator"
            ).getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Baritone API could not be initialized", e);
        }
    }
}
