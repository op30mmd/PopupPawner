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
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
        if (view == null) return null;

        // 1. STANDARD ANDROID SEARCH
        ArrayList<View> outViews = new ArrayList<>();
        for (String pattern : patterns) {
            view.findViewsWithText(outViews, pattern, 1); // FIND_VIEWS_WITH_TEXT
            if (!outViews.isEmpty()) return "Text match: " + pattern;

            view.findViewsWithText(outViews, pattern, 2); // FIND_VIEWS_WITH_CONTENT_DESCRIPTION
            if (!outViews.isEmpty()) return "Description match: " + pattern;
        }

        // 2. VIRTUAL TREE SEARCH (Compose, Flutter)
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null) {
            for (String pattern : patterns) {
                try {
                    List<AccessibilityNodeInfo> nodes = provider.findAccessibilityNodeInfosByText(pattern, -1);
                    if (nodes != null && !nodes.isEmpty()) {
                        return "Virtual tree match: " + pattern;
                    }
                } catch (Throwable ignored) {}
            }
        }

        // 3. FALLBACK
        return findBlockedTextRecursive(view, patterns);
    }

    private String findBlockedTextRecursive(View view, Set<String> patterns) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                for (String p : patterns) {
                    if (text.toString().toLowerCase().contains(p.toLowerCase())) return "Recursive text match: " + p;
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
