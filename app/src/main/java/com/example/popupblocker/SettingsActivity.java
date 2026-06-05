package com.example.popupblocker;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SettingsActivity extends Activity {

    private static final String PREFS_NAME = "popup_blocker_prefs";
    private static final String KEY_ENABLED = "module_enabled";
    private static final String KEY_PATTERNS = "blocked_patterns";
    private static final String KEY_WHITELIST = "whitelist_patterns";
    private static final String KEY_AGGRESSIVE = "aggressive_mode";
    private static final String KEY_DIAGNOSTICS = "verbose_diagnostics";

    private CheckBox enabledCheckbox;
    private EditText patternsEdit;
    private EditText whitelistEdit;
    private CheckBox aggressiveCheckbox;
    private CheckBox diagnosticsCheckbox;

    private SharedPreferences openPrefs() {
        try {
            // LSPosed bridges MODE_WORLD_READABLE to hooked processes
            return getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
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

        patternsEdit.setText(joinSet(prefs.getStringSet(KEY_PATTERNS,
                new HashSet<>(Arrays.asList("update", "rating", "survey")))));
        whitelistEdit.setText(joinSet(prefs.getStringSet(KEY_WHITELIST,
                new HashSet<>(Arrays.asList("save", "login", "search")))));

        saveButton.setOnClickListener(v -> {
            openPrefs().edit()
                    .putBoolean(KEY_ENABLED, enabledCheckbox.isChecked())
                    .putStringSet(KEY_PATTERNS, splitString(patternsEdit.getText().toString()))
                    .putStringSet(KEY_WHITELIST, splitString(whitelistEdit.getText().toString()))
                    .putBoolean(KEY_AGGRESSIVE, aggressiveCheckbox.isChecked())
                    .putBoolean(KEY_DIAGNOSTICS, diagnosticsCheckbox.isChecked())
                    .apply();

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        });
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
