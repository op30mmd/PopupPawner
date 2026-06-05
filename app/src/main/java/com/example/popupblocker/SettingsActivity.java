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
    private static final String KEY_PATTERNS = "blocked_patterns";
    private static final String KEY_AGGRESSIVE = "aggressive_mode";

    private EditText patternsEdit;
    private CheckBox aggressiveCheckbox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        patternsEdit = findViewById(R.id.patterns_edit);
        aggressiveCheckbox = findViewById(R.id.aggressive_checkbox);
        Button saveButton = findViewById(R.id.save_button);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> patterns = prefs.getStringSet(KEY_PATTERNS, new HashSet<>(Arrays.asList("update", "rating", "survey")));
        boolean aggressive = prefs.getBoolean(KEY_AGGRESSIVE, false);

        StringBuilder sb = new StringBuilder();
        for (String s : patterns) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        patternsEdit.setText(sb.toString());
        aggressiveCheckbox.setChecked(aggressive);

        saveButton.setOnClickListener(v -> {
            String input = patternsEdit.getText().toString();
            String[] split = input.split(",");
            Set<String> newPatterns = new HashSet<>();
            for (String s : split) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    newPatterns.add(trimmed);
                }
            }

            prefs.edit()
                    .putStringSet(KEY_PATTERNS, newPatterns)
                    .putBoolean(KEY_AGGRESSIVE, aggressiveCheckbox.isChecked())
                    .apply();

            // FIX: Make the file readable by the Xposed module across processes
            try {
                File prefsFile = new File(getApplicationInfo().dataDir, "shared_prefs/" + PREFS_NAME + ".xml");
                if (prefsFile.exists()) {
                    prefsFile.setReadable(true, false);
                    prefsFile.setExecutable(true, false);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        });
    }
}
