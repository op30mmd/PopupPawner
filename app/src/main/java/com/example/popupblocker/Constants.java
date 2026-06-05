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

    public static final String ACTION_CONFIG_PUSH = "com.example.popupblocker.CONFIG_PUSH";
    public static final String CACHE_FILE = "popup_blocker_cache.json";
    public static final String EX_PATTERNS = "patterns";
    public static final String EX_WHITELIST = "whitelist";
    public static final String EX_AGGRESSIVE = "aggressive";
    public static final String EX_ENABLED = "enabled";
    public static final String EX_DIAGNOSTICS = "diagnostics";
    public static final String EX_VERSION = "configVersion";
}
