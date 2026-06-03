package com.example.popupblocker;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PopupBlockerModule implements XposedModuleInterface {

    private static final String TAG = "PopupBlocker";
    private static final String PREFS_NAME = "popup_blocker_prefs";
    private static final String KEY_PATTERNS = "blocked_patterns";
    private static final Set<String> DEFAULT_PATTERNS = new HashSet<>(Arrays.asList(
            "update", "rating", "survey", "ad", "commercial", "promotion"
    ));

    private final XposedInterface mFramework;

    public PopupBlockerModule(XposedInterface base, ModuleLoadedParam param) {
        this.mFramework = base;
        mFramework.log(4, TAG, "Module instantiated in process: " + param.getProcessName());
    }

    public void attachFramework(XposedInterface base) {
        // Satisfy potential reflection-based framework calls
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (mFramework == null) return;
        String pkgName = param.getPackageName();
        mFramework.log(4, TAG, "onPackageLoaded: " + pkgName);

        if (pkgName.equals("com.example.popupblocker")) {
            mFramework.log(4, TAG, "Skipping own package: " + pkgName);
            return;
        }

        if ((param.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            mFramework.log(4, TAG, "Skipping system package: " + pkgName);
            return;
        }

        try {
            ClassLoader classLoader = param.getDefaultClassLoader();

            // Hook Dialog.show()
            try {
                Class<?> dialogClass = classLoader.loadClass("android.app.Dialog");
                Method showDialog = dialogClass.getDeclaredMethod("show");
                mFramework.hook(showDialog).intercept(chain -> {
                    if (shouldBlockDialog((Dialog) chain.getThisObject(), pkgName)) {
                        mFramework.log(4, TAG, "Blocked Dialog in " + pkgName);
                        return null;
                    }
                    return chain.proceed();
                });
                mFramework.log(4, TAG, "Successfully hooked Dialog.show() in " + pkgName);
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                mFramework.log(4, TAG, "Failed to hook Dialog.show() in " + pkgName + ": " + e.getMessage());
            }

            // Hook PopupWindow methods
            try {
                Class<?> popupClass = classLoader.loadClass("android.widget.PopupWindow");

                Class<?>[] showAsDropDown1Args = {View.class};
                mFramework.hook(popupClass.getDeclaredMethod("showAsDropDown", showAsDropDown1Args)).intercept(chain -> {
                    if (shouldBlockPopupWindow((PopupWindow) chain.getThisObject(), pkgName)) {
                        mFramework.log(4, TAG, "Blocked PopupWindow (dropdown) in " + pkgName);
                        return null;
                    }
                    return chain.proceed();
                });

                Class<?>[] showAsDropDown2Args = {View.class, int.class, int.class};
                mFramework.hook(popupClass.getDeclaredMethod("showAsDropDown", showAsDropDown2Args)).intercept(chain -> {
                    if (shouldBlockPopupWindow((PopupWindow) chain.getThisObject(), pkgName)) {
                        mFramework.log(4, TAG, "Blocked PopupWindow (dropdown offset) in " + pkgName);
                        return null;
                    }
                    return chain.proceed();
                });

                Class<?>[] showAsDropDown3Args = {View.class, int.class, int.class, int.class};
                mFramework.hook(popupClass.getDeclaredMethod("showAsDropDown", showAsDropDown3Args)).intercept(chain -> {
                    if (shouldBlockPopupWindow((PopupWindow) chain.getThisObject(), pkgName)) {
                        mFramework.log(4, TAG, "Blocked PopupWindow (dropdown gravity) in " + pkgName);
                        return null;
                    }
                    return chain.proceed();
                });

                Class<?>[] showAtLocationArgs = {View.class, int.class, int.class, int.class};
                mFramework.hook(popupClass.getDeclaredMethod("showAtLocation", showAtLocationArgs)).intercept(chain -> {
                    if (shouldBlockPopupWindow((PopupWindow) chain.getThisObject(), pkgName)) {
                        mFramework.log(4, TAG, "Blocked PopupWindow (location) in " + pkgName);
                        return null;
                    }
                    return chain.proceed();
                });
                mFramework.log(4, TAG, "Successfully hooked PopupWindow methods in " + pkgName);
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                mFramework.log(4, TAG, "Failed to hook PopupWindow methods in " + pkgName + ": " + e.getMessage());
            }

        } catch (Exception e) {
            mFramework.log(4, TAG, "Unexpected error in onPackageLoaded for " + pkgName + ": " + e.getMessage());
        }
    }

    private boolean shouldBlockDialog(Dialog dialog, String pkgName) {
        if (dialog == null || dialog.getWindow() == null) return false;
        mFramework.log(4, TAG, "Checking Dialog in " + pkgName);
        return findBlockedText(dialog.getWindow().getDecorView(), pkgName);
    }

    private boolean shouldBlockPopupWindow(PopupWindow popupWindow, String pkgName) {
        if (popupWindow == null || popupWindow.getContentView() == null) return false;
        mFramework.log(4, TAG, "Checking PopupWindow in " + pkgName);
        return findBlockedText(popupWindow.getContentView(), pkgName);
    }

    private boolean findBlockedText(View view, String pkgName) {
        Set<String> patterns = getPatterns();
        return findBlockedTextRecursive(view, patterns, pkgName);
    }

    private boolean findBlockedTextRecursive(View view, Set<String> patterns, String pkgName) {
        if (view instanceof TextView) {
            CharSequence textObj = ((TextView) view).getText();
            if (textObj != null) {
                String text = textObj.toString().toLowerCase();
                for (String pattern : patterns) {
                    if (text.contains(pattern.toLowerCase())) {
                        mFramework.log(4, TAG, "Match found! Pattern: '" + pattern + "' in text: '" + text + "' (Package: " + pkgName + ")");
                        return true;
                    }
                }
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (findBlockedTextRecursive(group.getChildAt(i), patterns, pkgName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> getPatterns() {
        try {
            SharedPreferences prefs = mFramework.getRemotePreferences(PREFS_NAME);
            Set<String> patterns = prefs.getStringSet(KEY_PATTERNS, DEFAULT_PATTERNS);
            mFramework.log(4, TAG, "Loaded " + patterns.size() + " patterns");
            return patterns;
        } catch (Exception e) {
            mFramework.log(4, TAG, "Error loading patterns, using defaults: " + e.getMessage());
            return DEFAULT_PATTERNS;
        }
    }
}
