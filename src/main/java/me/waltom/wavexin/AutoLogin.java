package me.waltom.wavexin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.ServerConnectBeginEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Auto-login flow adapted for WaveXinAddon from XinAutoLogin.
 *
 * Source: https://github.com/2698269088/XinAutoLogin
 * License: MIT
 */
public class AutoLogin extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = MeteorClient.FOLDER.toPath().resolve("wavexin").resolve("auto-login.json");

    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgAccount = settings.createGroup("Account");
    private final SettingGroup sgDelays = settings.createGroup("Delays");
    private final SettingGroup sgAutoJoin = settings.createGroup("Auto Join");

    public final Setting<Boolean> autoLogin = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Login")
        .description("Automatically sends /l for the current player when login text is detected")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> autoJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Join")
        .description("Automatically joins the main game from the lobby")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> afterLoginAction = sgGeneral.add(new BoolSetting.Builder()
        .name("After Login Action")
        .description("After login success, switches to hotbar slot 3 and right-clicks once")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> dailyFlowerCheckIn = sgGeneral.add(new BoolSetting.Builder()
        .name("Daily Flower Check-in")
        .description("Automatically sends /qiandao once per player per local day after entering the main game")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("Chat Feedback")
        .description("Shows safe status messages")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> debugMode = sgGeneral.add(new BoolSetting.Builder()
        .name("Debug Mode")
        .description("Shows extra state messages without revealing passwords")
        .defaultValue(false)
        .build()
    );

    public final Setting<String> currentAccount = sgAccount.add(new meteordevelopment.meteorclient.settings.StringSetting.Builder()
        .name("Current Account")
        .description("Current Minecraft player name")
        .defaultValue("")
        .build()
    );

    public final Setting<Boolean> password = sgAccount.add(new BoolSetting.Builder()
        .name("Password")
        .description("Shows whether the current account has a saved password")
        .defaultValue(false)
        .build()
    );

    public final Setting<String> passwordInput = sgAccount.add(new meteordevelopment.meteorclient.settings.StringSetting.Builder()
        .name("Password Input")
        .description("Temporary input. Cleared after Add or Update Account is enabled")
        .defaultValue("")
        .build()
    );

    public final Setting<Integer> savedAccounts = sgAccount.add(new IntSetting.Builder()
        .name("Saved Accounts")
        .description("Number of locally saved account passwords")
        .defaultValue(0)
        .min(0)
        .max(999)
        .sliderMax(20)
        .build()
    );

    public final Setting<Boolean> addOrUpdateAccount = sgAccount.add(new BoolSetting.Builder()
        .name("Add or Update Account")
        .description("Saves Password Input for the current account and clears the input")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> removeCurrentAccount = sgAccount.add(new BoolSetting.Builder()
        .name("Remove Current Account")
        .description("Removes the saved password for the current account")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> loginDelay = delay("Login Delay", "Delay before sending /l", 500, 0, 10000);
    public final Setting<Integer> successfulLoginActionDelay = delay("Successful Login Action Delay", "Delay before slot 3 right-click flow", 500, 0, 10000);
    public final Setting<Integer> rightClickDelay = delay("Right Click Delay", "Delay between selecting slot 3 and right-clicking", 300, 0, 10000);
    public final Setting<Integer> openGuiDelay = delay("Open GUI Delay", "Delay before using the compass", 500, 0, 10000);
    public final Setting<Integer> guiClickDelay = delay("GUI Click Delay", "Delay before clicking the join GUI", 500, 0, 10000);
    public final Setting<Integer> dailyCheckInDelay = delay("Daily Check-in Delay", "Delay after entering main game before /qiandao", 3000, 0, 10000);
    public final Setting<Integer> retryDelay = delay("Retry Delay", "Delay before retrying a stage", 500, 100, 10000);

    public final Setting<Integer> maximumRetries = sgDelays.add(new IntSetting.Builder()
        .name("Maximum Retries")
        .description("Maximum retries per automated stage")
        .defaultValue(20)
        .min(1)
        .max(200)
        .sliderMax(50)
        .build()
    );

    public final Setting<Boolean> detectAdventureMode = sgAutoJoin.add(new BoolSetting.Builder()
        .name("Detect Adventure Mode")
        .description("Start auto join when the player enters adventure mode")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> detectCompass = sgAutoJoin.add(new BoolSetting.Builder()
        .name("Detect Compass")
        .description("Detect compass-like hotbar items before opening the join GUI")
        .defaultValue(true)
        .build()
    );

    public final Setting<String> joinItemName = sgAutoJoin.add(new meteordevelopment.meteorclient.settings.StringSetting.Builder()
        .name("Join Item Name")
        .description("Name or lore keyword for the item used to open the join GUI")
        .defaultValue("指南针,compass,加入,join,游戏,game")
        .build()
    );

    public final Setting<String> joinGuiTitle = sgAutoJoin.add(new meteordevelopment.meteorclient.settings.StringSetting.Builder()
        .name("Join GUI Title")
        .description("Title keyword for the join GUI")
        .defaultValue("加入,join,game,游戏")
        .build()
    );

    public final Setting<String> joinButtonName = sgAutoJoin.add(new meteordevelopment.meteorclient.settings.StringSetting.Builder()
        .name("Join Button Name")
        .description("Name or lore keyword for the join button")
        .defaultValue("加入,join,game,游戏")
        .build()
    );

    public final Setting<Integer> compassHotbarSlotFallback = sgAutoJoin.add(new IntSetting.Builder()
        .name("Compass Hotbar Slot Fallback")
        .description("Fallback hotbar slot, 1-9")
        .defaultValue(3)
        .min(1)
        .max(9)
        .sliderMin(1)
        .sliderMax(9)
        .build()
    );

    public final Setting<Integer> joinGuiSlotFallback = sgAutoJoin.add(new IntSetting.Builder()
        .name("Join GUI Slot Fallback")
        .description("Fallback GUI slot id")
        .defaultValue(4)
        .min(0)
        .max(53)
        .sliderMax(53)
        .build()
    );

    private AutoLoginConfig config = AutoLoginConfig.load();
    private LoginState state = LoginState.IDLE;
    private long stateAt;
    private int retries;
    private boolean loginSent;
    private boolean afterLoginActionDone;
    private boolean joinDone;
    private boolean checkInSent;
    private String lastPlayerName = "";

    public AutoLogin() {
        super(WaveXinAddon.CATEGORY, "auto-login", "Auto Login");
        syncAccountSettings();
    }

    @Override
    public void onActivate() {
        config = AutoLoginConfig.load();
        resetConnectionState(LoginState.IDLE);
        syncAccountSettings();
    }

    @Override
    public void onDeactivate() {
        resetConnectionState(LoginState.IDLE);
    }

    @Override
    public String getInfoString() {
        return state + " | " + (hasPasswordForCurrentPlayer() ? "set" : "not set");
    }

    @EventHandler
    private void onConnect(ServerConnectBeginEvent event) {
        resetConnectionState(LoginState.WAITING_FOR_LOGIN);
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!isActive() || !autoJoin.get()) return;
        if (state == LoginState.WAITING_FOR_JOIN_GUI && event.screen instanceof HandledScreen<?>) {
            setState(LoginState.WAITING_TO_CLICK_JOIN);
        }
    }

    @EventHandler
    private void onText(AutoLoginTextEvent event) {
        if (!isActive() || mc.isInSingleplayer()) return;
        if (event.text == null || event.text.isBlank()) return;

        String text = event.text;
        debug("Text %s: %s", event.source, text);

        if (isLoginSuccessText(text)) {
            if (afterLoginAction.get() && !afterLoginActionDone) setState(LoginState.LOGIN_SUCCESS);
            else if (autoJoin.get()) setState(LoginState.WAITING_TO_USE_COMPASS);
            else enterMainGame();
            return;
        }

        if (autoLogin.get() && !loginSent && isLoginPromptText(text)) {
            if (!hasPasswordForCurrentPlayer()) {
                feedback("Password is not set for current account.");
                return;
            }
            setState(LoginState.WAITING_FOR_LOGIN);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!isActive()) return;
        processAccountActions();

        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            resetConnectionState(LoginState.IDLE);
            return;
        }

        String playerName = getCurrentPlayerName();
        if (!playerName.equals(lastPlayerName)) {
            lastPlayerName = playerName;
            syncAccountSettings();
            resetConnectionState(LoginState.IDLE);
        }

        if (detectAdventureMode.get() && autoJoin.get() && !joinDone && mc.interactionManager != null && mc.interactionManager.getCurrentGameMode() == GameMode.ADVENTURE) {
            if (state == LoginState.IDLE || state == LoginState.LOGIN_SENT || state == LoginState.LOGIN_SUCCESS) {
                setState(LoginState.WAITING_TO_USE_COMPASS);
            }
        }

        runState();
    }

    private void processAccountActions() {
        if (addOrUpdateAccount.get()) {
            addOrUpdateAccount.set(false);
            savePasswordFromInput();
        }

        if (removeCurrentAccount.get()) {
            removeCurrentAccount.set(false);
            removeSavedPassword();
        }
    }

    private void savePasswordFromInput() {
        String playerName = getCurrentPlayerName();
        String value = passwordInput.get();

        if (playerName.isEmpty()) {
            feedback("Join a server before setting a password.");
            return;
        }

        if (value == null || value.isEmpty()) {
            feedback("Password Input is empty.");
            return;
        }

        config.passwords.put(playerName, value);
        config.save();
        passwordInput.set("");
        syncAccountSettings();
        feedback("Password saved for current account.");
    }

    private void removeSavedPassword() {
        String playerName = getCurrentPlayerName();
        if (!playerName.isEmpty()) {
            config.passwords.remove(playerName);
            config.save();
        }

        passwordInput.set("");
        syncAccountSettings();
        feedback("Password removed for current account.");
    }

    private void runState() {
        if (state == LoginState.IDLE || state == LoginState.COMPLETED) return;
        if (retries > maximumRetries.get()) {
            feedback("Auto Login stopped after too many retries at %s.", state);
            setState(LoginState.IDLE);
            return;
        }

        switch (state) {
            case WAITING_FOR_LOGIN -> runLogin();
            case LOGIN_SUCCESS -> runAfterLoginActionStart();
            case WAITING_AFTER_SLOT_SELECT -> runAfterLoginRightClick();
            case WAITING_TO_USE_COMPASS -> runUseCompass();
            case WAITING_FOR_JOIN_GUI -> retryAfterDelay();
            case WAITING_TO_CLICK_JOIN -> runClickJoinGui();
            case JOIN_CLICKED -> runJoinClicked();
            case IN_GAME -> runInGame();
            case WAITING_FOR_CHECKIN -> runCheckIn();
            default -> {
            }
        }
    }

    private void runLogin() {
        if (loginSent || !elapsed(loginDelay.get())) return;
        String password = config.getPassword(getCurrentPlayerName());
        if (password == null || password.isEmpty()) {
            feedback("Password is not set for current account.");
            setState(LoginState.IDLE);
            return;
        }

        mc.getNetworkHandler().sendChatCommand("l " + password);
        loginSent = true;
        setState(LoginState.LOGIN_SENT);
        feedback("Login command sent.");
    }

    private void runAfterLoginActionStart() {
        if (afterLoginActionDone || !elapsed(successfulLoginActionDelay.get())) return;
        if (mc.player == null || mc.interactionManager == null) {
            retry();
            return;
        }

        mc.player.getInventory().setSelectedSlot(2);
        setState(LoginState.WAITING_AFTER_SLOT_SELECT);
    }

    private void runAfterLoginRightClick() {
        if (!elapsed(rightClickDelay.get())) return;
        if (mc.player == null || mc.interactionManager == null) {
            retry();
            return;
        }

        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        afterLoginActionDone = true;
        if (autoJoin.get()) setState(LoginState.WAITING_TO_USE_COMPASS);
        else enterMainGame();
    }

    private void runUseCompass() {
        if (joinDone || !elapsed(openGuiDelay.get())) return;
        if (mc.player == null || mc.interactionManager == null) {
            retry();
            return;
        }

        int hotbarSlot = findJoinHotbarSlot();
        if (hotbarSlot < 0) {
            retry();
            return;
        }

        mc.player.getInventory().setSelectedSlot(hotbarSlot);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        setState(LoginState.WAITING_FOR_JOIN_GUI);
    }

    private void runClickJoinGui() {
        if (!elapsed(guiClickDelay.get())) return;
        if (!(mc.currentScreen instanceof HandledScreen<?> screen) || mc.player == null || mc.interactionManager == null) {
            retry();
            return;
        }

        if (!matchesAny(screen.getTitle().getString(), joinGuiTitle.get())) {
            retry();
            return;
        }

        int slotId = findJoinButtonSlot(screen);
        if (slotId < 0) {
            retry();
            return;
        }

        mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, slotId, 0, SlotActionType.PICKUP, mc.player);
        joinDone = true;
        setState(LoginState.JOIN_CLICKED);
    }

    private void runJoinClicked() {
        if (!elapsed(retryDelay.get())) return;
        if (mc.currentScreen == null || (mc.interactionManager != null && mc.interactionManager.getCurrentGameMode() != GameMode.ADVENTURE)) {
            enterMainGame();
        } else {
            retry();
        }
    }

    private void runInGame() {
        if (dailyFlowerCheckIn.get() && !checkInSent && !config.hasCheckedInToday(getCurrentPlayerName())) {
            setState(LoginState.WAITING_FOR_CHECKIN);
        } else {
            setState(LoginState.COMPLETED);
        }
    }

    private void runCheckIn() {
        if (!elapsed(dailyCheckInDelay.get())) return;
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null || mc.isInSingleplayer()) {
            retry();
            return;
        }

        mc.getNetworkHandler().sendChatCommand("qiandao");
        checkInSent = true;
        config.lastCheckIn.put(getCurrentPlayerName(), LocalDate.now().toString());
        config.save();
        setState(LoginState.COMPLETED);
        feedback("Daily check-in command sent.");
    }

    private void retryAfterDelay() {
        if (elapsed(retryDelay.get())) retry();
    }

    private void retry() {
        retries++;
        stateAt = System.currentTimeMillis();
    }

    private boolean elapsed(int millis) {
        return System.currentTimeMillis() - stateAt >= millis;
    }

    private void enterMainGame() {
        setState(LoginState.IN_GAME);
    }

    private void setState(LoginState newState) {
        if (state == newState) return;
        state = newState;
        stateAt = System.currentTimeMillis();
        retries = 0;
        debug("State -> %s", newState);
    }

    private void resetConnectionState(LoginState newState) {
        state = newState;
        stateAt = System.currentTimeMillis();
        retries = 0;
        loginSent = false;
        afterLoginActionDone = false;
        joinDone = false;
        checkInSent = false;
        syncAccountSettings();
    }

    private int findJoinHotbarSlot() {
        if (detectCompass.get()) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (isJoinCompass(stack)) return i;
            }
        }

        int fallback = compassHotbarSlotFallback.get() - 1;
        ItemStack stack = mc.player.getInventory().getStack(fallback);
        return stack.isEmpty() ? -1 : fallback;
    }

    private boolean isJoinCompass(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isOf(Items.COMPASS)) return true;
        return matchesItem(stack, joinItemName.get());
    }

    private int findJoinButtonSlot(HandledScreen<?> screen) {
        List<Slot> slots = screen.getScreenHandler().slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        if (containerSlotCount == 0) containerSlotCount = slots.size();

        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack stack = slots.get(i).getStack();
            if (!stack.isEmpty() && matchesItem(stack, joinButtonName.get())) return i;
        }

        int fallback = joinGuiSlotFallback.get();
        if (fallback >= 0 && fallback < slots.size() && !slots.get(fallback).getStack().isEmpty()) return fallback;
        return -1;
    }

    private boolean matchesItem(ItemStack stack, String keywords) {
        StringBuilder text = new StringBuilder(stack.getName().getString());
        try {
            for (Text line : stack.getTooltip(net.minecraft.item.Item.TooltipContext.DEFAULT, mc.player, net.minecraft.item.tooltip.TooltipType.BASIC)) {
                text.append(' ').append(line.getString());
            }
        } catch (Exception ignored) {
        }
        return matchesAny(text.toString(), keywords);
    }

    private boolean matchesAny(String value, String keywords) {
        if (value == null || keywords == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String keyword : keywords.split(",")) {
            String trimmed = keyword.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && lower.contains(trimmed)) return true;
        }
        return false;
    }

    private boolean isLoginPromptText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return (text.contains("登录") || text.contains("登陆") || lower.contains("login")) && !isLoginSuccessText(text);
    }

    private boolean isLoginSuccessText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return text.contains("登录成功") || text.contains("登陆成功") || lower.contains("login successful") || lower.contains("logged in");
    }

    private boolean hasPasswordForCurrentPlayer() {
        return config.hasPassword(getCurrentPlayerName());
    }

    private void syncAccountSettings() {
        String playerName = getCurrentPlayerName();
        currentAccount.set(playerName.isEmpty() ? "unknown" : playerName);
        password.set(config.hasPassword(playerName));
        savedAccounts.set(config.passwords.size());
        lastPlayerName = playerName;
    }

    private static String getCurrentPlayerName() {
        if (mc.player == null) return "";
        return mc.player.getName().getString();
    }

    private Setting<Integer> delay(String name, String description, int defaultValue, int min, int max) {
        return sgDelays.add(new IntSetting.Builder()
            .name(name)
            .description(description)
            .defaultValue(defaultValue)
            .min(min)
            .max(max)
            .sliderMin(min)
            .sliderMax(max)
            .build()
        );
    }

    private void feedback(String message, Object... args) {
        if (chatFeedback.get()) ChatUtils.info(message, args);
    }

    private void debug(String message, Object... args) {
        if (debugMode.get()) ChatUtils.info("[AutoLogin] " + message, args);
    }

    private enum LoginState {
        IDLE,
        WAITING_FOR_LOGIN,
        LOGIN_SENT,
        LOGIN_SUCCESS,
        WAITING_AFTER_SLOT_SELECT,
        WAITING_TO_USE_COMPASS,
        WAITING_FOR_JOIN_GUI,
        WAITING_TO_CLICK_JOIN,
        JOIN_CLICKED,
        IN_GAME,
        WAITING_FOR_CHECKIN,
        COMPLETED
    }

    private static class AutoLoginConfig {
        Map<String, String> passwords = new HashMap<>();
        Map<String, String> lastCheckIn = new HashMap<>();

        static AutoLoginConfig load() {
            try {
                if (!Files.exists(CONFIG_PATH)) return new AutoLoginConfig();
                AutoLoginConfig config = GSON.fromJson(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8), AutoLoginConfig.class);
                if (config == null) config = new AutoLoginConfig();
                if (config.passwords == null) config.passwords = new HashMap<>();
                if (config.lastCheckIn == null) config.lastCheckIn = new HashMap<>();
                return config;
            } catch (Exception ignored) {
                return new AutoLoginConfig();
            }
        }

        void save() {
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(CONFIG_PATH, GSON.toJson(this), StandardCharsets.UTF_8);
            } catch (IOException e) {
                ChatUtils.error("Auto Login config save failed: %s", e.getMessage());
            }
        }

        String getPassword(String playerName) {
            if (playerName == null || playerName.isEmpty()) return "";
            return passwords.getOrDefault(playerName, "");
        }

        boolean hasPassword(String playerName) {
            return !getPassword(playerName).isEmpty();
        }

        boolean hasCheckedInToday(String playerName) {
            if (playerName == null || playerName.isEmpty()) return false;
            return LocalDate.now().toString().equals(lastCheckIn.get(playerName));
        }
    }
}
