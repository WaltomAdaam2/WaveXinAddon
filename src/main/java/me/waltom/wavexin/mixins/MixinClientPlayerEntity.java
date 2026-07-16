package me.waltom.wavexin.mixins;

import com.mojang.authlib.GameProfile;
import me.waltom.wavexin.MoveEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity extends AbstractClientPlayerEntity {
    public MixinClientPlayerEntity(ClientWorld world, GameProfile profile) {
        super(world, profile);
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    public void handleWaveMoveHook(MovementType movementType, Vec3d movement, CallbackInfo ci) {
        MoveEvent event = new MoveEvent(movement.x, movement.y, movement.z);
        MeteorClient.EVENT_BUS.post(event);

        boolean changed = event.getX() != movement.x || event.getY() != movement.y || event.getZ() != movement.z;
        if (event.isCancelled()) {
            ci.cancel();
        } else if (changed) {
            ci.cancel();
            super.move(movementType, new Vec3d(event.getX(), event.getY(), event.getZ()));
        }
    }
}
