package me.waltom.wavexin.mixins;

import me.waltom.wavexin.AutoLoginTextEvent;
import me.waltom.wavexin.CommandSuggestionsEvent;
import me.waltom.wavexin.ChatFilter;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class MixinClientPlayNetworkHandler {
    @Inject(method = "onCommandSuggestions", at = @At("HEAD"))
    private void onCommandSuggestions(CommandSuggestionsS2CPacket packet, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(new CommandSuggestionsEvent(packet));
    }

    @Inject(method = "onTitle", at = @At("HEAD"))
    private void onTitle(TitleS2CPacket packet, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(new AutoLoginTextEvent(packet.text().getString(), AutoLoginTextEvent.Source.Title));
    }

    @Inject(method = "onSubtitle", at = @At("HEAD"))
    private void onSubtitle(SubtitleS2CPacket packet, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(new AutoLoginTextEvent(packet.text().getString(), AutoLoginTextEvent.Source.Subtitle));
    }

    @Inject(method = "onGameMessage", at = @At("HEAD"), cancellable = true)
    private void onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        var content = packet.content();
        MeteorClient.EVENT_BUS.post(new AutoLoginTextEvent(content.getString(), AutoLoginTextEvent.Source.Chat));
        if (ChatFilter.shouldHideServerMessage(content)) ci.cancel();
    }
}
