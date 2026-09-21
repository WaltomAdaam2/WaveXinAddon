package me.waltom.wavexin.mixins;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Unclamped vanilla cooldown counter, including attacks made outside the addon. */
@Mixin(LivingEntity.class)
public interface MixinLivingEntityAccessor {
    @Accessor("lastAttackedTicks")
    int wavexin$getTicksSinceLastAttack();
}
