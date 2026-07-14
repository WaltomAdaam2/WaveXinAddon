package me.waltom.wavexin;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

// Internal backend only. It is intentionally not registered in WaveXinAddon's module list.
final class CommandScannerXin extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String NUMBERS = "0123456789";
    private static final long REQUEST_TIMEOUT_MS = 3000;
    private static final int RECURSIVE_THRESHOLD = 100;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> startPrefixLength = sgGeneral.add(new IntSetting.Builder()
        .name("Start Prefix Length")
        .description("Initial prefix length for command scanning")
        .defaultValue(2)
        .min(1)
        .sliderMin(1)
        .max(8)
        .sliderMax(8)
        .build()
    );

    public final Setting<Integer> maximumPrefixLength = sgGeneral.add(new IntSetting.Builder()
        .name("Maximum Prefix Length")
        .description("Maximum recursive prefix length")
        .defaultValue(6)
        .min(1)
        .sliderMin(1)
        .max(32)
        .sliderMax(32)
        .build()
    );

    public final Setting<Integer> requestDelay = sgGeneral.add(new IntSetting.Builder()
        .name("Request Delay")
        .description("Delay between command suggestion requests in milliseconds")
        .defaultValue(500)
        .min(200)
        .sliderMin(200)
        .max(5000)
        .sliderMax(5000)
        .build()
    );

    public final Setting<Boolean> includeLetters = sgGeneral.add(new BoolSetting.Builder()
        .name("Include Letters")
        .description("Include a-z in generated prefixes")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> includeNumbers = sgGeneral.add(new BoolSetting.Builder()
        .name("Include Numbers")
        .description("Include 0-9 in generated prefixes")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> recursiveScan = sgGeneral.add(new BoolSetting.Builder()
        .name("Recursive Scan")
        .description("Scan deeper prefixes when a response may contain more command results")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> saveResults = sgGeneral.add(new BoolSetting.Builder()
        .name("Save Results")
        .description("Save discovered commands to meteor-client/command-scanner")
        .defaultValue(true)
        .build()
    );

    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Set<String> queuedPrefixes = new HashSet<>();
    private final Set<String> requestedPrefixes = new HashSet<>();
    private final TreeSet<String> commands = new TreeSet<>();

    private ScanState state = ScanState.Idle;
    private String currentPrefix = "";
    private int currentRequestId = -1;
    private int nextRequestId = 100000000;
    private long lastRequestTime;
    private long currentRequestTime;
    private long sentRequests;

    public CommandScannerXin() {
        super(WaveXinAddon.CATEGORY, "command-scanner-xin", "Scans server command suggestions without executing commands");
    }

    @Override
    public void onActivate() {
        startScan();
    }

    @Override
    public void onDeactivate() {
        cancelScan(false);
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable table = theme.table();

        WButton start = table.add(theme.button("Start")).expandX().widget();
        start.action = this::startScan;

        WButton pause = table.add(theme.button("Pause")).expandX().widget();
        pause.action = this::pauseScan;

        WButton resume = table.add(theme.button("Resume")).expandX().widget();
        resume.action = this::resumeScan;

        WButton cancel = table.add(theme.button("Cancel")).expandX().widget();
        cancel.action = () -> cancelScan(true);

        return table;
    }

    @Override
    public String getInfoString() {
        return "%s | %s | Q:%d | Sent:%d | Found:%d".formatted(
            state,
            currentPrefix.isEmpty() ? "-" : currentPrefix,
            queue.size(),
            sentRequests,
            commands.size()
        );
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (state != ScanState.Running || mc.player == null || mc.getNetworkHandler() == null) return;

        long now = System.currentTimeMillis();
        if (hasPendingRequest()) {
            if (now - currentRequestTime >= REQUEST_TIMEOUT_MS) {
                ChatUtils.warning("CommandScannerXin request timed out for /%s", currentPrefix);
                clearPendingRequest();
                lastRequestTime = now;
            }
            return;
        }

        if (now - lastRequestTime < requestDelay.get()) return;

        String prefix = pollNextPrefix();
        if (prefix == null) {
            finishScan();
            return;
        }

        sendSuggestionRequest(prefix, now);
    }

    @EventHandler
    private void onCommandSuggestions(CommandSuggestionsEvent event) {
        if ((state != ScanState.Running && state != ScanState.Paused) || !hasPendingRequest()) return;

        CommandSuggestionsS2CPacket packet = event.packet;
        if (packet.id() != currentRequestId) return;

        String prefix = currentPrefix;
        int discoveredBefore = commands.size();

        List<String> responseRoots = collectCommands(prefix, packet);
        commands.addAll(responseRoots);

        if (recursiveScan.get()) enqueueRecursivePrefixes(prefix, responseRoots, packet);

        clearPendingRequest();
        lastRequestTime = System.currentTimeMillis();

        int discovered = commands.size() - discoveredBefore;
        if (discovered > 0) {
            ChatUtils.info("CommandScannerXin found %d new commands. Total: %d", discovered, commands.size());
            if (saveResults.get()) saveResults();
        }
    }

    private void startScan() {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            ChatUtils.error("Enter a server before starting CommandScannerXin.");
            return;
        }

        queue.clear();
        queuedPrefixes.clear();
        requestedPrefixes.clear();
        commands.clear();
        clearPendingRequest();
        sentRequests = 0;
        currentPrefix = "";
        lastRequestTime = 0;

        List<Character> chars = getPrefixCharacters();
        if (chars.isEmpty()) {
            ChatUtils.error("Enable Include Letters or Include Numbers before scanning.");
            state = ScanState.Idle;
            return;
        }

        int startLength = Math.max(1, startPrefixLength.get());
        int maxLength = Math.max(startLength, maximumPrefixLength.get());
        if (maximumPrefixLength.get() < startLength) maximumPrefixLength.set(startLength);

        generatePrefixes("", startLength, chars);
        state = ScanState.Running;
        ChatUtils.info("CommandScannerXin started with %d prefixes. Max length: %d", queue.size(), maxLength);
    }

    private void pauseScan() {
        if (state != ScanState.Running) return;
        state = ScanState.Paused;
        ChatUtils.info("CommandScannerXin paused. Queue: %d, Found: %d", queue.size(), commands.size());
    }

    private void resumeScan() {
        if (state != ScanState.Paused) return;
        state = ScanState.Running;
        lastRequestTime = 0;
        ChatUtils.info("CommandScannerXin resumed.");
    }

    private void cancelScan(boolean notify) {
        if (saveResults.get() && !commands.isEmpty()) saveResults();

        queue.clear();
        queuedPrefixes.clear();
        requestedPrefixes.clear();
        clearPendingRequest();
        currentPrefix = "";
        state = ScanState.Idle;

        if (notify) ChatUtils.info("CommandScannerXin cancelled. Found: %d", commands.size());
    }

    private void finishScan() {
        state = ScanState.Done;
        clearPendingRequest();
        if (saveResults.get()) saveResults();
        ChatUtils.info("CommandScannerXin finished. Sent: %d, Found: %d", sentRequests, commands.size());
    }

    private void sendSuggestionRequest(String prefix, long now) {
        currentPrefix = prefix;
        currentRequestId = nextRequestId++;
        currentRequestTime = now;
        sentRequests++;

        mc.getNetworkHandler().sendPacket(new RequestCommandCompletionsC2SPacket(currentRequestId, "/" + prefix));
    }

    private boolean hasPendingRequest() {
        return currentRequestId != -1;
    }

    private void clearPendingRequest() {
        currentRequestId = -1;
        currentRequestTime = 0;
    }

    private String pollNextPrefix() {
        while (!queue.isEmpty()) {
            String prefix = queue.removeFirst();
            queuedPrefixes.remove(prefix);
            if (requestedPrefixes.add(prefix)) return prefix;
        }

        return null;
    }

    private void enqueuePrefix(String prefix) {
        if (prefix.length() > maximumPrefixLength.get()) return;
        if (requestedPrefixes.contains(prefix) || queuedPrefixes.contains(prefix)) return;

        queue.addLast(prefix);
        queuedPrefixes.add(prefix);
    }

    private void enqueueRecursivePrefixes(String prefix, List<String> responseRoots, CommandSuggestionsS2CPacket packet) {
        if (prefix.length() >= maximumPrefixLength.get()) return;
        if (responseRoots.isEmpty()) return;

        boolean likelyMoreResults = packet.suggestions().size() >= RECURSIVE_THRESHOLD;
        for (String command : responseRoots) {
            String normalized = command.substring(1).toLowerCase(Locale.ROOT);
            if (normalized.startsWith(prefix) && normalized.length() > prefix.length()) {
                likelyMoreResults = true;
                break;
            }
        }

        if (!likelyMoreResults) return;

        for (char c : getPrefixCharacters()) {
            enqueuePrefix(prefix + c);
        }
    }

    private List<String> collectCommands(String prefix, CommandSuggestionsS2CPacket packet) {
        if (packet.suggestions().isEmpty()) return Collections.emptyList();

        List<String> roots = new ArrayList<>();
        String partialCommand = "/" + prefix;
        Suggestions fullSuggestions = packet.getSuggestions();

        for (Suggestion suggestion : fullSuggestions.getList()) {
            String command = extractRootCommand(partialCommand, suggestion.apply(partialCommand));
            if (command != null) roots.add(command);
        }

        for (CommandSuggestionsS2CPacket.Suggestion suggestion : packet.suggestions()) {
            String command = extractRootCommand(partialCommand, applyPacketSuggestion(partialCommand, packet, suggestion.text()));
            if (command != null) roots.add(command);
        }

        return roots;
    }

    private String applyPacketSuggestion(String partialCommand, CommandSuggestionsS2CPacket packet, String text) {
        int start = Math.max(0, Math.min(packet.start(), partialCommand.length()));
        int end = Math.max(start, Math.min(start + packet.length(), partialCommand.length()));
        return partialCommand.substring(0, start) + text + partialCommand.substring(end);
    }

    private String extractRootCommand(String partialCommand, String candidate) {
        if (candidate == null || candidate.isBlank()) return null;

        String value = candidate.trim();
        if (!value.startsWith("/")) {
            value = applyRootFallback(partialCommand, value);
            if (value == null) return null;
        }

        int end = value.length();
        for (int i = 1; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                end = i;
                break;
            }
        }

        if (end <= 1) return null;

        String root = value.substring(1, end);
        if (!root.matches("[A-Za-z0-9_:\\-.]+")) return null;

        return "/" + root.toLowerCase(Locale.ROOT);
    }

    private String applyRootFallback(String partialCommand, String suggestionText) {
        if (!partialCommand.startsWith("/")) return null;
        if (suggestionText.indexOf(' ') >= 0) return null;
        if (suggestionText.startsWith("/")) return suggestionText;
        return "/" + suggestionText;
    }

    private List<Character> getPrefixCharacters() {
        List<Character> chars = new ArrayList<>(36);
        if (includeLetters.get()) {
            for (char c : LETTERS.toCharArray()) chars.add(c);
        }
        if (includeNumbers.get()) {
            for (char c : NUMBERS.toCharArray()) chars.add(c);
        }
        return chars;
    }

    private void generatePrefixes(String prefix, int targetLength, List<Character> chars) {
        if (prefix.length() == targetLength) {
            enqueuePrefix(prefix);
            return;
        }

        for (char c : chars) {
            generatePrefixes(prefix + c, targetLength, chars);
        }
    }

    private void saveResults() {
        try {
            Path directory = MeteorClient.FOLDER.toPath().resolve("command-scanner");
            Files.createDirectories(directory);

            List<String> lines = new ArrayList<>();
            lines.add("# CommandScannerXin");
            lines.add("# Scanned At: " + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()));
            lines.add("# Server: " + getServerName());
            lines.addAll(commands);

            Files.write(getOutputPath(directory), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ChatUtils.error("CommandScannerXin could not save results: %s", e.getMessage());
        }
    }

    private Path getOutputPath(Path directory) {
        return directory.resolve(sanitizeFileName(getServerName()) + ".txt");
    }

    private String getServerName() {
        ServerInfo server = mc.getCurrentServerEntry();
        if (server != null && server.address != null && !server.address.isBlank()) return server.address;
        if (mc.isInSingleplayer()) return "singleplayer";
        return "unknown-server";
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private enum ScanState {
        Idle,
        Running,
        Paused,
        Done
    }
}
