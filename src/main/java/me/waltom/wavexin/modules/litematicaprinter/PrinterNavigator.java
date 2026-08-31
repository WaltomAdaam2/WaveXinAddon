package me.waltom.wavexin.modules.litematicaprinter;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

interface PrinterNavigator {
    void configure();

    void goTo(NavigationPlan plan);

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

    record NavigationPlan(Kind kind, BlockPos target, BlockPos support, int range, boolean allowSameLevel) {
        public NavigationPlan {
            target = target.toImmutable();
            support = support == null ? null : support.toImmutable();
            range = Math.max(1, range);
        }

        static NavigationPlan near(BlockPos target, int range) {
            return new NavigationPlan(Kind.NEAR, target, null, range, true);
        }

        static NavigationPlan build(BlockPos target, BlockPos support, boolean allowSameLevel) {
            return new NavigationPlan(Kind.BUILD, target, support, 1, allowSameLevel);
        }

        static NavigationPlan mine(BlockPos target) {
            return new NavigationPlan(Kind.MINE, target, null, 1, true);
        }
    }

    enum Kind {
        NEAR,
        BUILD,
        MINE
    }
}
