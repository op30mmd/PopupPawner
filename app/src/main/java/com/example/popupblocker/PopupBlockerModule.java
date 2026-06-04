package com.example.popupblocker;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
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

            // Hook WindowManagerImpl.addView - catch all window types (Compose, Popups, etc)
            try {
                Class<?> wmClass = classLoader.loadClass("android.view.WindowManagerImpl");
                Method addView = wmClass.getDeclaredMethod("addView", View.class, ViewGroup.LayoutParams.class);
                hook(addView).intercept(chain -> {
                    View view = (View) chain.getArgs().get(0);
                    ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) chain.getArgs().get(1);

                    Object result = chain.proceed();

                    if (params instanceof WindowManager.LayoutParams) {
                        WindowManager.LayoutParams wl = (WindowManager.LayoutParams) params;
                        // Only target sub-windows (dialogs, panels, popups)
                        if (wl.type >= 1000 && wl.type <= 2999) {
                            view.setAlpha(0f); // Hide until scanned
                            view.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                                @Override
                                public void onGlobalLayout() {
                                    // Always remove listener after first run to prevent performance leak
                                    view.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                                    String match = checkBlock(view, wl, false);
                                    if (match != null) {
                                        log(4, TAG, "Blocked View addition in " + pkgName + ". " + match);
                                        view.setVisibility(View.GONE);
                                    } else {
                                        view.setAlpha(1f);
                                    }
                                }
                            });
                        }
                    }
                    return result;
                });
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                // Ignore
            }

            // Hook Dialog.show() - traditional Dialog fix
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

                    if (window != null) {
                        // For Dialogs, we treat 'null' params as a signal to check aggressive mode
                        String match = checkBlock(window.getDecorView(), null, true);
                        if (match != null) {
                            log(4, TAG, "Blocked Dialog in " + pkgName + ". " + match);
                            dialog.dismiss();
                        } else {
                            window.getDecorView().setAlpha(1f);
                        }
                    }
                    return result;
                });
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                // Ignore
            }

            // Hook Activity.onResume - catch Activity-based popups
            try {
                Class<?> activityClass = classLoader.loadClass("android.app.Activity");
                Method onResume = activityClass.getDeclaredMethod("onResume");
                hook(onResume).intercept(chain -> {
                    Activity activity = (Activity) chain.getThisObject();
                    Object result = chain.proceed();

                    // NEVER trigger aggressive mode on generic Activity resume (would break the phone)
                    String match = checkBlock(activity.getWindow().getDecorView(), null, false);
                    if (match != null) {
                        log(4, TAG, "Blocked Activity popup in " + pkgName + ". " + match);
                        activity.finish();
                    }
                    return result;
                });
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                // Ignore
            }

        } catch (Exception e) {
            log(4, TAG, "Error in onPackageLoaded: " + e.getMessage());
        }
    }

    private String checkBlock(View view, WindowManager.LayoutParams params, boolean isExplicitDialog) {
        if (view == null) return null;
        SharedPreferences prefs = getRemotePreferences(PREFS_NAME);

        // Aggressive mode logic: only block if it's a sub-window or explicit dialog
        if (prefs.getBoolean(KEY_AGGRESSIVE, false)) {
            if (isExplicitDialog) {
                return "Aggressive mode (Dialog)";
            }
            if (params != null && params.type >= 1000 && params.type <= 2999) {
                return "Aggressive mode (Window Type " + params.type + ")";
            }
        }

        Set<String> patterns = prefs.getStringSet(KEY_PATTERNS, DEFAULT_PATTERNS);
        return findBlockedText(view, patterns);
    }

    private String findBlockedText(View view, Set<String> patterns) {
        AccessibilityNodeInfo nodeInfo = view.createAccessibilityNodeInfo();
        if (nodeInfo != null) {
            String match = searchNodeRecursive(nodeInfo, patterns);
            nodeInfo.recycle();
            return match;
        }
        return null;
    }

    private String searchNodeRecursive(AccessibilityNodeInfo node, Set<String> patterns) {
        if (node == null) return null;

        CharSequence textObj = node.getText();
        if (textObj != null) {
            String text = textObj.toString().toLowerCase();
            for (String p : patterns) {
                if (text.contains(p.toLowerCase())) return "Text match: " + p;
            }
        }

        CharSequence descObj = node.getContentDescription();
        if (descObj != null) {
            String desc = descObj.toString().toLowerCase();
            for (String p : patterns) {
                if (desc.contains(p.toLowerCase())) return "Description match: " + p;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String match = searchNodeRecursive(child, patterns);
                child.recycle();
                if (match != null) return match;
            }
        }
        return null;
    }
}
