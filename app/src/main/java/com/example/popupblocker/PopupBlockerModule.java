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

    public PopupBlockerModule(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
        super();
        attachFramework(base);
        log(4, TAG, "Module instantiated");
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
                    View view = (View) chain.getArg(0);
                    ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) chain.getArg(1);

                    Object result = chain.proceed();

                    if (params instanceof WindowManager.LayoutParams) {
                        WindowManager.LayoutParams wl = (WindowManager.LayoutParams) params;
                        // Target only sub-windows/panels (types 1000-1999)
                        // Never target main Activity windows (types 1, 2) to avoid double-remove crashes
                        boolean isCandidate = (wl.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW &&
                                              wl.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW);

                        if (isCandidate) {
                            view.setAlpha(0f); // Hide until scanned
                            view.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                                @Override
                                public void onGlobalLayout() {
                                    // Always remove listener after first run to prevent performance leak
                                    view.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                                    String match = checkBlock(view, wl, false);
                                    if (match != null) {
                                        log(4, TAG, "Blocked View addition in " + pkgName + ". " + match);
                                        view.post(() -> {
                                            if (view.isAttachedToWindow()) {
                                                try {
                                                    WindowManager wm = (WindowManager) view.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
                                                    wm.removeView(view);
                                                } catch (Exception e) {
                                                    view.setVisibility(View.GONE);
                                                }
                                            } else {
                                                view.setVisibility(View.GONE);
                                            }
                                        });
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

                    if (window != null) {
                        String match = checkBlock(window.getDecorView(), null, true);
                        if (match != null) {
                            log(4, TAG, "Blocked Dialog in " + pkgName + ". " + match);
                            try {
                                if (dialog.isShowing()) {
                                    dialog.dismiss();
                                }
                            } catch (Exception e) {
                                // Already dismissed or not attached
                            }
                        } else {
                            window.getDecorView().setAlpha(1f);
                        }
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

        return findBlockedText(view, getPatterns());
    }

    private boolean isWholeWordMatch(String content, String pattern) {
        if (content == null || pattern == null) return false;
        try {
            return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(pattern) + "\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(content).find();
        } catch (Exception e) {
            return content.toLowerCase().contains(pattern.toLowerCase());
        }
    }

    private String findBlockedText(View view, Set<String> patterns) {
        if (view == null) return null;

        // 1. Virtual tree search (Compose, Flutter)
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null) {
            for (String pattern : patterns) {
                try {
                    // findAccessibilityNodeInfosByText is often substring-based, so we filter results
                    List<AccessibilityNodeInfo> nodes = provider.findAccessibilityNodeInfosByText(pattern, -1);
                    if (nodes != null) {
                        String matchFound = null;
                        for (AccessibilityNodeInfo node : nodes) {
                            if (matchFound == null) {
                                if (node.getText() != null && isWholeWordMatch(node.getText().toString(), pattern)) {
                                    matchFound = "Virtual tree match: " + pattern;
                                } else if (node.getContentDescription() != null && isWholeWordMatch(node.getContentDescription().toString(), pattern)) {
                                    matchFound = "Virtual tree match (desc): " + pattern;
                                }
                            }
                            node.recycle();
                        }
                        if (matchFound != null) return matchFound;
                    }
                } catch (Throwable ignored) {}
            }
        }

        // 2. Fallback to recursive scan (covers TextViews, Buttons, etc. with whole-word matching)
        return findBlockedTextRecursive(view, patterns);
    }

    private String findBlockedTextRecursive(View view, Set<String> patterns) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                for (String p : patterns) {
                    if (isWholeWordMatch(text.toString(), p)) return "Recursive text match: " + p;
                }
            }
        }

        CharSequence desc = view.getContentDescription();
        if (desc != null) {
            for (String p : patterns) {
                if (isWholeWordMatch(desc.toString(), p)) return "Recursive description match: " + p;
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

    private Set<String> getPatterns() {
        try {
            SharedPreferences prefs = getRemotePreferences(PREFS_NAME);
            if (prefs != null) {
                Set<String> customPatterns = prefs.getStringSet(KEY_PATTERNS, DEFAULT_PATTERNS);
                return customPatterns;
            }
        } catch (Exception e) {
            log(4, TAG, "Failed to read prefs: " + e.getMessage());
        }
        return DEFAULT_PATTERNS;
    }
}
