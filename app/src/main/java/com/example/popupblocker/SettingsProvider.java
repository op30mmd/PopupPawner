package com.example.popupblocker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;

import static com.example.popupblocker.Constants.*;

public class SettingsProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"getConfig".equals(method)) return null;

        Context ctx = getContext();
        if (ctx == null) return null;

        SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        Bundle b = new Bundle();
        b.putBoolean("enabled", p.getBoolean(KEY_ENABLED, true));
        b.putStringArrayList("patterns", p.contains(KEY_PATTERNS)
                ? new ArrayList<>(p.getStringSet(KEY_PATTERNS, Collections.emptySet()))
                : new ArrayList<>(DEFAULT_PATTERNS));
        b.putStringArrayList("whitelist", p.contains(KEY_WHITELIST)
                ? new ArrayList<>(p.getStringSet(KEY_WHITELIST, Collections.emptySet()))
                : new ArrayList<>(DEFAULT_WHITELIST));
        b.putBoolean("aggressive", p.getBoolean(KEY_AGGRESSIVE, false));
        b.putBoolean("diagnostics", p.getBoolean(KEY_DIAGNOSTICS, false));
        b.putLong("configVersion", p.getLong(KEY_CONFIG_VERSION, 0));

        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
