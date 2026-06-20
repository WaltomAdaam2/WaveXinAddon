package me.waltom.wavexin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class AutoAnswerXin extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Map<String, Pattern> questions = new HashMap<>();
    private String pendingAnswer;
    private int pendingTicks;

    private final Setting<Integer> answerDelay = sgGeneral.add(new IntSetting.Builder()
        .name("Answer Delay")
        .description("Delay before sending the answer, in ticks")
        .defaultValue(5)
        .min(0)
        .max(60)
        .sliderMin(0)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("Chat Feedback")
        .description("Shows detected answers in chat")
        .defaultValue(true)
        .build()
    );

    public AutoAnswerXin() {
        super(WaveXinAddon.CATEGORY, "auto-answer-xin", "Automatically answers 2b2t.xin quiz questions.");
        loadQuestions();
    }

    @Override
    public void onActivate() {
        if (questions.isEmpty()) loadQuestions();
        pendingAnswer = null;
        pendingTicks = 0;
    }

    @Override
    public void onDeactivate() {
        pendingAnswer = null;
        pendingTicks = 0;
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (event.getMessage() == null) return;

        String message = event.getMessage().getString();
        if (!message.contains("丨")) return;

        String[] parts = message.split("丨");
        if (parts.length != 2) return;

        String question = parts[0].trim();
        String options = parts[1].trim();
        Pattern pattern = questions.get(question);
        if (pattern == null) return;

        Matcher matcher = pattern.matcher(options);
        if (!matcher.find() || matcher.groupCount() < 1) return;

        queueAnswer(matcher.group(1));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (pendingAnswer == null) return;
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        if (pendingTicks > 0) {
            pendingTicks--;
            return;
        }

        String answer = pendingAnswer;
        pendingAnswer = null;
        mc.getNetworkHandler().sendChatMessage(";" + answer);

        if (chatFeedback.get()) {
            info("Answered quiz with: %s", answer);
        }
    }

    private void queueAnswer(String answer) {
        pendingAnswer = answer;
        pendingTicks = Math.max(0, answerDelay.get());
        if (chatFeedback.get()) {
            info("Queued quiz answer: %s", answer);
        }
    }

    private void loadQuestions() {
        questions.clear();

        try (InputStream stream = AutoAnswerXin.class.getClassLoader().getResourceAsStream("assets/wavexin/questions.json")) {
            if (stream == null) {
                warning("AutoAnswer questions.json was not found.");
                return;
            }

            JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                try {
                    questions.put(entry.getKey(), Pattern.compile(entry.getValue().getAsString()));
                } catch (PatternSyntaxException ignored) {
                }
            }

            if (chatFeedback.get()) {
                info("Loaded %d quiz answers.", questions.size());
            }
        } catch (Exception e) {
            warning("Failed to load quiz answers: %s", e.getMessage());
        }
    }

    @Override
    public String getInfoString() {
        return Integer.toString(questions.size());
    }
}
