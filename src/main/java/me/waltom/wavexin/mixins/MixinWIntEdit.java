package me.waltom.wavexin.mixins;

import me.waltom.wavexin.gui.TargetCoordinateInput;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WIntEdit.class, remap = false)
public abstract class MixinWIntEdit implements TargetCoordinateInput {
    @Shadow
    private WTextBox textBox;

    @Unique
    private boolean wavexin$targetCoordinateInput;

    @Inject(method = "filter", at = @At("HEAD"), cancellable = true)
    private void onFilter(String text, char c, CallbackInfoReturnable<Boolean> cir) {
        if (!wavexin$targetCoordinateInput) return;

        MixinWTextBoxAccessor accessor = (MixinWTextBoxAccessor) textBox;
        int selectionStart = Math.min(accessor.wavexin$getSelectionStart(), accessor.wavexin$getSelectionEnd());
        int selectionEnd = Math.max(accessor.wavexin$getSelectionStart(), accessor.wavexin$getSelectionEnd());

        if (selectionStart == selectionEnd) return;

        boolean validCharacter = Character.isDigit(c) || c == '-';
        if (!validCharacter) {
            cir.setReturnValue(false);
            return;
        }

        String remainingText = text.substring(0, selectionStart) + text.substring(selectionEnd);
        String result = remainingText.substring(0, selectionStart) + c + remainingText.substring(selectionStart);

        if (result.equals("-")) {
            cir.setReturnValue(true);
            return;
        }

        try {
            Integer.parseInt(result);
            cir.setReturnValue(true);
        } catch (NumberFormatException ignored) {
            cir.setReturnValue(false);
        }
    }

    @Override
    public void wavexin$setTargetCoordinateInput(boolean targetCoordinateInput) {
        this.wavexin$targetCoordinateInput = targetCoordinateInput;
    }
}
