package dev.user.fullscreen;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

import cat.narezany.margyt.plugin.MargyPlugin;

/**
 * Fullscreen 1.1 by Seust: keeps TikTok fullscreen by hiding the status bar.
 *
 * Only plain framework APIs are used (no AndroidX). Every hook is wrapped so
 * nothing can escape: the loader drops a plugin whose hook throws.
 *
 * Whatever we change on a window is remembered the first time and put back
 * exactly when the toggle or the plugin is switched off, on every activity we
 * touched, not only the one on screen.
 *
 * Threading: the activity hooks run on the main thread, and everything else
 * that touches a window or the two maps below is posted to it.
 *
 * Build:  python3 -m margyt.plugin path/to/margyt-fullscreen
 */
public final class FullscreenPlugin extends MargyPlugin {

    private static final String KEY_HIDE = "hide_status_bar";
    private static final String KEY_CUTOUT = "fill_cutout";

    /** The two legacy systemUiVisibility bits that we own. */
    private static final int LEGACY_MASK =
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** Activities we changed, with what they looked like before (main thread). */
    private final WeakHashMap<Activity, Saved> applied = new WeakHashMap<>();
    /** Focus listeners we added, one per activity (main thread). */
    private final WeakHashMap<Activity, FocusWatcher> watched = new WeakHashMap<>();

    /** Set by onStop: from then on nothing may touch a window. */
    private volatile boolean stopped;
    private boolean errorLogged;

    // ---------------------------------------------------------------- hooks

    @Override
    public void onStart(Context context) {
        stopped = false;
        try {
            margyt().addSettingsSection("Fullscreen");
            margyt().addSettingsToggle("Hide status bar",
                    "Fullscreen on every screen. Swipe down from the top to peek.",
                    KEY_HIDE, true);
            margyt().addSettingsToggle("Fill the camera cutout",
                    "Draw into the notch / punch-hole area (Android 9+)",
                    KEY_CUTOUT, true);
        } catch (Throwable t) {
            logFail("onStart", t);
        }
        // Switched on while TikTok is already running: catch up with the
        // screen that is up. At app start there is none yet, and that is fine.
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity now = margyt().screen();
                    if (now != null) {
                        syncOne(now);
                        watchFocus(now);
                    }
                } catch (Throwable t) {
                    logFail("onStart/catch-up", t);
                }
            }
        });
    }

    @Override
    public void onActivityCreated(Activity activity) {
        // Too early to touch the decor view or the insets controller: that
        // would install the decor and could break the activity's own
        // requestFeature() calls. Window flags are safe at any time. The
        // activity is recorded right away so a switch-off before the first
        // resume still undoes the flag.
        try {
            if (!stopped && alive(activity) && hideWanted()) {
                Window window = activity.getWindow();
                remember(activity, window);
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
        } catch (Throwable t) {
            logFail("onActivityCreated", t);
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        // Two separate guards: a failure in one must not skip the other.
        try {
            syncOne(activity);
        } catch (Throwable t) {
            logFail("onActivityResumed/sync", t);
        }
        try {
            watchFocus(activity);
        } catch (Throwable t) {
            logFail("onActivityResumed/watch", t);
        }
    }

    /**
     * API 4: the mod menu saved a setting. On an API 3 host this is never
     * called and the new value is picked up the next time a screen resumes.
     * No @Override on purpose, so it compiles whether or not the base class
     * declares the hook.
     */
    public void onSettingsChanged(String key) {
        try {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (stopped) return;
                        if (hideWanted()) {
                            syncOne(margyt().screen());
                        } else {
                            restoreAll();
                        }
                    } catch (Throwable t) {
                        logFail("onSettingsChanged", t);
                    }
                }
            });
        } catch (Throwable t) {
            logFail("onSettingsChanged", t);
        }
    }

    @Override
    public void onStop() {
        // The plugin was switched off. The flag goes up at once so that no
        // listener can hide the bar again; the actual undoing happens on the
        // main thread.
        stopped = true;
        try {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        restoreAll();
                    } catch (Throwable t) {
                        logFail("onStop/restore", t);
                    }
                    try {
                        unwatchAll();
                    } catch (Throwable t) {
                        logFail("onStop/unwatch", t);
                    }
                    applied.clear();
                    watched.clear();
                    errorLogged = false;
                }
            });
        } catch (Throwable t) {
            logFail("onStop", t);
        }
    }

    // ----------------------------------------------------------------- work

    private boolean hideWanted() {
        return margyt().getBoolean(KEY_HIDE, true);
    }

    private static boolean alive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed() && a.getWindow() != null;
    }

    /** Hide when the toggle is on; give back what we took when it is off. */
    private void syncOne(Activity activity) {
        if (stopped || !alive(activity)) return;
        if (hideWanted()) {
            applyHide(activity);
        } else if (applied.containsKey(activity)) {
            applyRestore(activity);
        }
    }

    /** The record for this activity; created, with the window's own flag, on first sight. */
    private Saved remember(Activity activity, Window window) {
        Saved s = applied.get(activity);
        if (s == null) {
            s = new Saved();
            s.hadFullscreen =
                    (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
            applied.put(activity, s);
        }
        return s;
    }

    private void applyHide(Activity activity) {
        Window window = activity.getWindow();
        Saved s = remember(activity, window);

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Camera cutout: on while its toggle is on, put back when it goes off.
        if (Build.VERSION.SDK_INT >= 28) {
            if (margyt().getBoolean(KEY_CUTOUT, true)) {
                WindowManager.LayoutParams lp = window.getAttributes();
                int shortEdges = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                if (lp.layoutInDisplayCutoutMode != shortEdges) {
                    if (!s.cutoutChanged) {
                        s.cutoutOriginal = lp.layoutInDisplayCutoutMode;
                        s.cutoutChanged = true;
                    }
                    lp.layoutInDisplayCutoutMode = shortEdges;
                    window.setAttributes(lp);
                }
            } else {
                revertCutout(window, s);
            }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = window.getInsetsController();
            if (c != null) {
                if (!s.decorSaved) {
                    s.behavior = c.getSystemBarsBehavior();
                    s.decorSaved = true;
                }
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.statusBars());
            }
        } else {
            View decor = window.peekDecorView();
            if (decor != null) {
                int now = decor.getSystemUiVisibility();
                if (!s.decorSaved) {
                    s.uiVisibility = now;
                    s.decorSaved = true;
                }
                decor.setSystemUiVisibility(now | LEGACY_MASK);
            }
        }
    }

    private void applyRestore(Activity activity) {
        Saved s = applied.remove(activity);
        if (s == null || !alive(activity)) return;
        Window window = activity.getWindow();

        if (!s.hadFullscreen) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            revertCutout(window, s);
        }

        if (Build.VERSION.SDK_INT >= 30) {
            if (s.decorSaved) {
                WindowInsetsController c = window.getInsetsController();
                if (c != null) {
                    c.setSystemBarsBehavior(s.behavior);
                    // Visible before us unless the window itself asked for fullscreen.
                    if (!s.hadFullscreen) c.show(WindowInsets.Type.statusBars());
                }
            }
        } else if (s.decorSaved) {
            View decor = window.peekDecorView();
            if (decor != null) {
                // Only our two bits go back; anything TikTok set since stays.
                decor.setSystemUiVisibility(
                        (decor.getSystemUiVisibility() & ~LEGACY_MASK)
                                | (s.uiVisibility & LEGACY_MASK));
            }
        }
    }

    private static void revertCutout(Window window, Saved s) {
        if (!s.cutoutChanged) return;
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.layoutInDisplayCutoutMode = s.cutoutOriginal;
        window.setAttributes(lp);
        s.cutoutChanged = false;
    }

    /** Undo everything on every activity we touched, one failure not stopping the rest. */
    private void restoreAll() {
        for (Activity a : new ArrayList<>(applied.keySet())) {
            try {
                applyRestore(a);
            } catch (Throwable t) {
                logFail("restore", t);
            }
        }
    }

    /**
     * The bar comes back after a dialog, the keyboard or the notification
     * shade. Re-hide it whenever the window gets focus again. One listener per
     * activity, holding the activity only weakly.
     */
    private void watchFocus(Activity activity) {
        if (stopped || !alive(activity) || watched.containsKey(activity)) return;
        View decor = activity.getWindow().peekDecorView();
        if (decor == null) return;
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) return;
        FocusWatcher watcher = new FocusWatcher(this, activity);
        observer.addOnWindowFocusChangeListener(watcher);
        watched.put(activity, watcher);
    }

    /** Take our listeners off; a listener that cannot be reached is made inert. */
    private void unwatchAll() {
        for (Activity a : new ArrayList<>(watched.keySet())) {
            FocusWatcher watcher = watched.get(a);
            if (watcher == null) continue;
            watcher.dead = true;
            try {
                Window window = a.getWindow();
                View decor = window == null ? null : window.peekDecorView();
                ViewTreeObserver observer = decor == null ? null : decor.getViewTreeObserver();
                if (observer != null && observer.isAlive()) {
                    observer.removeOnWindowFocusChangeListener(watcher);
                }
            } catch (Throwable t) {
                logFail("unwatch", t);
            }
        }
    }

    private void logFail(String where, Throwable t) {
        if (errorLogged) return; // one line is enough, no diary spam
        errorLogged = true;
        try {
            margyt().log("fullscreen: " + where + " failed: " + t);
        } catch (Throwable ignored) {
            // nothing left to do
        }
    }

    // -------------------------------------------------------------- helpers

    /** What a window looked like before we touched it. */
    private static final class Saved {
        boolean hadFullscreen;
        boolean cutoutChanged;
        int cutoutOriginal;
        boolean decorSaved;
        int behavior;       // API 30+: WindowInsetsController behaviour
        int uiVisibility;   // below API 30: systemUiVisibility
    }

    private static final class FocusWatcher
            implements ViewTreeObserver.OnWindowFocusChangeListener {
        private final WeakReference<FullscreenPlugin> plugin;
        private final WeakReference<Activity> activity;
        volatile boolean dead;

        FocusWatcher(FullscreenPlugin plugin, Activity activity) {
            this.plugin = new WeakReference<>(plugin);
            this.activity = new WeakReference<>(activity);
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (!hasFocus || dead) return;
            FullscreenPlugin p = plugin.get();
            Activity a = activity.get();
            if (p == null || a == null || p.stopped) return;
            try {
                p.syncOne(a);
            } catch (Throwable t) {
                p.logFail("focus", t);
            }
        }
    }
}
