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

    private static final String TAG = "PopupBlocker";
    private static final String PREFS_NAME = "popup_blocker_prefs";
    private static final String KEY_PATTERNS = "blocked_patterns";
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
            log(4, TAG, "Skipping own package: " + pkgName);
            return;
        }

        if ((param.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            log(4, TAG, "Skipping system package: " + pkgName);
            return;
        }

        try {
            ClassLoader classLoader = param.getDefaultClassLoader();

            // Hook Dialog.show()
            try {
                Class<?> dialogClass = classLoader.loadClass("android.app.Dialog");
                Method showDialog = dialogClass.getDeclaredMethod("show");
                hook(showDialog).intercept(chain -> {
                    String match = findBlockedText(shouldBlockDialog((Dialog) chain.getThisObject()));
                    if (match != null) {
                        log(4, TAG, "Blocked Dialog in " + pkgName + ". Match details: " + match);
                        return null;
                    }
                    return chain.proceed();
                });
                log(4, TAG, "Successfully hooked Dialog.show() in " + pkgName);
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                log(4, TAG, "Failed to hook Dialog.show() in " + pkgName + ": " + e.getMessage());
            }

            // Hook PopupWindow methods
            try {
                Class<?> popupClass = classLoader.loadClass("android.widget.PopupWindow");

                Class<?>[] showAsDropDown1Args = {View.class};
                hook(popupClass.getDeclaredMethod("showAsDropDown", showAsDropDown1Args)).intercept(chain -> {
                    String match = findBlockedText(shouldBlockPopupWindow((PopupWindow) chain.getThisObject()));
                    if (match != null) {
                        log(4, TAG, "Blocked PopupWindow (dropdown) in " + pkgName + ". Match details: " + match);
                        return null;
                    }
                    return chain.proceed();
                });

                Class<?>[] showAsDropDown2Args = {View.class, int.class, int.class};
                hook(popupClass.getDeclaredMethod("showAsDropDown", showAsDropDown2Args)).intercept(chain -> {
                    String match = findBlockedText(shouldBlockPopupWindow((PopupWindow) chain.getThisObject()));
                    if (match != null) {
                        log(4, TAG, "Blocked PopupWindow (dropdown offset) in " + pkgName + ". Match details: " + match);
                        return null;
                    }
                    return chain.proceed();
                });

                Class<?>[] showAsDropDown3Args = {View.class, int.class, int.class, int.class};
                hook(popupClass.getDeclaredMethod("showAsDropDown", showAsDropDown3Args)).intercept(chain -> {
                    String match = findBlockedText(shouldBlockPopupWindow((PopupWindow) chain.getThisObject()));
                    if (match != null) {
                        log(4, TAG, "Blocked PopupWindow (dropdown gravity) in " + pkgName + ". Match details: " + match);
                        return null;
                    }
                    return chain.proceed();
                });

                Class<?>[] showAtLocationArgs = {View.class, int.class, int.class, int.class};
                hook(popupClass.getDeclaredMethod("showAtLocation", showAtLocationArgs)).intercept(chain -> {
                    String match = findBlockedText(shouldBlockPopupWindow((PopupWindow) chain.getThisObject()));
                    if (match != null) {
                        log(4, TAG, "Blocked PopupWindow (location) in " + pkgName + ". Match details: " + match);
                        return null;
                    }
                    return chain.proceed();
                });
                log(4, TAG, "Successfully hooked PopupWindow methods in " + pkgName);
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                log(4, TAG, "Failed to hook PopupWindow methods in " + pkgName + ": " + e.getMessage());
            }

        } catch (Exception e) {
            log(4, TAG, "Unexpected error in onPackageLoaded for " + pkgName + ": " + e.getMessage());
        }
    }

    private View shouldBlockDialog(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return null;
        return dialog.getWindow().getDecorView();
    }

    private View shouldBlockPopupWindow(PopupWindow popupWindow) {
        if (popupWindow == null || popupWindow.getContentView() == null) return null;
        return popupWindow.getContentView();
    }

    private String findBlockedText(View view) {
        if (view == null) return null;
        Set<String> patterns = getPatterns();
        return findBlockedTextRecursive(view, patterns);
    }

    private String findBlockedTextRecursive(View view, Set<String> patterns) {
        if (view instanceof TextView) {
            CharSequence textObj = ((TextView) view).getText();
            if (textObj != null) {
                String text = textObj.toString().toLowerCase();
                for (String pattern : patterns) {
                    if (text.contains(pattern.toLowerCase())) {
                        return "Pattern: '" + pattern + "' matched in text: '" + text + "'";
                    }
                }
            }
        } else if (view instanceof ViewGroup) {
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

    private Set<String> getPatterns() {
        try {
            SharedPreferences prefs = getRemotePreferences(PREFS_NAME);
            Set<String> patterns = prefs.getStringSet(KEY_PATTERNS, DEFAULT_PATTERNS);
            return patterns;
        } catch (Exception e) {
            return DEFAULT_PATTERNS;
        }
    }
}
