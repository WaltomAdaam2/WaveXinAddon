package me.waltom.wavexin.mixins;

import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BlockItem.class)
public interface MixinBlockItemAccessor {
    @Invoker("getPlacementState")
    BlockState wavexin$invokeGetPlacementState(ItemPlacementContext context);
}
