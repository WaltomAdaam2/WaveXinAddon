package me.waltom.wavexin.modules.commandscanner;

import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;

public class CommandSuggestionsEvent {
    public final CommandSuggestionsS2CPacket packet;

    public CommandSuggestionsEvent(CommandSuggestionsS2CPacket packet) {
        this.packet = packet;
    }
}
