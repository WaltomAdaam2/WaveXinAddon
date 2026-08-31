package me.waltom.wavexin.modules.litematicaprinter;

import me.waltom.wavexin.WaveXinAddon;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

final class PrinterDebugLog implements AutoCloseable {
    private static final int FLUSH_BATCH = 32;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private BufferedWriter writer;
    private Path path;
    private boolean enabled;
    private int bufferedLines;
    private String lastInfoSignature;
    private String lastInfoEvent;
    private int repeatedInfoLines;

    synchronized void open(boolean enabled, Path runDirectory) {
        this.enabled = enabled;
        if (!enabled || writer != null) return;
        Path directory = runDirectory.resolve("meteor-client").resolve("wavexin").resolve("printer");
        try {
            path = nextPath(directory, LocalDate.now());
            writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            bufferedLines = 0;
            lastInfoSignature = null;
            lastInfoEvent = null;
            repeatedInfoLines = 0;
        } catch (IOException e) {
            path = null;
            WaveXinAddon.LOG.warn("Could not open Litematica Printer debug log.", e);
        }
    }

    void info(String event, Object... fields) {
        write("INFO", event, fields);
    }

    void warn(String event, Object... fields) {
        write("WARN", event, fields);
    }

    void error(String event, Object... fields) {
        write("ERROR", event, fields);
    }

    Path path() {
        return path;
    }

    private synchronized void write(String level, String event, Object... fields) {
        if (!enabled || writer == null) return;
        try {
            String signature = "INFO".equals(level) ? signature(event, fields) : null;
            if (signature != null && signature.equals(lastInfoSignature)) {
                repeatedInfoLines++;
                return;
            }
            writeRepeatedSummary();
            writer.write(formatLine(LocalTime.now(), level, event, fields));
            writer.newLine();
            bufferedLines++;
            lastInfoSignature = signature;
            lastInfoEvent = signature == null ? null : event;
            if (!"INFO".equals(level) || bufferedLines >= FLUSH_BATCH) flush();
        } catch (IOException e) {
            WaveXinAddon.LOG.warn("Could not write Litematica Printer debug log.", e);
            close();
        }
    }

    synchronized void flush() {
        if (writer == null || (bufferedLines == 0 && repeatedInfoLines == 0)) return;
        try {
            writeRepeatedSummary();
            writer.flush();
            bufferedLines = 0;
        } catch (IOException e) {
            WaveXinAddon.LOG.warn("Could not flush Litematica Printer debug log.", e);
            close();
        }
    }

    @Override
    public synchronized void close() {
        if (writer == null) return;
        try {
            writeRepeatedSummary();
            writer.flush();
            writer.close();
        } catch (IOException e) {
            WaveXinAddon.LOG.warn("Could not close Litematica Printer debug log.", e);
        } finally {
            writer = null;
            path = null;
            enabled = false;
            bufferedLines = 0;
            lastInfoSignature = null;
            lastInfoEvent = null;
            repeatedInfoLines = 0;
        }
    }

    private void writeRepeatedSummary() throws IOException {
        if (repeatedInfoLines == 0) return;
        writer.write(formatLine(
            LocalTime.now(), "INFO", "repeated_event", "event", lastInfoEvent, "suppressed", repeatedInfoLines
        ));
        writer.newLine();
        bufferedLines++;
        repeatedInfoLines = 0;
    }

    private static String signature(String event, Object... fields) {
        StringBuilder signature = new StringBuilder(event);
        for (int i = 0; i < fields.length; i += 2) {
            String key = clean(fields[i]);
            if (key.equals("micros")) continue;
            signature.append('\u0000').append(key);
            if (i + 1 < fields.length) signature.append('=').append(clean(fields[i + 1]));
        }
        return signature.toString();
    }

    static Path nextPath(Path directory, LocalDate date) throws IOException {
        Files.createDirectories(directory);
        String prefix = DATE.format(date);
        int highest = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, prefix + "-*.log")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String sequence = name.substring(prefix.length() + 1, name.length() - ".log".length());
                try {
                    highest = Math.max(highest, Integer.parseInt(sequence));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return directory.resolve(prefix + '-' + (highest + 1) + ".log");
    }

    static String formatLine(LocalTime time, String level, String event, Object... fields) {
        StringBuilder line = new StringBuilder()
            .append('[').append(TIME.format(time)).append("] [Client thread/")
            .append(level).append("] [WaveXinPrinter]: ").append(clean(event));
        for (int i = 0; i < fields.length; i += 2) {
            line.append(' ').append(clean(fields[i]));
            if (i + 1 < fields.length) line.append('=').append(clean(fields[i + 1]));
        }
        return line.toString();
    }

    private static String clean(Object value) {
        return String.valueOf(value).replace('\r', ' ').replace('\n', ' ');
    }
}
