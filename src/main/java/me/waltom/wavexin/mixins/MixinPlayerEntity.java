package me.waltom.wavexin.mixins;

import me.waltom.wavexin.events.TravelEvent;
import me.waltom.wavexin.events.Event;
import me.waltom.wavexin.core.Wrapper;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlayerEntity.class)
public class MixinPlayerEntity implements Wrapper {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void handleWaveTravelPre(Vec3d movementInput, CallbackInfo ci) {
        PlayerEntity player = (PlayerEntity) (Object) this;
        if (player != mc.player) return;

        TravelEvent event = new TravelEvent(Event.Stage.Pre, player);
        MeteorClient.EVENT_BUS.post(event);
        if (event.isCancelled()) {
            ci.cancel();
            event = new TravelEvent(Event.Stage.Post, player);
            MeteorClient.EVENT_BUS.post(event);
        }
    }

    @Inject(method = "travel", at = @At("RETURN"))
    private void handleWaveTravelPost(Vec3d movementInput, CallbackInfo ci) {
        PlayerEntity player = (PlayerEntity) (Object) this;
        if (player != mc.player) return;

        TravelEvent event = new TravelEvent(Event.Stage.Post, player);
        MeteorClient.EVENT_BUS.post(event);
    }
}
