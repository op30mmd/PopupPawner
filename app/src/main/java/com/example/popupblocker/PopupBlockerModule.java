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
import android.widget.PopupWindow;
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
    private static final String KEY_ENABLED = "module_enabled";
    private static final String KEY_PATTERNS = "blocked_patterns";
    private static final String KEY_WHITELIST = "whitelist_patterns";
    private static final String KEY_AGGRESSIVE = "aggressive_mode";
    private static final String KEY_DIAGNOSTICS = "verbose_diagnostics";
    private static final String KEY_CONFIG_VERSION = "config_version";

    private static final Set<String> DEFAULT_PATTERNS = new HashSet<>(Arrays.asList(
            "update", "rating", "survey", "ad", "commercial", "promotion"
    ));
    private static final Set<String> DEFAULT_WHITELIST = new HashSet<>(Arrays.asList(
            "save", "login", "search"
    ));

    /**
     * No-argument constructor required by LibXposed (v101.0.1) for reflective instantiation.
     * Note: A 2-argument constructor (XposedInterface, ModuleLoadedParam) is not supported
     * by the superclass in this API version and causes compilation errors.
     */
    public PopupBlockerModule() {
        super();
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        log(4, TAG, "Module loaded in " + param.getProcessName());
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

            hookDialogShow(classLoader, pkgName);
            hookPopupWindow(classLoader, pkgName);
            hookRemoveViewSafety(classLoader);

        } catch (Exception e) {
            log(4, TAG, "Error in onPackageLoaded: " + e.getMessage());
        }
    }

    private void hookDialogShow(ClassLoader cl, String pkgName) {
        try {
            Class<?> dialogClass = cl.loadClass("android.app.Dialog");
            Method show = dialogClass.getDeclaredMethod("show");
            hook(show).intercept(chain -> {
                Dialog dialog = (Dialog) chain.getThisObject();
                Window window = dialog.getWindow();
                if (window != null) window.getDecorView().setAlpha(0f);

                Object result = chain.proceed();

                if (window != null) {
                    View decor = window.getDecorView();
                    if (hasEditText(decor)) {
                        decor.setAlpha(1f);
                        return result;
                    }

                    String match = checkBlock(decor, null, true);
                    if (match == null && isShizukuAboutDialog(decor)) {
                        match = "Shizuku About dialog signature";
                    }

                    if (match != null) {
                        log(4, TAG, "Blocked Dialog in " + pkgName + ". " + match);
                        try { if (dialog.isShowing()) dialog.dismiss(); }
                        catch (IllegalArgumentException ignored) {}
                    } else {
                        decor.setAlpha(1f);
                    }
                }
                return result;
            });
        } catch (Exception e) {
            log(4, TAG, "hookDialogShow failed: " + e.getMessage());
        }
    }

    private void hookPopupWindow(ClassLoader cl, String pkgName) {
        try {
            Class<?> pw = cl.loadClass("android.widget.PopupWindow");
            for (String m : new String[]{"showAsDropDown", "showAtLocation"}) {
                for (Method method : pw.getDeclaredMethods()) {
                    if (!method.getName().equals(m)) continue;
                    hook(method).intercept(chain -> {
                        PopupWindow popup = (PopupWindow) chain.getThisObject();
                        View content = popup.getContentView();
                        if (content != null && !hasEditText(content)) {
                            String match = checkBlock(content, null, false);
                            if (match != null) {
                                log(4, TAG, "Blocked PopupWindow in " + pkgName + ". " + match);
                                return null;
                            }
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Exception e) {
            log(4, TAG, "hookPopupWindow failed: " + e.getMessage());
        }
    }

    private void hookRemoveViewSafety(ClassLoader cl) {
        try {
            Class<?> wmi = cl.loadClass("android.view.WindowManagerImpl");
            for (String m : new String[]{"removeView", "removeViewImmediate"}) {
                Method rm = wmi.getDeclaredMethod(m, View.class);
                hook(rm).intercept(chain -> {
                    try { return chain.proceed(); }
                    catch (IllegalArgumentException e) {
                        if (e.getMessage() != null && e.getMessage().contains("not attached to window manager"))
                            return null;
                        throw e;
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    private boolean hasEditText(View view) {
        if (view instanceof android.widget.EditText) return true;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++)
                if (hasEditText(g.getChildAt(i))) return true;
        }
        return false;
    }

    private String checkBlock(View view, WindowManager.LayoutParams params, boolean isExplicitDialog) {
        if (view == null) return null;
        SharedPreferences prefs = getRemotePreferences(PREFS_NAME);
        reloadPrefs(prefs);

        if (!prefs.getBoolean(KEY_ENABLED, true)) return null;

        boolean aggressive = prefs.getBoolean(KEY_AGGRESSIVE, false);
        Set<String> patterns = getPatterns(prefs, KEY_PATTERNS, DEFAULT_PATTERNS);
        Set<String> whitelist = getPatterns(prefs, KEY_WHITELIST, DEFAULT_WHITELIST);
        long configVersion = prefs.getLong(KEY_CONFIG_VERSION, -1);

        log(4, TAG, "Scanning view. Patterns: " + patterns.size() + ", Whitelist: " + whitelist.size() + ", Aggressive: " + aggressive + ", configVersion=" + configVersion);

        // 1. Whitelist Check (Highest priority)
        String whiteMatch = findBlockedText(view, whitelist);
        if (whiteMatch != null) {
            log(4, TAG, "Allowing view due to whitelist match: " + whiteMatch);
            return null;
        }

        // 2. Aggressive Check
        if (aggressive) {
            if (isExplicitDialog) {
                return "Aggressive mode (Dialog)";
            }
            if (params != null && params.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW
                    && params.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) {
                return "Aggressive mode (Window Type " + params.type + ")";
            }
        }

        // 3. Pattern Check
        return findBlockedText(view, patterns);
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
                        SharedPreferences prefs = getRemotePreferences(PREFS_NAME);
                        reloadPrefs(prefs);
                        if (!prefs.getBoolean(KEY_DIAGNOSTICS, false)) {
                            return chain.proceed();
                        }

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
                                + " configVersion=" + prefs.getLong(KEY_CONFIG_VERSION, -1)
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

    private void reloadPrefs(SharedPreferences prefs) {
        try {
            // Reflective check for XSharedPreferences to pick up on-disk changes
            Method reload = prefs.getClass().getMethod("reload");
            reload.invoke(prefs);
        } catch (Exception ignored) {}
    }

    private Set<String> getPatterns(SharedPreferences prefs, String key, Set<String> defaults) {
        try {
            if (prefs != null && prefs.contains(key)) {
                Set<String> p = prefs.getStringSet(key, null);
                return (p != null) ? new HashSet<>(p) : new HashSet<>();
            }
        } catch (Exception e) {
            log(4, TAG, "Failed to read patterns for " + key + ": " + e.getMessage());
        }
        return defaults;
    }
}
