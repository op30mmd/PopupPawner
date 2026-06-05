package com.example.popupblocker;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Constants {
    public static final String TAG = "PopupBlocker";
    public static final String PREFS_NAME = "popup_blocker_prefs";
    public static final String KEY_ENABLED = "module_enabled";
    public static final String KEY_PATTERNS = "blocked_patterns";
    public static final String KEY_WHITELIST = "whitelist_patterns";
    public static final String KEY_AGGRESSIVE = "aggressive_mode";
    public static final String KEY_DIAGNOSTICS = "verbose_diagnostics";
    public static final String KEY_CONFIG_VERSION = "config_version";

    public static final Set<String> DEFAULT_PATTERNS = new HashSet<>(Arrays.asList(
            "update", "rating", "survey", "ad", "commercial", "promotion"
    ));
    public static final Set<String> DEFAULT_WHITELIST = new HashSet<>(Arrays.asList(
            "save", "login", "search"
    ));

    public static final String AUTHORITY = "com.example.popupblocker.settings";
}
