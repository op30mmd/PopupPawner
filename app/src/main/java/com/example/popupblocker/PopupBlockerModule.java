package com.example.popupblocker;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        super.onPackageLoaded(param);
        String pkgName = param.getPackageName();

        if (pkgName.equals("com.example.popupblocker")) {
            return;
        }

        try {
            ClassLoader classLoader = param.getDefaultClassLoader();

            // Hook WindowManagerImpl.addView
            try {
                Class<?> wmClass = classLoader.loadClass("android.view.WindowManagerImpl");
                Method addView = wmClass.getDeclaredMethod("addView", View.class, ViewGroup.LayoutParams.class);
                hook(addView).intercept(chain -> {
                    View view = (View) chain.getArgs().get(0);
                    ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) chain.getArgs().get(1);
                    String match = checkBlock(view, params);
                    if (match != null) {
                        log(4, TAG, "Blocked View addition in " + pkgName + ". Match: " + match);
                        return null;
                    }
                    return chain.proceed();
                });
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                log(4, TAG, "Failed to hook addView: " + e.getMessage());
            }

            // Hook Dialog.show() with lifecycle fix
            try {
                Class<?> dialogClass = classLoader.loadClass("android.app.Dialog");
                Method showDialog = dialogClass.getDeclaredMethod("show");
                hook(showDialog).intercept(chain -> {
                    Dialog dialog = (Dialog) chain.getThisObject();
                    Window window = dialog.getWindow();

                    if (window != null) {
                        window.getDecorView().setAlpha(0f);
                    }

                    Object result = chain.proceed();

                    String match = checkBlock(dialog.getWindow().getDecorView(), null);
                    if (match != null) {
                        log(4, TAG, "Blocked Dialog in " + pkgName + ". Match: " + match);
                        dialog.dismiss();
                    } else if (window != null) {
                        window.getDecorView().setAlpha(1f);
                    }

                    return result;
                });
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                log(4, TAG, "Failed to hook Dialog.show(): " + e.getMessage());
            }

        } catch (Exception e) {
            log(4, TAG, "Error in onPackageLoaded: " + e.getMessage());
        }
    }

    private String checkBlock(View view, ViewGroup.LayoutParams params) {
        if (view == null) return null;
        SharedPreferences prefs = getRemotePreferences(PREFS_NAME);

        if (prefs.getBoolean(KEY_AGGRESSIVE, false)) {
            if (params instanceof WindowManager.LayoutParams) {
                int type = ((WindowManager.LayoutParams) params).type;
                if (type >= 1000) return "Aggressive mode (Type " + type + ")";
            } else if (params == null) {
                return "Aggressive mode (Dialog)";
            }
        }

        Set<String> patterns = prefs.getStringSet(KEY_PATTERNS, DEFAULT_PATTERNS);
        return findBlockedTextRecursive(view, patterns);
    }

    private String findBlockedTextRecursive(View view, Set<String> patterns) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                for (String p : patterns) {
                    if (text.toString().toLowerCase().contains(p.toLowerCase())) {
                        return "Text match: " + p;
                    }
                }
            }
        }

        CharSequence desc = view.getContentDescription();
        if (desc != null) {
            for (String p : patterns) {
                if (desc.toString().toLowerCase().contains(p.toLowerCase())) {
                    return "Description match: " + p;
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String match = findBlockedTextRecursive(group.getChildAt(i), patterns);
                if (match != null) return match;
            }
        }
        return null;
    }
}
