package local.station.voicebridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int PURPLE = Color.rgb(112, 75, 196);
    private LinearLayout body;
    private TextView status, runtimeStatus;
    private EditText giga, model, speechify, voice;
    private Spinner voiceGain;
    private final Handler main = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override public void run() { showResult(); main.postDelayed(this, 1500); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(247, 245, 252));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(44), dp(28), dp(44), dp(40));
        scroll.addView(body);
        setContentView(scroll);
        label("Station Voice Lab", 30, PURPLE);
        label("Голосовой режим Станции", 18, Color.DKGRAY);
        label("Скажите «Алиса» и задайте вопрос. Обработка работает на Станции; ПК предоставляет сетевой туннель к внешним API.", 15, Color.DKGRAY);
        runtimeStatus = label("Проверяю голосовой режим…", 18, PURPLE);
        Button enable = button("Включить голосовой режим");
        enable.setOnClickListener(view -> setRuntime(true));
        Button disable = button("Выключить голосовой режим");
        disable.setOnClickListener(view -> setRuntime(false));
        Button tvControl = button("Настроить голосовое управление видео");
        tvControl.setOnClickListener(view -> openAccessibilitySettings());
        label("Громкость ответов Алисы", 16, Color.DKGRAY);
        voiceGain = new Spinner(this);
        String[] gainOptions = new String[SpeechifyClient.MAX_VOICE_GAIN_DB - SpeechifyClient.MIN_VOICE_GAIN_DB + 1];
        for (int i = 0; i < gainOptions.length; i++) {
            int gain = i + SpeechifyClient.MIN_VOICE_GAIN_DB;
            gainOptions[i] = gain == 0 ? "Без коррекции" : (gain > 0 ? "+" : "") + gain + " дБ";
        }
        ArrayAdapter<String> levels = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, gainOptions);
        levels.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceGain.setAdapter(levels);
        voiceGain.setContentDescription("Громкость ответов Алисы");
        body.addView(voiceGain, new LinearLayout.LayoutParams(-1, dp(48)));
        voiceGain.setSelection(SpeechifyClient.DEFAULT_VOICE_GAIN_DB - SpeechifyClient.MIN_VOICE_GAIN_DB);
        TextView volumeStatus = label("", 14, Color.DKGRAY);
        button("Сохранить громкость ответов").setOnClickListener(view -> saveVoiceGain(volumeStatus));
        label("Проверки API", 22, PURPLE);
        status = label("Ожидает проверки", 17, PURPLE);
        Button probe = button("Проверить доступ к GigaChat");
        probe.setOnClickListener(view -> test("probe"));
        Button ask = button("Пробный текстовый вопрос");
        ask.setOnClickListener(view -> test("ask"));
        Button audio = button("Проверить распознавание GigaChat Max");
        audio.setOnClickListener(view -> test("audio_probe"));
        Button speech = button("Создать пробную озвучку Speechify · без воспроизведения");
        speech.setOnClickListener(view -> test("speechify_probe"));
        Button pipeline = button("Проверить полный диалог · искусственная запись, без воспроизведения");
        pipeline.setOnClickListener(view -> test("pipeline_probe"));
        Button tts = button("Проверить локальное озвучивание");
        tts.setOnClickListener(view -> test("tts_info"));
        label("Настройки API", 22, PURPLE);
        giga = field("Ключ авторизации GigaChat · оставьте пустым, чтобы сохранить текущий", true);
        model = field("Модель GigaChat", false);
        speechify = field("API-ключ Speechify · пустое поле сохраняет текущий", true);
        voice = field("Голос Speechify · vasilisa, tikhon или savva", false);
        try {
            JSONObject config = PrivateConfig.read(this, "config.json");
            model.setText(config.optString("gigachat_model", "GigaChat-2"));
            voice.setText(config.optString("speechify_voice_id", "vasilisa"));
            voiceGain.setSelection(SpeechifyClient.voiceGainDb(config) - SpeechifyClient.MIN_VOICE_GAIN_DB);
        } catch (Exception ignored) { model.setText("GigaChat-2"); }
        Button save = button("Сохранить настройки");
        save.setOnClickListener(view -> save());
    }
    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException unavailable) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void saveVoiceGain(TextView feedback) {
        try {
            JSONObject config = PrivateConfig.read(this, "config.json");
            config.put(SpeechifyClient.VOICE_GAIN_KEY, voiceGain.getSelectedItemPosition() + SpeechifyClient.MIN_VOICE_GAIN_DB);
            PrivateConfig.write(this, "config.json", config);
            feedback.setText("Громкость ответов сохранена");
        } catch (Exception unavailable) {
            feedback.setText("Не удалось сохранить громкость ответов");
        }
    }
    private void test(String operation) {
        if (RuntimeSettings.enabled(this)) {
            status.setText("Для отдельной проверки сначала выключите голосовой режим.");
            return;
        }
        status.setText("Выполняется проверка…");
        startForegroundService(new Intent(this, ProbeService.class).putExtra("operation", operation));
    }
    private void setRuntime(boolean enabled) {
        if (!RuntimeSettings.setEnabled(this, enabled)) {
            runtimeStatus.setText("Не удалось сохранить выбор"); return;
        }
        startForegroundService(new Intent(this, VoiceRuntimeService.class));
        runtimeStatus.setText(enabled ? "Запускаю голосовой режим…" : "Выключаю голосовой режим…");
    }
    private void save() {
        try {
            JSONObject config = PrivateConfig.read(this, "config.json");
            preserveKey(config, "gigachat_authorization_key", giga);
            preserveKey(config, "speechify_api_key", speechify);
            config.put("gigachat_model", model.getText().toString().trim());
            config.put("speechify_voice_id", voice.getText().toString().trim());
            PrivateConfig.write(this, "config.json", config);
            status.setText("Настройки сохранены в закрытом хранилище приложения");
        } catch (Exception ignored) { status.setText("Не удалось сохранить настройки"); }
    }
    private static void preserveKey(JSONObject config, String key, EditText field) throws Exception {
        String value = field.getText().toString().trim();
        if (!value.isEmpty()) config.put(key, value);
        field.setText("");
    }
    private void showResult() {
        try {
            JSONObject runtime = PrivateConfig.read(this, "runtime-status.json");
            if (!RuntimeSettings.enabled(this)) runtimeStatus.setText("Голосовой режим выключен");
            else if (System.currentTimeMillis() - runtime.optLong("updatedAtMs") > 10000)
                runtimeStatus.setText("Голосовой режим восстанавливается…");
            else if (!runtime.optBoolean("credentialsConfigured")) runtimeStatus.setText("Нужны ключи GigaChat и Speechify");
            else if (runtime.optBoolean("circuitOpen")) runtimeStatus.setText("Облачный API временно недоступен · повтор позже");
            else if (runtime.optInt("activeWorkers") > 0) runtimeStatus.setText("Обрабатываю вопрос…");
            else if (runtime.optBoolean("listenerReady")) runtimeStatus.setText("Голосовой режим готов · диалогов: " + runtime.optLong("completed"));
            else runtimeStatus.setText("Запускаю голосовой режим…");
        } catch (Exception ignored) { }
        try {
            JSONObject result = PrivateConfig.read(this, "result.json");
            if (result.length() == 0) return;
            if ("tts_info".equals(result.optString("operation"))) {
                status.setText(result.optBoolean("ok") ? "TTS: " + result.optJSONArray("engines")
                        : "Локальный движок озвучивания недоступен");
            } else {
                JSONObject response = result.optJSONObject("result");
                String provider = "pipeline_probe".equals(result.optString("operation")) ? "ASR → GigaChat → Speechify"
                        : "speechify_probe".equals(result.optString("operation")) ? "Speechify" : "GigaChat";
                status.setText(result.optBoolean("ok") ? provider + " подключён\n" +
                        (response == null ? "" : response.toString())
                        : "Проверка не пройдена: " + result.optString("reason", result.optString("error")));
            }
        } catch (Exception ignored) { }
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(9));
        body.addView(view); return view;
    }
    private EditText field(String hint, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint); field.setTextSize(16); field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setBackground(background(Color.WHITE));
        if (secret) field.setSaveEnabled(false);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(55));
        layout.bottomMargin = dp(10); body.addView(field, layout); return field;
    }
    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text); button.setAllCaps(false); button.setTextSize(16);
        button.setTextColor(PURPLE); button.setBackground(background(Color.rgb(234, 226, 249)));
        button.setOnFocusChangeListener((view, focused) -> view.setBackground(background(
                focused ? Color.rgb(214, 196, 246) : Color.rgb(234, 226, 249))));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(51));
        layout.bottomMargin = dp(10); body.addView(button, layout); return button;
    }
    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(12)); return drawable;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
}
