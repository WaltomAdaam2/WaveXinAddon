package me.waltom.wavexin.core;

import me.waltom.wavexin.WaveXinAddon;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-module diagnostic file stored below the active Meteor run directory. */
public final class WaveXinDebugLog implements AutoCloseable {
    private static final int FLUSH_BATCH = 32;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final String module;
    private BufferedWriter writer;
    private Path path;
    private int bufferedLines;
    private String previousInfo;
    private int repeatedLines;
    private long lastFlush;

    public WaveXinDebugLog(String module) { this.module = module; }

    public synchronized void open(boolean enabled, Path runDirectory) {
        if (!enabled) { close(); return; }
        if (writer != null) return;
        try {
            path = nextPath(runDirectory.resolve("meteor-client").resolve("wavexin").resolve("debug"), module, LocalDate.now());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            bufferedLines = 0;
            previousInfo = null;
            repeatedLines = 0;
            lastFlush = System.nanoTime();
        } catch (IOException error) {
            path = null;
            WaveXinAddon.LOG.warn("Could not open {} debug log.", module, error);
        }
    }

    public void info(String event, Object... fields) { write("INFO", event, fields); }
    public void warn(String event, Object... fields) { write("WARN", event, fields); }
    public void error(String event, Object... fields) { write("ERROR", event, fields); }
    public Path path() { return path; }

    private synchronized void write(String level, String event, Object... fields) {
        if (writer == null) return;
        try {
            String signature = "INFO".equals(level) ? formatLine(LocalTime.MIDNIGHT, level, module, event, fields) : null;
            if (signature != null && signature.equals(previousInfo)) {
                repeatedLines++;
                if (System.nanoTime() - lastFlush >= 1_000_000_000L) flush();
                return;
            }
            writeRepeatedSummary();
            writer.write(formatLine(LocalTime.now(), level, module, event, fields));
            writer.newLine();
            bufferedLines++;
            previousInfo = signature;
            if (!"INFO".equals(level) || bufferedLines >= FLUSH_BATCH || System.nanoTime() - lastFlush >= 1_000_000_000L) flush();
        } catch (IOException error) {
            WaveXinAddon.LOG.warn("Could not write {} debug log.", module, error);
            close();
        }
    }

    public synchronized void flush() {
        if (writer == null || bufferedLines == 0 && repeatedLines == 0) return;
        try { writeRepeatedSummary(); writer.flush(); bufferedLines = 0; lastFlush = System.nanoTime(); }
        catch (IOException error) { WaveXinAddon.LOG.warn("Could not flush {} debug log.", module, error); close(); }
    }

    @Override public synchronized void close() {
        if (writer == null) return;
        try (BufferedWriter closing = writer) { writeRepeatedSummary(); }
        catch (IOException error) { WaveXinAddon.LOG.warn("Could not close {} debug log.", module, error); }
        finally { writer = null; path = null; bufferedLines = 0; previousInfo = null; repeatedLines = 0; }
    }

    private void writeRepeatedSummary() throws IOException {
        if (repeatedLines == 0) return;
        writer.write(formatLine(LocalTime.now(), "INFO", module, "repeated_event", "suppressed", repeatedLines));
        writer.newLine();
        bufferedLines++;
        repeatedLines = 0;
    }

    public static Path nextPath(Path directory, String module, LocalDate date) throws IOException {
        Files.createDirectories(directory);
        String prefix = "[" + module + "]-" + DATE.format(date) + "-";
        Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "(\\d+)\\.log");
        int highest = 0;
        try (var files = Files.list(directory)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                Matcher match = pattern.matcher(file.getFileName().toString());
                if (match.matches()) {
                    try { highest = Math.max(highest, Integer.parseInt(match.group(1))); }
                    catch (NumberFormatException ignored) { }
                }
            }
        }
        return directory.resolve(prefix + (highest + 1) + ".log");
    }

    public static String formatLine(LocalTime time, String level, String module, String event, Object... fields) {
        StringBuilder line = new StringBuilder().append('[').append(TIME.format(time)).append("] [Client thread/")
            .append(level).append("] [WaveXin").append(clean(module)).append("]: ").append(clean(event));
        for (int i = 0; i < fields.length; i += 2) {
            line.append(' ').append(clean(fields[i]));
            if (i + 1 < fields.length) line.append('=').append(clean(fields[i + 1]));
        }
        return line.toString();
    }

    private static String clean(Object value) { return String.valueOf(value).replace('\r', ' ').replace('\n', ' '); }
}
