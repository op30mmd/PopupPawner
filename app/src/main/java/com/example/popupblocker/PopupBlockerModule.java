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
        log(4, TAG, "Module instantiated in " + param.getProcessName());
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        log(4, TAG, "onModuleLoaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        super.onPackageLoaded(param);
        String pkgName = param.getPackageName();

        if (pkgName.equals("com.example.popupblocker")) {
            return;
        }
        log(4, TAG, "Module active in process: " + pkgName);

        try {
            ClassLoader classLoader = param.getDefaultClassLoader();
            installWindowDiagnostics(classLoader, pkgName);

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
                        View decor = window.getDecorView();
                        String match = checkBlock(decor, null, true);
                        if (match == null && isShizukuAboutDialog(decor)) {
                            match = "Shizuku About dialog signature";
                        }

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
            // In aggressive mode, block all sub-windows but still exclude main Activities
            if (params != null && params.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW
                    && params.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) {
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

    private boolean isShizukuAboutDialog(View decor) {
        String text = dumpText(decor, new StringBuilder(), 0).toLowerCase();
        if (text.isEmpty()) return false;

        boolean hasGithub  = text.contains("github");
        boolean hasVersion = java.util.regex.Pattern
                .compile("\\d+\\.\\d+\\.\\d+\\.r\\d+")   // e.g. 13.5.4.r1049
                .matcher(text).find();
        boolean hasSource  = text.contains("source code");

        // require >=2 independent signals so a normal dialog can't trip it
        int signals = (hasGithub ? 1 : 0) + (hasVersion ? 1 : 0) + (hasSource ? 1 : 0);
        return signals >= 2;
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

    private void installWindowDiagnostics(ClassLoader cl, String pkg) {
        try {
            Class<?> wmg = cl.loadClass("android.view.WindowManagerGlobal");
            for (Method m : wmg.getDeclaredMethods()) {
                if (!m.getName().equals("addView")) continue;
                hook(m).intercept(chain -> {
                    try {
                        View v = null;
                        int type = -1;
                        for (int i = 0; i < chain.getArgs().size(); i++) {
                            Object a = chain.getArg(i);
                            if (a instanceof View) v = (View) a;
                            if (a instanceof WindowManager.LayoutParams)
                                type = ((WindowManager.LayoutParams) a).type;
                        }
                        log(4, TAG, "[WIN] pkg=" + pkg
                                + " type=" + type
                                + " view=" + (v == null ? "null" : v.getClass().getName())
                                + " text=" + dumpText(v, new StringBuilder(), 0));
                        // The caller chain is the answer: Dialog.show? PopupWindow? DialogFragment? custom?
                        StackTraceElement[] st = new Throwable().getStackTrace();
                        for (int i = 0; i < Math.min(st.length, 25); i++) {
                            log(4, TAG, "    at " + st[i]);
                        }
                    } catch (Throwable t) {
                        log(4, TAG, "diag err: " + t);
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            log(4, TAG, "diag install failed: " + t);
        }
    }

    private String dumpText(View v, StringBuilder sb, int depth) {
        if (v == null || depth > 12 || sb.length() > 300) return sb.toString();
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) sb.append('|').append(t);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpText(g.getChildAt(i), sb, depth + 1);
        }
        return sb.toString();
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
