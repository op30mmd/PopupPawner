package com.example.popupblocker;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.view.View;
import android.view.ViewGroup;
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

    private static final String PREFS_NAME = "popup_blocker_prefs";
    private static final String KEY_PATTERNS = "blocked_patterns";
    private static final Set<String> DEFAULT_PATTERNS = new HashSet<>(Arrays.asList(
            "update", "rating", "survey", "ad", "commercial", "promotion"
    ));

    private final XposedInterface mFramework;

    public PopupBlockerModule(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
        super();
        this.mFramework = base;
        try {
            attachFramework(base);
        } catch (Throwable ignored) {
            // Fallback for environments where attachFramework is missing
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        super.onPackageLoaded(param);
        if (mFramework == null) return;
        if (param.getPackageName().equals("com.example.popupblocker")) {
            return;
        }

        if ((param.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return;
        }

        try {
            ClassLoader classLoader = param.getDefaultClassLoader();

            // Hook Dialog.show()
            try {
                Class<?> dialogClass = classLoader.loadClass("android.app.Dialog");
                Method showDialog = dialogClass.getDeclaredMethod("show");
                mFramework.hook(showDialog).intercept(chain -> {
                    if (shouldBlockDialog((Dialog) chain.getThisObject())) {
                        mFramework.log(4, "PopupBlocker", "Blocked Dialog in " + param.getPackageName());
                        return null;
                    }
                    return chain.proceed();
                });
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                // Ignore
            }

            // Hook PopupWindow methods
            try {
                Class<?> popupClass = classLoader.loadClass("android.widget.PopupWindow");

                Class<?>[] showAsDropDown1Args = {View.class};
                mFramework.hook(popupClass.getDeclaredMethod("showAsDropDown", showAsDropDown1Args)).intercept(chain -> {
                    if (shouldBlockPopupWindow((PopupWindow) chain.getThisObject())) {
                        mFramework.log(4, "PopupBlocker", "Blocked PopupWindow (dropdown) in " + param.getPackageName());
                        return null;
                    }
                    return chain.proceed();
                });

                Class<?>[] showAsDropDown2Args = {View.class, int.class, int.class};
                mFramework.hook(popupClass.getDeclaredMethod("showAsDropDown", showAsDropDown2Args)).intercept(chain -> {
                    if (shouldBlockPopupWindow((PopupWindow) chain.getThisObject())) {
                        mFramework.log(4, "PopupBlocker", "Blocked PopupWindow (dropdown offset) in " + param.getPackageName());
                        return null;
                    }
                    return chain.proceed();
                });

                Class<?>[] showAsDropDown3Args = {View.class, int.class, int.class, int.class};
                mFramework.hook(popupClass.getDeclaredMethod("showAsDropDown", showAsDropDown3Args)).intercept(chain -> {
                    if (shouldBlockPopupWindow((PopupWindow) chain.getThisObject())) {
                        mFramework.log(4, "PopupBlocker", "Blocked PopupWindow (dropdown gravity) in " + param.getPackageName());
                        return null;
                    }
                    return chain.proceed();
                });

                Class<?>[] showAtLocationArgs = {View.class, int.class, int.class, int.class};
                mFramework.hook(popupClass.getDeclaredMethod("showAtLocation", showAtLocationArgs)).intercept(chain -> {
                    if (shouldBlockPopupWindow((PopupWindow) chain.getThisObject())) {
                        mFramework.log(4, "PopupBlocker", "Blocked PopupWindow (location) in " + param.getPackageName());
                        return null;
                    }
                    return chain.proceed();
                });
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                // Ignore
            }

        } catch (Exception e) {
            // Unexpected error
        }
    }

    private boolean shouldBlockDialog(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return false;
        return findBlockedText(dialog.getWindow().getDecorView());
    }

    private boolean shouldBlockPopupWindow(PopupWindow popupWindow) {
        if (popupWindow == null || popupWindow.getContentView() == null) return false;
        return findBlockedText(popupWindow.getContentView());
    }

    private boolean findBlockedText(View view) {
        Set<String> patterns = getPatterns();
        return findBlockedTextRecursive(view, patterns);
    }

    private boolean findBlockedTextRecursive(View view, Set<String> patterns) {
        if (view instanceof TextView) {
            CharSequence textObj = ((TextView) view).getText();
            if (textObj != null) {
                String text = textObj.toString().toLowerCase();
                for (String pattern : patterns) {
                    if (text.contains(pattern.toLowerCase())) {
                        return true;
                    }
                }
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (findBlockedTextRecursive(group.getChildAt(i), patterns)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> getPatterns() {
        try {
            SharedPreferences prefs = mFramework.getRemotePreferences(PREFS_NAME);
            return prefs.getStringSet(KEY_PATTERNS, DEFAULT_PATTERNS);
        } catch (Exception e) {
            return DEFAULT_PATTERNS;
        }
    }
}
