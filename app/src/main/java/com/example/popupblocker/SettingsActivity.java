package com.example.popupblocker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static com.example.popupblocker.Constants.*;

public class SettingsActivity extends Activity {

    private CheckBox enabledCheckbox;
    private EditText patternsEdit;
    private EditText whitelistEdit;
    private CheckBox aggressiveCheckbox;
    private CheckBox diagnosticsCheckbox;

    private SharedPreferences openPrefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        enabledCheckbox = findViewById(R.id.enabled_checkbox);
        patternsEdit = findViewById(R.id.patterns_edit);
        whitelistEdit = findViewById(R.id.whitelist_edit);
        aggressiveCheckbox = findViewById(R.id.aggressive_checkbox);
        diagnosticsCheckbox = findViewById(R.id.diagnostics_checkbox);
        Button saveButton = findViewById(R.id.save_button);

        SharedPreferences prefs = openPrefs();
        enabledCheckbox.setChecked(prefs.getBoolean(KEY_ENABLED, true));
        aggressiveCheckbox.setChecked(prefs.getBoolean(KEY_AGGRESSIVE, false));
        diagnosticsCheckbox.setChecked(prefs.getBoolean(KEY_DIAGNOSTICS, false));

        patternsEdit.setText(joinSet(prefs.getStringSet(KEY_PATTERNS, DEFAULT_PATTERNS)));
        whitelistEdit.setText(joinSet(prefs.getStringSet(KEY_WHITELIST, DEFAULT_WHITELIST)));

        saveButton.setOnClickListener(v -> {
            Set<String> patterns = splitString(patternsEdit.getText().toString());
            Set<String> whitelist = splitString(whitelistEdit.getText().toString());
            saveAndPush(patterns, whitelist, aggressiveCheckbox.isChecked(),
                    enabledCheckbox.isChecked(), diagnosticsCheckbox.isChecked());

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        });
    }

    private void saveAndPush(Set<String> patterns, Set<String> whitelist,
                             boolean aggressive, boolean enabled, boolean diagnostics) {
        long version = System.currentTimeMillis();
        openPrefs().edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putStringSet(KEY_PATTERNS, new HashSet<>(patterns))
                .putStringSet(KEY_WHITELIST, new HashSet<>(whitelist))
                .putBoolean(KEY_AGGRESSIVE, aggressive)
                .putBoolean(KEY_DIAGNOSTICS, diagnostics)
                .putLong(KEY_CONFIG_VERSION, version)
                .apply();

        Intent i = new Intent(ACTION_CONFIG_PUSH);
        i.setPackage(null); // Explicitly implicit
        i.putStringArrayListExtra(EX_PATTERNS, new ArrayList<>(patterns));
        i.putStringArrayListExtra(EX_WHITELIST, new ArrayList<>(whitelist));
        i.putExtra(EX_AGGRESSIVE, aggressive);
        i.putExtra(EX_ENABLED, enabled);
        i.putExtra(EX_DIAGNOSTICS, diagnostics);
        i.putExtra(EX_VERSION, version);
        sendBroadcast(i);
    }

    private String joinSet(Set<String> set) {
        if (set == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private Set<String> splitString(String input) {
        Set<String> set = new HashSet<>();
        if (input == null) return set;
        String[] split = input.split(",");
        for (String s : split) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }
}
