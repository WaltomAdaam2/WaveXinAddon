package me.waltom.wavexin.modules.autologin;

public class AutoLoginTextEvent {
    public final String text;
    public final Source source;

    public AutoLoginTextEvent(String text, Source source) {
        this.text = text;
        this.source = source;
    }

    public enum Source {
        Title,
        Subtitle,
        Chat
    }
}
