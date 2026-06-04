package com.example.popupblocker;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PopupBlockerModule extends XposedModule {

    private static final String TAG = "PopupBlocker";
    private static final String PREFS_NAME = "popup_blocker_prefs";
    private static final String KEY_PATTERNS = "blocked_patterns";
    private static final String KEY_AGGRESSIVE = "aggressive_mode";
    private static final Set<String> DEFAULT_PATTERNS = new HashSet<>(Arrays.asList(
            "update", "rating", "survey", "ad", "commercial", "promotion"
    ));

    public PopupBlockerModule() {
        super();
    }

    public PopupBlockerModule(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
        super();
        attachFramework(base);
        log(4, TAG, "Module instantiated in process: " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        super.onPackageLoaded(param);
        String pkgName = param.getPackageName();
        log(4, TAG, "onPackageLoaded: " + pkgName);

        if (pkgName.equals("com.example.popupblocker")) {
            return;
        }

        // We don't skip system packages anymore to be as broad as possible as requested
        // but we still want to avoid critical system UI if possible.
        // For now, let's keep it broad.

        try {
            ClassLoader classLoader = param.getDefaultClassLoader();

            // Hook WindowManagerImpl.addView - very broad
            try {
                Class<?> wmClass = classLoader.loadClass("android.view.WindowManagerImpl");
                Method addView = wmClass.getDeclaredMethod("addView", View.class, ViewGroup.LayoutParams.class);
                hook(addView).intercept(chain -> {
                    View view = (View) chain.getArgs().get(0);
                    ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) chain.getArgs().get(1);
                    String match = checkBlock(view, params, pkgName);
                    if (match != null) {
                        log(4, TAG, "Blocked View addition in " + pkgName + ". Match: " + match);
                        return null;
                    }
                    return chain.proceed();
                });
                log(4, TAG, "Successfully hooked WindowManagerImpl.addView in " + pkgName);
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                log(4, TAG, "Failed to hook WindowManagerImpl.addView in " + pkgName + ": " + e.getMessage());
            }

            // Hook Dialog.show()
            try {
                Class<?> dialogClass = classLoader.loadClass("android.app.Dialog");
                Method showDialog = dialogClass.getDeclaredMethod("show");
                hook(showDialog).intercept(chain -> {
                    Dialog dialog = (Dialog) chain.getThisObject();
                    String match = checkBlock(dialog.getWindow().getDecorView(), null, pkgName);
                    if (match != null) {
                        log(4, TAG, "Blocked Dialog.show() in " + pkgName + ". Match: " + match);
                        return null;
                    }
                    return chain.proceed();
                });
                log(4, TAG, "Successfully hooked Dialog.show() in " + pkgName);
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                log(4, TAG, "Failed to hook Dialog.show() in " + pkgName + ": " + e.getMessage());
            }

        } catch (Exception e) {
            log(4, TAG, "Unexpected error in onPackageLoaded for " + pkgName + ": " + e.getMessage());
        }
    }

    private String checkBlock(View view, ViewGroup.LayoutParams params, String pkgName) {
        if (view == null) return null;
        SharedPreferences prefs = getRemotePreferences(PREFS_NAME);

        boolean aggressive = prefs.getBoolean(KEY_AGGRESSIVE, false);
        if (aggressive) {
            if (params instanceof WindowManager.LayoutParams) {
                int type = ((WindowManager.LayoutParams) params).type;
                // Sub-windows (dialogs, panels, etc) are usually >= 1000
                // Application windows are usually 1-99
                if (type >= 1000) {
                    return "Aggressive mode (Window Type: " + type + ")";
                }
            } else if (params == null) {
                // For Dialog.show() where we might not have the params yet in our hook
                return "Aggressive mode (Dialog)";
            }
        }

        Set<String> patterns = prefs.getStringSet(KEY_PATTERNS, DEFAULT_PATTERNS);
        return findBlockedTextRecursive(view, patterns);
    }

    private String findBlockedTextRecursive(View view, Set<String> patterns) {
        if (view instanceof TextView) {
            CharSequence textObj = ((TextView) view).getText();
            if (textObj != null) {
                String text = textObj.toString().toLowerCase();
                for (String pattern : patterns) {
                    if (text.contains(pattern.toLowerCase())) {
                        return "Text: '" + text + "' matched pattern: '" + pattern + "'";
                    }
                }
            }
        }

        CharSequence descObj = view.getContentDescription();
        if (descObj != null) {
            String desc = descObj.toString().toLowerCase();
            for (String pattern : patterns) {
                if (desc.contains(pattern.toLowerCase())) {
                    return "ContentDescription: '" + desc + "' matched pattern: '" + pattern + "'";
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String match = findBlockedTextRecursive(group.getChildAt(i), patterns);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }
}
