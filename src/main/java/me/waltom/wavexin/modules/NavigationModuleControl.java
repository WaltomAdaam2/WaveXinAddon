package me.waltom.wavexin.modules;

import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.i18n.WaveXinI18n;
import me.waltom.wavexin.modules.basefinder.BaseFinder;
import me.waltom.wavexin.modules.elytraflypath.ElytraFlyPath;
import me.waltom.wavexin.modules.endgateway.EndGatewayFinder;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import java.util.Arrays;
import java.util.function.Predicate;

public final class NavigationModuleControl {
    private NavigationModuleControl() {
    }

    public static boolean reportConflictingActivation(WaveXinModule module) {
        Module conflict = firstActiveOther(module, Arrays.asList(
            Modules.get().get(BaseFinder.class),
            Modules.get().get(ElytraFlyPath.class),
            Modules.get().get(EndGatewayFinder.class)
        ), candidate -> candidate != null && candidate.isActive());
        if (conflict == null) return false;

        module.info(WaveXinI18n.tr(
            "message.wavexin.navigation_module_conflict",
            "Cannot enable %s while %s is active.",
            WaveXinI18n.moduleTitle(module),
            WaveXinI18n.moduleTitle(conflict)
        ));
        return true;
    }

    public static void suppressMovementInput() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        if (client.player != null && client.player.input != null) client.player.input.tick(false, 1.0F);
    }

    public static void restoreMovementInput() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options == null) return;
        KeyBinding.updatePressedStates();
        if (client.player != null && client.player.input != null) client.player.input.tick(false, 1.0F);
    }

    static <T> T firstActiveOther(T current, Iterable<T> candidates, Predicate<T> active) {
        for (T candidate : candidates) {
            if (candidate != null && candidate != current && active.test(candidate)) return candidate;
        }
        return null;
    }
}
