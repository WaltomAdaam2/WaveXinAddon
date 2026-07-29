package me.waltom.wavexin.modules.autologin;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinModule;
import me.waltom.wavexin.core.WaveXinDataPaths;
import me.waltom.wavexin.i18n.WaveXinI18n;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.ServerConnectBeginEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Auto-login flow adapted for WaveXinAddon from XinAutoLogin.
 *
 * Source: https://github.com/2698269088/XinAutoLogin
 * License: MIT
 */
public class AutoLogin extends WaveXinModule {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIRECTORY = WaveXinDataPaths.DIRECTORY;
    private static final Path CONFIG_PATH = CONFIG_DIRECTORY.resolve("auto-login.json");
    private static final Path KEY_PATH = CONFIG_PATH.resolveSibling("auto-login.key");
    private static final PasswordCipher PASSWORD_CIPHER = new PasswordCipher(KEY_PATH);
    private static final Set<String> XIN_SERVERS = Set.of("2b2t.xin", "d.2b2t.xin", "lt.2b2t.xin", "2b2t.org", "110.42.44.209");
    private static final String JOIN_ITEM_KEYWORDS = "compass,join,game,\u6307\u5357\u9488,\u52a0\u5165,\u6e38\u620f";
    private static final String JOIN_GUI_TITLE_KEYWORDS = "join,game,\u52a0\u5165,\u6e38\u620f";
    private static final String JOIN_BUTTON_KEYWORDS = "join,game,\u52a0\u5165,\u6e38\u620f";
    private static final int COMPASS_HOTBAR_SLOT_FALLBACK = 2;
    private static final int JOIN_GUI_SLOT_FALLBACK = 4;
    private static final int AUTO_ANSWER_DELAY_TICKS = 5;

    static {
        SettingsWidgetFactory.registerCustomFactory(ActionButtonSetting.class, theme -> (table, setting) -> {
            ActionButtonSetting buttonSetting = (ActionButtonSetting) setting;
            var button = table.add(theme.button(WaveXinI18n.tr(buttonSetting.buttonKey, buttonSetting.buttonLabel))).widget();
            button.action = buttonSetting::run;
            button.tooltip = WaveXinI18n.tr(buttonSetting.tooltipKey, setting.description);
        });
        SettingsWidgetFactory.registerCustomFactory(SavedAccountsSetting.class, theme -> (table, setting) -> {
            SavedAccountsSetting accountList = (SavedAccountsSetting) setting;
            accountList.create(table, theme);
        });
        SettingsWidgetFactory.registerCustomFactory(PasswordInputSetting.class, theme -> (table, setting) -> {
            PasswordInputSetting passwordSetting = (PasswordInputSetting) setting;
            var textBox = table.add(theme.textBox(passwordSetting.get(), WaveXinI18n.tr("placeholder.wavexin.auto_login.password", "Password"))).expandX().widget();
            textBox.action = () -> passwordSetting.set(textBox.get());
            textBox.actionOnUnfocused = () -> passwordSetting.set(textBox.get());
        });
    }

    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgAccount = settings.createGroup("Account");
    private final SettingGroup sgSavedAccounts = settings.createGroup("Saved Accounts");
    private final SettingGroup sgDelays = settings.createGroup("Delays");

    public final Setting<Boolean> onlyOnXinServers = sgGeneral.add(new BoolSetting.Builder()
        .name("Only on 2b2t.xin")
        .description("Runs AutoLogin only on supported 2b2t.xin servers")
        .defaultValue(true)
        .onChanged(value -> serverChecked = false)
        .build()
    );

    public final Setting<Boolean> autoLogin = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Login")
        .description("Automatically sends /l for the current player when login text is detected")
        .defaultValue(true)
        .build()
    );
    public final Setting<Boolean> autoAnswer = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Answer")
        .description("Automatically answers 2b2t.xin quiz questions after a fixed 5-tick delay")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> dailyFlowerCheckIn = sgGeneral.add(new BoolSetting.Builder()
        .name("Daily Flower Check-in")
        .description("Automatically sends /qiandao once per connection after joining")
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

    public final Setting<AccountType> accountType = sgAccount.add(new EnumSetting.Builder<AccountType>()
        .name("Account Type")
        .description("Microsoft accounts do not use /l. Offline accounts use the saved password")
        .defaultValue(AccountType.Microsoft)
        .onChanged(this::onAccountTypeChanged)
        .build()
    );

    public final SavedAccountsSetting savedAccounts = sgSavedAccounts.add(new SavedAccountsSetting.Builder()
        .name("")
        .description("Select an account to edit or remove")
        .accounts(this::getSavedAccounts)
        .onSelect(this::loadSavedAccount)
        .onDelete(this::deleteSavedAccount)
        .build()
    );

    public final Setting<String> accountNameInput = sgAccount.add(new StringSetting.Builder()
        .name("Account Name")
        .description("Account name to save. Leave empty to use the current player name")
        .defaultValue("")
        .build()
    );

    public final Setting<String> passwordInput = sgAccount.add(new PasswordInputSetting(
        "Password Input",
        "Temporary input for Offline Account. It is encrypted before saving",
        () -> accountType.get() == AccountType.Offline
    ));
    public final Setting<Boolean> addOrUpdateAccount = sgAccount.add(new ActionButtonSetting.Builder()
        .name("Add / Update Account")
        .description("Saves Account Name with the selected account type")
        .buttonLabel("Save")
        .action(this::saveAccountFromInput)
        .build()
    );

    public final Setting<Integer> loginDelay = delay("Login Delay", "Delay before sending /l", 500, 0, 10000);
    public final Setting<Integer> actionDelay = delay("Action Delay", "Delay between automatic login actions", 500, 0, 10000);
    public final Setting<Integer> dailyCheckInDelay = delay("Daily Check-in Delay", "Delay before /qiandao after login", 500, 0, 10000);
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

    private AutoLoginConfig config = AutoLoginConfig.load();
    private LoginState state = LoginState.IDLE;
    private long stateAt;
    private int retries;
    private boolean loginSent;
    private boolean afterLoginActionDone;
    private boolean joinDone;
    private boolean checkInSent;
    private boolean serverChecked;
    private boolean targetServer;
    private boolean refreshingAccountEditor;
    private String lastPlayerName = "";
    private final Map<String, Pattern> questions = new HashMap<>();
    private String pendingAnswer;
    private int pendingAnswerTicks;

    public AutoLogin() {
        super(WaveXinAddon.CATEGORY, "auto-login", "Auto Login");
        loadQuestions();
        syncAccountSettings();
    }

    @Override
    public void onActivate() {
        config = AutoLoginConfig.load();
        resetConnectionState(LoginState.IDLE);
        if (questions.isEmpty()) loadQuestions();
        pendingAnswer = null;
        pendingAnswerTicks = 0;
        syncAccountSettings();
        savedAccounts.refresh();
    }

    @Override
    public void onDeactivate() {
        resetConnectionState(LoginState.IDLE);
        pendingAnswer = null;
        pendingAnswerTicks = 0;
    }

    @Override
    public String getInfoString() {
        return null;
    }

    @EventHandler
    private void onConnect(ServerConnectBeginEvent event) {
        serverChecked = false;
        targetServer = false;
        resetConnectionState(LoginState.WAITING_FOR_LOGIN);
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!isActive() || !canRunOnCurrentServer()) return;
        if (state == LoginState.WAITING_FOR_JOIN_GUI && event.screen instanceof HandledScreen<?>) {
            setState(LoginState.WAITING_TO_CLICK_JOIN);
        }
    }

    @EventHandler
    private void onText(AutoLoginTextEvent event) {
        if (!isActive() || !canRunOnCurrentServer()) return;
        if (event.text == null || event.text.isBlank()) return;

        String text = event.text;
        debugKey("debug.wavexin.auto_login.text", "Text %s: %s", event.source, text);

        if (isLoginSuccessText(text)) {
            beginPostLoginFlow();
            return;
        }

        if (loginSent && (!dailyFlowerCheckIn.get() || checkInSent) && isQueuePositionText(text)) {
            beginQueueWait();
            return;
        }

        if (autoLogin.get() && !loginSent && isLoginPromptText(text)) {
            AccountRecord account = config.getAccount(getCurrentPlayerName());
            if (account != null && account.type == AccountType.Microsoft) {
                loginSent = true;
                setState(LoginState.LOGIN_SENT);
                feedbackKey("message.wavexin.auto_login.microsoft_waiting", "Saved Microsoft Account detected. Waiting for login success.");
                return;
            }

            if (account == null || !account.hasPassword()) {
                feedbackKey("message.wavexin.auto_login.offline_password_missing", "Offline password is not set for current account.");
                return;
            }
            setState(LoginState.WAITING_FOR_LOGIN);
        }
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!autoAnswer.get() || mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (event.getMessage() == null) return;

        String message = event.getMessage().getString();
        for (Map.Entry<String, Pattern> entry : questions.entrySet()) {
            String question = entry.getKey();
            int questionIndex = message.indexOf(question);
            if (questionIndex < 0) continue;

            String options = message.substring(questionIndex + question.length()).trim();
            Matcher matcher = entry.getValue().matcher(options);
            if (!matcher.find() || matcher.groupCount() < 1) continue;

            queueAnswer(matcher.group(1));
            return;
        }
    }
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!isActive()) return;

        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            resetConnectionState(LoginState.IDLE);
            return;
        }

        sendPendingAnswer();

        if (!canRunOnCurrentServer()) {
            if (state != LoginState.IDLE) resetConnectionState(LoginState.IDLE);
            return;
        }

        String playerName = getCurrentPlayerName();
        if (!playerName.equals(lastPlayerName)) {
            lastPlayerName = playerName;
            syncAccountSettings();
            resetConnectionState(LoginState.IDLE);
        }

        if (!joinDone && mc.interactionManager != null && mc.interactionManager.getCurrentGameMode() == GameMode.ADVENTURE) {
            if (state == LoginState.IDLE || state == LoginState.LOGIN_SENT || state == LoginState.LOGIN_SUCCESS) {
                beginPostLoginFlow();
            }
        }

        if (state == LoginState.WAITING_FOR_LOGIN && shouldCheckIn() && usesNativeSession()) {
            beginPostLoginFlow();
        }

        runState();
    }

    private void saveAccountFromInput() {
        String playerName = getAccountNameInput();
        String value = passwordInput.get();

        if (playerName.isEmpty()) {
            feedbackKey("message.wavexin.auto_login.account_name_required", "Enter an account name or join a server first.");
            return;
        }

        AccountRecord account = new AccountRecord();
        account.type = accountType.get();
        if (account.type == AccountType.Offline) {
            if (value == null || value.isEmpty()) {
                feedbackKey("message.wavexin.auto_login.offline_password_required", "Offline Account requires a password.");
                return;
            }

            String encryptedPassword = PASSWORD_CIPHER.encrypt(value);
            if (encryptedPassword.isEmpty()) {
                feedbackKey("message.wavexin.auto_login.password_encrypt_failed", "Password could not be encrypted.");
                return;
            }
            account.encryptedPassword = encryptedPassword;
        }

        config.accounts.put(playerName, account);
        config.save();

        passwordInput.set("");
        if (!syncAccountSettings()) refreshAccountEditor();
        feedbackKey("message.wavexin.auto_login.account_saved", "Account saved as %s.", WaveXinI18n.enumLabel(account.type, account.type.toString()));
    }

    private void deleteSavedAccount(String playerName) {
        if (playerName == null || config.accounts.remove(playerName) == null) return;

        config.save();
        if (playerName.equals(getAccountNameInput())) {
            refreshingAccountEditor = true;
            try {
                passwordInput.set("");
                accountType.set(AccountType.Microsoft);
            } finally {
                refreshingAccountEditor = false;
            }
        }
        refreshAccountEditor();
        feedbackKey("message.wavexin.auto_login.account_removed", "Saved account removed.");
    }

    private void runState() {
        if (state == LoginState.IDLE || state == LoginState.COMPLETED) return;
        if (retries > maximumRetries.get()) {
            feedbackKey("message.wavexin.auto_login.too_many_retries", "Auto Login stopped after too many retries at %s.", WaveXinI18n.enumLabelOr(state, "Unknown state"));
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
            case WAITING_FOR_QUEUE -> { }
            default -> {
            }
        }
    }

    private void runLogin() {
        if (loginSent || !elapsed(loginDelay.get())) return;

        AccountRecord account = config.getAccount(getCurrentPlayerName());
        if (account != null && account.type == AccountType.Microsoft) {
            loginSent = true;
            setState(LoginState.LOGIN_SENT);
            feedbackKey("message.wavexin.auto_login.microsoft_waiting", "Saved Microsoft Account detected. Waiting for login success.");
            return;
        }

        if (account == null || !account.hasPassword()) {
            feedbackKey("message.wavexin.auto_login.offline_password_missing", "Offline password is not set for current account.");
            setState(LoginState.IDLE);
            return;
        }

        String password = account.getPassword();
        if (password.isEmpty()) {
            feedbackKey("message.wavexin.auto_login.offline_password_read_failed", "Offline password could not be read for current account.");
            setState(LoginState.IDLE);
            return;
        }

        mc.getNetworkHandler().sendChatCommand("l " + password);
        loginSent = true;
        setState(LoginState.LOGIN_SENT);
        feedbackKey("message.wavexin.auto_login.login_command_sent", "Login command sent.");
    }

    private void runAfterLoginActionStart() {
        if (afterLoginActionDone || !elapsed(actionDelay.get())) return;
        if (mc.player == null || mc.interactionManager == null) {
            retry();
            return;
        }

        mc.player.getInventory().setSelectedSlot(2);
        setState(LoginState.WAITING_AFTER_SLOT_SELECT);
    }

    private void runAfterLoginRightClick() {
        if (!elapsed(actionDelay.get())) return;
        if (mc.player == null || mc.interactionManager == null) {
            retry();
            return;
        }

        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        afterLoginActionDone = true;
        setState(LoginState.WAITING_TO_USE_COMPASS);
    }

    private void runUseCompass() {
        if (joinDone || !elapsed(actionDelay.get())) return;
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
        if (!elapsed(actionDelay.get())) return;
        if (!(mc.currentScreen instanceof HandledScreen<?> screen) || mc.player == null || mc.interactionManager == null) {
            retry();
            return;
        }

        if (!matchesAny(screen.getTitle().getString(), JOIN_GUI_TITLE_KEYWORDS)) {
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
        if (shouldCheckIn()) {
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
        feedbackKey("message.wavexin.auto_login.daily_checkin_sent", "Daily check-in command sent.");
        continueAfterCheckIn();
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

    private void beginPostLoginFlow() {
        if (state == LoginState.WAITING_FOR_QUEUE) return;
        if (shouldCheckIn()) {
            setState(LoginState.WAITING_FOR_CHECKIN);
        } else if (!afterLoginActionDone) {
            setState(LoginState.LOGIN_SUCCESS);
        } else if (!joinDone) {
            setState(LoginState.WAITING_TO_USE_COMPASS);
        } else {
            setState(LoginState.IN_GAME);
        }
    }

    private void continueAfterCheckIn() {
        if (!afterLoginActionDone) {
            setState(LoginState.LOGIN_SUCCESS);
        } else if (!joinDone) {
            setState(LoginState.WAITING_TO_USE_COMPASS);
        } else {
            setState(LoginState.COMPLETED);
        }
    }

    private boolean shouldCheckIn() {
        return dailyFlowerCheckIn.get() && !checkInSent;
    }

    private boolean usesNativeSession() {
        AccountRecord account = config.getAccount(getCurrentPlayerName());
        return account == null || account.type == AccountType.Microsoft;
    }

    private boolean canRunOnCurrentServer() {
        if (mc.isInSingleplayer()) return false;
        if (!onlyOnXinServers.get()) return true;
        if (serverChecked) return targetServer;
        if (mc.getNetworkHandler() == null) return false;

        serverChecked = true;
        try {
            targetServer = isXinServer(mc.getNetworkHandler().getConnection().getAddress());
        } catch (Exception ignored) {
            targetServer = false;
        }
        debugKey("debug.wavexin.auto_login.server_detection", "Server detection: %s", targetServer ? WaveXinI18n.tr("debug.wavexin.auto_login.supported_server", "supported server") : WaveXinI18n.tr("debug.wavexin.auto_login.unsupported_server", "unsupported server"));
        return targetServer;
    }

    private boolean isXinServer(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inetAddress)) return false;

        String host = inetAddress.getHostString().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return XIN_SERVERS.contains(host);
    }

    private void setState(LoginState newState) {
        if (state == newState) return;
        state = newState;
        stateAt = System.currentTimeMillis();
        retries = 0;
        debugKey("debug.wavexin.auto_login.state", "State -> %s", WaveXinI18n.enumLabelOr(newState, "Unknown state"));
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
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isJoinCompass(stack)) return i;
        }

        int fallback = COMPASS_HOTBAR_SLOT_FALLBACK;
        ItemStack stack = mc.player.getInventory().getStack(fallback);
        return stack.isEmpty() ? -1 : fallback;
    }

    private boolean isJoinCompass(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isOf(Items.COMPASS)) return true;
        return matchesItem(stack, JOIN_ITEM_KEYWORDS);
    }

    private int findJoinButtonSlot(HandledScreen<?> screen) {
        List<Slot> slots = screen.getScreenHandler().slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        if (containerSlotCount == 0) containerSlotCount = slots.size();

        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack stack = slots.get(i).getStack();
            if (!stack.isEmpty() && matchesItem(stack, JOIN_BUTTON_KEYWORDS)) return i;
        }

        int fallback = JOIN_GUI_SLOT_FALLBACK;
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

    private void beginQueueWait() {
        setState(LoginState.WAITING_FOR_QUEUE);
    }

    private boolean isQueuePositionText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("position in queue")
            || lower.contains("queue position")
            || text.contains("\u6392\u961f\u4f4d\u7f6e")
            || text.contains("\u961f\u5217\u4f4d\u7f6e")
            || text.contains("\u6b63\u5728\u6392\u961f");
    }

    private boolean isLoginPromptText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return (text.contains("\u767b\u5f55") || text.contains("\u767b\u9646") || lower.contains("login")) && !isLoginSuccessText(text);
    }

    private boolean isLoginSuccessText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return text.contains("\u767b\u5f55\u6210\u529f") || text.contains("\u767b\u9646\u6210\u529f") || lower.contains("login successful") || lower.contains("logged in");
    }

    private void sendPendingAnswer() {
        if (pendingAnswer == null) return;

        if (pendingAnswerTicks > 0) {
            pendingAnswerTicks--;
            return;
        }

        String answer = pendingAnswer;
        pendingAnswer = null;
        mc.getNetworkHandler().sendChatMessage(";" + answer);

        if (chatFeedback.get()) infoKey("message.wavexin.auto_login.quiz_answered", "Answered quiz with: %s", answer);
    }

    private void queueAnswer(String answer) {
        if (pendingAnswer != null) return;

        pendingAnswer = answer;
        pendingAnswerTicks = AUTO_ANSWER_DELAY_TICKS;
    }

    private void loadQuestions() {
        questions.clear();

        try (InputStream stream = AutoLogin.class.getClassLoader().getResourceAsStream("assets/wavexin/questions.json")) {
            if (stream == null) {
                warningKey("warning.wavexin.auto_login.questions_missing", "Auto Answer questions.json was not found.");
                return;
            }

            JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                try {
                    questions.put(entry.getKey(), Pattern.compile(entry.getValue().getAsString()));
                } catch (PatternSyntaxException ignored) {
                }
            }

            if (chatFeedback.get()) infoKey("message.wavexin.auto_login.questions_loaded", "Loaded %d quiz answers.", questions.size());
        } catch (Exception e) {
            warningKey("warning.wavexin.auto_login.questions_load_failed", "Failed to load quiz answers: %s", e.getMessage());
        }
    }
    private boolean syncAccountSettings() {
        String playerName = getCurrentPlayerName();
        lastPlayerName = playerName;
        if (playerName.isEmpty()) return false;

        boolean changed = !playerName.equals(accountNameInput.get());
        AccountRecord account = config.getAccount(playerName);
        changed |= account != null && accountType.get() != account.type;

        refreshingAccountEditor = true;
        try {
            accountNameInput.set(playerName);
            if (account != null) accountType.set(account.type);
        } finally {
            refreshingAccountEditor = false;
        }

        if (changed) refreshAccountEditor();
        return changed;
    }

    private void onAccountTypeChanged(AccountType value) {
        if (passwordInput != null) passwordInput.set("");
        if (!refreshingAccountEditor) refreshAccountEditor();
    }

    private void refreshAccountEditor() {
        settings.invalidate();
    }

    private List<SavedAccountEntry> getSavedAccounts() {
        List<SavedAccountEntry> accounts = new ArrayList<>();
        for (String playerName : config.accounts.keySet()) {
            AccountRecord account = config.getAccount(playerName);
            if (account != null) accounts.add(new SavedAccountEntry(playerName, account.type));
        }
        accounts.sort((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.name, second.name));
        return accounts;
    }

    private void loadSavedAccount(String playerName) {
        if (playerName == null) return;

        AccountRecord account = config.getAccount(playerName);
        if (account == null) return;

        refreshingAccountEditor = true;
        try {
            accountNameInput.set(playerName);
            accountType.set(account.type);
            passwordInput.set("");
        } finally {
            refreshingAccountEditor = false;
        }
        refreshAccountEditor();
    }

    private String getAccountNameInput() {
        String value = accountNameInput.get();
        if (value != null && !value.isBlank()) return value.trim();
        return getCurrentPlayerName();
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

    private void feedbackKey(String key, String fallback, Object... args) {
        if (chatFeedback.get()) infoKey(key, fallback, args);
    }

    private void debugKey(String key, String fallback, Object... args) {
        if (debugMode.get()) ChatUtils.info("[AutoLogin] " + WaveXinI18n.tr(key, fallback, args));
    }

    private enum AccountType {
        Microsoft("Microsoft Account"),
        Offline("Offline Account");

        private final String title;

        AccountType(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
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
        WAITING_FOR_QUEUE,
        COMPLETED
    }

    private static class PasswordInputSetting extends Setting<String> {
        private PasswordInputSetting(String name, String description, IVisible visible) {
            super(name, description, "", null, null, visible);
        }

        @Override
        protected String parseImpl(String str) {
            return str;
        }

        @Override
        protected boolean isValueValid(String value) {
            return true;
        }

        @Override
        protected NbtCompound save(NbtCompound tag) {
            tag.putString("value", "");
            return tag;
        }

        @Override
        protected String load(NbtCompound tag) {
            set("");
            return "";
        }

        @Override
        public boolean wasChanged() {
            return false;
        }
    }
    private static class ActionButtonSetting extends Setting<Boolean> {
        private final String buttonLabel;
        private final String buttonKey;
        private final String tooltipKey;
        private final Runnable action;

        private ActionButtonSetting(String name, String description, String buttonLabel, Runnable action, Consumer<Boolean> onChanged, Consumer<Setting<Boolean>> onModuleActivated, IVisible visible) {
            super(name, description, false, onChanged, onModuleActivated, visible);
            String segment = WaveXinI18n.keySegment(name);
            this.buttonLabel = buttonLabel;
            this.buttonKey = "button.wavexin.auto_login." + segment + ".label";
            this.tooltipKey = "button.wavexin.auto_login." + segment + ".tooltip";
            this.action = action;
        }

        private void run() {
            if (action != null) action.run();
        }

        @Override
        protected Boolean parseImpl(String str) {
            return false;
        }

        @Override
        protected boolean isValueValid(Boolean value) {
            return true;
        }

        @Override
        protected NbtCompound save(NbtCompound tag) {
            tag.putBoolean("value", false);
            return tag;
        }

        @Override
        protected Boolean load(NbtCompound tag) {
            set(false);
            return false;
        }

        private static class Builder extends SettingBuilder<Builder, Boolean, ActionButtonSetting> {
            private String buttonLabel = "Run";
            private Runnable action;

            private Builder() {
                super(false);
            }

            private Builder buttonLabel(String buttonLabel) {
                this.buttonLabel = buttonLabel;
                return this;
            }

            private Builder action(Runnable action) {
                this.action = action;
                return this;
            }

            @Override
            public ActionButtonSetting build() {
                return new ActionButtonSetting(name, description, buttonLabel, action, onChanged, onModuleActivated, visible);
            }
        }
    }

    private static class SavedAccountsSetting extends Setting<Boolean> {
        private final Supplier<List<SavedAccountEntry>> accounts;
        private final Consumer<String> onSelect;
        private final Consumer<String> onDelete;
        private final List<WeakReference<WTable>> tables = new ArrayList<>();

        private SavedAccountsSetting(String name, String description, Supplier<List<SavedAccountEntry>> accounts, Consumer<String> onSelect, Consumer<String> onDelete, Consumer<Boolean> onChanged, Consumer<Setting<Boolean>> onModuleActivated, IVisible visible) {
            super(name, description, false, onChanged, onModuleActivated, visible);
            this.accounts = accounts;
            this.onSelect = onSelect;
            this.onDelete = onDelete;
        }

        private void create(WTable table, GuiTheme theme) {
            WTable accountsTable = table.add(theme.table()).expandX().widget();
            tables.add(new WeakReference<>(accountsTable));
            rebuild(accountsTable, theme);
        }

        private void refresh() {
            tables.removeIf(reference -> {
                WTable table = reference.get();
                if (table == null) return true;
                rebuild(table, table.theme);
                return false;
            });
        }

        private void rebuild(WTable table, GuiTheme theme) {
            table.clear();
            List<SavedAccountEntry> entries = accounts.get();
            if (entries.isEmpty()) {
                table.add(theme.label(WaveXinI18n.tr("status.wavexin.auto_login.no_saved_accounts", "No saved accounts"))).expandX();
                return;
            }

            for (SavedAccountEntry entry : entries) {
                var select = table.add(theme.button(entry.name)).expandX().widget();
                select.action = () -> onSelect.accept(entry.name);
                table.add(theme.label(WaveXinI18n.tr("enum.wavexin.account_type." + WaveXinI18n.keySegment(entry.type.name()), entry.type.toString()))).expandX();

                var delete = table.add(theme.minus()).widget();
                delete.action = () -> onDelete.accept(entry.name);
                delete.tooltip = WaveXinI18n.tr("tooltip.wavexin.auto_login.delete_account", "Delete");
                table.row();
            }
        }

        @Override
        protected Boolean parseImpl(String str) {
            return false;
        }

        @Override
        protected boolean isValueValid(Boolean value) {
            return true;
        }

        @Override
        protected NbtCompound save(NbtCompound tag) {
            tag.putBoolean("value", false);
            return tag;
        }

        @Override
        protected Boolean load(NbtCompound tag) {
            set(false);
            return false;
        }

        private static class Builder extends SettingBuilder<Builder, Boolean, SavedAccountsSetting> {
            private Supplier<List<SavedAccountEntry>> accounts;
            private Consumer<String> onSelect;
            private Consumer<String> onDelete;

            private Builder() {
                super(false);
            }

            private Builder accounts(Supplier<List<SavedAccountEntry>> accounts) {
                this.accounts = accounts;
                return this;
            }

            private Builder onSelect(Consumer<String> onSelect) {
                this.onSelect = onSelect;
                return this;
            }

            private Builder onDelete(Consumer<String> onDelete) {
                this.onDelete = onDelete;
                return this;
            }

            @Override
            public SavedAccountsSetting build() {
                return new SavedAccountsSetting(name, description, accounts, onSelect, onDelete, onChanged, onModuleActivated, visible);
            }
        }
    }

    private static class SavedAccountEntry {
        private final String name;
        private final AccountType type;

        private SavedAccountEntry(String name, AccountType type) {
            this.name = name;
            this.type = type;
        }
    }

    private static class AccountRecord {
        AccountType type = AccountType.Microsoft;
        String encryptedPassword = "";
        @Deprecated
        String password = "";

        boolean hasPassword() {
            return type == AccountType.Offline && !getPassword().isEmpty();
        }

        String getPassword() {
            if (type != AccountType.Offline) return "";
            return PASSWORD_CIPHER.decrypt(encryptedPassword);
        }
    }

    private static class AutoLoginConfig {
        Map<String, AccountRecord> accounts = new HashMap<>();
        @Deprecated
        Map<String, String> passwords = new HashMap<>();

        static AutoLoginConfig load() {
            try {
                if (!Files.exists(CONFIG_PATH)) return new AutoLoginConfig();
                AutoLoginConfig config = GSON.fromJson(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8), AutoLoginConfig.class);
                if (config == null) config = new AutoLoginConfig();
                if (config.accounts == null) config.accounts = new HashMap<>();
                if (config.passwords == null) config.passwords = new HashMap<>();
                if (config.migratePasswords()) config.save();
                return config;
            } catch (Exception ignored) {
                return new AutoLoginConfig();
            }
        }

        void save() {
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                AutoLoginConfig saved = new AutoLoginConfig();
                saved.accounts = accounts;
                saved.passwords = null;
                Files.writeString(CONFIG_PATH, GSON.toJson(saved), StandardCharsets.UTF_8);
            } catch (IOException e) {
                String message = WaveXinI18n.tr("error.wavexin.auto_login.config_save_failed", "Auto Login config save failed: %s", e.getMessage());
                WaveXinAddon.LOG.error("[WaveXinDebug] module=AutoLogin message={}", message, e);
                ChatUtils.error(message);
            }
        }

        AccountRecord getAccount(String playerName) {
            if (playerName == null || playerName.isEmpty()) return null;
            AccountRecord account = accounts.get(playerName);
            if (account == null) return null;
            if (account.type == null) account.type = AccountType.Microsoft;
            if (account.encryptedPassword == null) account.encryptedPassword = "";
            if (account.password == null) account.password = "";
            return account;
        }

        private boolean migratePasswords() {
            boolean changed = false;
            for (AccountRecord account : accounts.values()) {
                if (account == null || account.type != AccountType.Offline || account.password == null || account.password.isEmpty() || !account.encryptedPassword.isEmpty()) continue;

                String encryptedPassword = PASSWORD_CIPHER.encrypt(account.password);
                if (!encryptedPassword.isEmpty()) {
                    account.encryptedPassword = encryptedPassword;
                    account.password = "";
                    changed = true;
                }
            }

            for (Map.Entry<String, String> entry : passwords.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) continue;
                if (accounts.containsKey(entry.getKey())) continue;

                AccountRecord account = new AccountRecord();
                account.type = AccountType.Offline;
                account.encryptedPassword = PASSWORD_CIPHER.encrypt(entry.getValue() == null ? "" : entry.getValue());
                accounts.put(entry.getKey(), account);
                changed = true;
            }
            if (!passwords.isEmpty()) changed = true;
            passwords.clear();
            return changed;
        }
    }

    private static class PasswordCipher {
        private static final String TRANSFORMATION = "AES/GCM/NoPadding";
        private static final int KEY_LENGTH = 32;
        private static final int IV_LENGTH = 12;

        private final Path keyPath;
        private final SecureRandom random = new SecureRandom();
        private SecretKey key;

        private PasswordCipher(Path keyPath) {
            this.keyPath = keyPath;
        }

        String encrypt(String plainText) {
            if (plainText == null || plainText.isEmpty()) return "";
            try {
                byte[] iv = new byte[IV_LENGTH];
                random.nextBytes(iv);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
                byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
            } catch (IOException | GeneralSecurityException ignored) {
                return "";
            }
        }

        String decrypt(String encryptedText) {
            if (encryptedText == null || encryptedText.isEmpty()) return "";
            try {
                String[] parts = encryptedText.split(":", 2);
                if (parts.length != 2) return "";
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
                return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
            } catch (IOException | GeneralSecurityException | IllegalArgumentException ignored) {
                return "";
            }
        }

        private SecretKey getKey() throws IOException {
            if (key != null) return key;

            byte[] keyBytes;
            if (Files.exists(keyPath)) {
                keyBytes = Base64.getDecoder().decode(Files.readString(keyPath, StandardCharsets.UTF_8).trim());
            } else {
                Files.createDirectories(keyPath.getParent());
                keyBytes = new byte[KEY_LENGTH];
                random.nextBytes(keyBytes);
                Files.writeString(keyPath, Base64.getEncoder().encodeToString(keyBytes), StandardCharsets.UTF_8);
            }

            if (keyBytes.length != KEY_LENGTH) throw new IOException("Invalid Auto Login key.");
            key = new SecretKeySpec(keyBytes, "AES");
            return key;
        }
    }
}
