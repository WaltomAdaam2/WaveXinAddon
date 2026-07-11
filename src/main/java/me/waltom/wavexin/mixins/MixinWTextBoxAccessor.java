package me.waltom.wavexin.mixins;

import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = WTextBox.class, remap = false)
public interface MixinWTextBoxAccessor {
    @Accessor("selectionStart")
    int wavexin$getSelectionStart();

    @Accessor("selectionEnd")
    int wavexin$getSelectionEnd();
}
