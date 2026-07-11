package tianci.dev.xptranslatetext.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import tianci.dev.xptranslatetext.core.server.R;

/**
 * Configure a custom OpenAI-compatible LLM (DeepSeek / SiliconFlow / any compatible endpoint)
 * as the online translation backend. Settings are stored in xp_translate_text_configs so the
 * LocalTranslationService can read them.
 */
public class LlmSettingsActivity extends AppCompatActivity {

    private static final String PREF_NAME = "xp_translate_text_configs";

    private static final String PROVIDER_DEEPSEEK = "deepseek";
    private static final String PROVIDER_SILICONFLOW = "siliconflow";
    private static final String PROVIDER_CUSTOM = "custom";

    private MaterialSwitch enableSwitch;
    private MaterialAutoCompleteTextView providerDropdown;
    private TextInputEditText baseUrlEdit;
    private TextInputEditText apiKeyEdit;
    private TextInputEditText modelEdit;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_llm_settings);

        setTitle(R.string.llm_settings_title);

        enableSwitch = findViewById(R.id.switch_llm_enabled);
        providerDropdown = findViewById(R.id.spinner_llm_provider);
        baseUrlEdit = findViewById(R.id.edit_llm_base_url);
        apiKeyEdit = findViewById(R.id.edit_llm_api_key);
        modelEdit = findViewById(R.id.edit_llm_model);

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        setupProviderDropdown();
        loadSettings();

        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("llm_enabled", isChecked).apply());

        MaterialButton saveButton = findViewById(R.id.btn_llm_save);
        saveButton.setOnClickListener(v -> saveSettings());
    }

    private void setupProviderDropdown() {
        String[] entries = new String[]{
                getString(R.string.llm_provider_deepseek),
                getString(R.string.llm_provider_siliconflow),
                getString(R.string.llm_provider_custom)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, entries);
        providerDropdown.setAdapter(adapter);

        providerDropdown.setOnItemClickListener((parent, view, position, id) -> {
            String provider = indexToProviderId(position);
            applyProviderPreset(provider);
        });
    }

    private String indexToProviderId(int index) {
        switch (index) {
            case 0:
                return PROVIDER_DEEPSEEK;
            case 1:
                return PROVIDER_SILICONFLOW;
            default:
                return PROVIDER_CUSTOM;
        }
    }

    private String providerToId(String label) {
        if (label.equals(getString(R.string.llm_provider_deepseek))) return PROVIDER_DEEPSEEK;
        if (label.equals(getString(R.string.llm_provider_siliconflow))) return PROVIDER_SILICONFLOW;
        return PROVIDER_CUSTOM;
    }

    private void applyProviderPreset(String provider) {
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            baseUrlEdit.setText("https://api.deepseek.com/v1");
            modelEdit.setText("deepseek-chat");
        } else if (PROVIDER_SILICONFLOW.equals(provider)) {
            baseUrlEdit.setText("https://api.siliconflow.cn/v1");
            modelEdit.setText("Qwen/Qwen2.5-7B-Instruct");
        }
        // custom: leave fields for the user to fill.
    }

    private void loadSettings() {
        enableSwitch.setChecked(prefs.getBoolean("llm_enabled", false));
        String baseUrl = prefs.getString("llm_base_url", "");
        String apiKey = prefs.getString("llm_api_key", "");
        String model = prefs.getString("llm_model", "");

        baseUrlEdit.setText(baseUrl);
        apiKeyEdit.setText(apiKey);
        modelEdit.setText(model);

        String provider = prefs.getString("llm_provider", PROVIDER_CUSTOM);
        int providerIndex;
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            providerIndex = 0;
        } else if (PROVIDER_SILICONFLOW.equals(provider)) {
            providerIndex = 1;
        } else {
            providerIndex = 2;
        }
        String[] entries = new String[]{
                getString(R.string.llm_provider_deepseek),
                getString(R.string.llm_provider_siliconflow),
                getString(R.string.llm_provider_custom)
        };
        if (providerIndex < entries.length) {
            providerDropdown.setText(entries[providerIndex], false);
        }
    }

    private void saveSettings() {
        String baseUrl = baseUrlEdit.getText() == null ? "" : baseUrlEdit.getText().toString().trim();
        String apiKey = apiKeyEdit.getText() == null ? "" : apiKeyEdit.getText().toString().trim();
        String model = modelEdit.getText() == null ? "" : modelEdit.getText().toString().trim();

        String providerLabel = providerDropdown.getText() == null ? "" : providerDropdown.getText().toString();
        String provider = providerToId(providerLabel);

        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("llm_enabled", enableSwitch.isChecked());
        editor.putString("llm_provider", provider);
        editor.putString("llm_base_url", baseUrl);
        editor.putString("llm_api_key", apiKey);
        editor.putString("llm_model", model);
        editor.apply();

        if (enableSwitch.isChecked() && (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(model))) {
            Toast.makeText(this, R.string.llm_save_incomplete, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.llm_saved, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
