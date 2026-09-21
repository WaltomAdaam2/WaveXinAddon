package me.waltom.wavexin.mixins;

import me.waltom.wavexin.core.WaveXinSettingsAutoSaver;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MixinMinecraftClient {
    @Inject(method = "stop", at = @At("HEAD"))
    private void wavexin$saveBeforeShutdown(CallbackInfo info) {
        WaveXinSettingsAutoSaver.INSTANCE.flush();
    }
}
