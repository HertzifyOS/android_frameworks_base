package com.android.internal.util.hertzify;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class AppShieldUtils {

    public static final String KEY_HIDE_APPLIST = "appshield_hide_applist";
    public static final String KEY_HIDE_LAUNCHER = "appshield_hide_launcher";
    public static final String KEY_HIDE_DEVSTATUS = "appshield_hide_devstatus";
    public static final String KEY_DETACHED = "appshield_detached";

    private static final Set<String> DEV_SETTINGS_TO_HIDE = Set.of(
            Settings.Global.ADB_ENABLED,
            Settings.Global.ADB_WIFI_ENABLED,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
    );

    private static final Set<String> EMPTY = Collections.emptySet();

    private static volatile Set<String> sHidden = EMPTY;
    private static volatile Set<String> sLauncherHidden = EMPTY;
    private static volatile Set<String> sDevHidden = EMPTY;
    private static volatile Set<String> sDetached = EMPTY;
    private static volatile boolean sObserverRegistered = false;

    private AppShieldUtils() {}

    private static boolean isBootCompleted() {
        return SystemProperties.getBoolean("sys.boot_completed", false);
    }

    public static void ensureObserver(ContentResolver cr) {
        if (sObserverRegistered || cr == null) return;
        synchronized (AppShieldUtils.class) {
            if (sObserverRegistered) return;
            sHidden = readCsv(cr, KEY_HIDE_APPLIST);
            sLauncherHidden = readCsv(cr, KEY_HIDE_LAUNCHER);
            sDevHidden = readCsv(cr, KEY_HIDE_DEVSTATUS);
            sDetached = readCsv(cr, KEY_DETACHED);

            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    sHidden = readCsv(cr, KEY_HIDE_APPLIST);
                    sLauncherHidden = readCsv(cr, KEY_HIDE_LAUNCHER);
                    sDevHidden = readCsv(cr, KEY_HIDE_DEVSTATUS);
                    sDetached = readCsv(cr, KEY_DETACHED);
                }
            };
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_HIDE_APPLIST), true, observer, UserHandle.USER_ALL);
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_HIDE_LAUNCHER), true, observer, UserHandle.USER_ALL);
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_HIDE_DEVSTATUS), true, observer, UserHandle.USER_ALL);
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_DETACHED), true, observer, UserHandle.USER_ALL);
            sObserverRegistered = true;
        }
    }

    private static Set<String> readCsv(ContentResolver cr, String key) {
        String raw = Settings.Secure.getStringForUser(cr, key, UserHandle.USER_SYSTEM);
        if (TextUtils.isEmpty(raw)) return EMPTY;
        String[] parts = raw.split(",");
        Set<String> out = new HashSet<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) out.add(p);
        }
        return out.isEmpty() ? EMPTY : out;
    }

    private static synchronized void mutate(ContentResolver cr, String key, String packageName, boolean add) {
        if (cr == null || TextUtils.isEmpty(packageName)) return;
        Set<String> fresh = new HashSet<>(readCsv(cr, key));
        boolean changed = add ? fresh.add(packageName) : fresh.remove(packageName);
        if (!changed) return;
        Settings.Secure.putStringForUser(cr, key, TextUtils.join(",", fresh), UserHandle.USER_SYSTEM);
        switch (key) {
            case KEY_HIDE_APPLIST -> sHidden = fresh.isEmpty() ? EMPTY : fresh;
            case KEY_HIDE_LAUNCHER -> sLauncherHidden = fresh.isEmpty() ? EMPTY : fresh;
            case KEY_HIDE_DEVSTATUS -> sDevHidden = fresh.isEmpty() ? EMPTY : fresh;
            case KEY_DETACHED -> sDetached = fresh.isEmpty() ? EMPTY : fresh;
        }
    }

    public static boolean isAppHidden(ContentResolver cr, String packageName) {
        if (TextUtils.isEmpty(packageName) || !isBootCompleted()) return false;
        ensureObserver(cr);
        return sHidden.contains(packageName);
    }

    public static boolean isHiddenFromLauncher(ContentResolver cr, String packageName) {
        if (TextUtils.isEmpty(packageName) || !isBootCompleted()) return false;
        ensureObserver(cr);
        return sLauncherHidden.contains(packageName);
    }

    public static void setAppHidden(ContentResolver cr, String packageName, boolean hidden) {
        mutate(cr, KEY_HIDE_APPLIST, packageName, hidden);
    }

    public static void setHiddenFromLauncher(ContentResolver cr, String packageName, boolean hidden) {
        mutate(cr, KEY_HIDE_LAUNCHER, packageName, hidden);
    }

    public static boolean shouldHideDevStatus(ContentResolver cr, String callingPackage, String settingName) {
        if (callingPackage == null || settingName == null || !isBootCompleted()) return false;
        if (!DEV_SETTINGS_TO_HIDE.contains(settingName)) return false;
        ensureObserver(cr);
        return sDevHidden.contains(callingPackage);
    }

    public static void setDevStatusHidden(ContentResolver cr, String packageName, boolean hidden) {
        mutate(cr, KEY_HIDE_DEVSTATUS, packageName, hidden);
    }

    public static boolean isDetached(ContentResolver cr, String packageName) {
        if (TextUtils.isEmpty(packageName) || !isBootCompleted()) return false;
        ensureObserver(cr);
        return sDetached.contains(packageName);
    }

    public static void setDetached(ContentResolver cr, String packageName, boolean detached) {
        mutate(cr, KEY_DETACHED, packageName, detached);
    }

    public static void removeAllForPackage(ContentResolver cr, String packageName) {
        if (cr == null || TextUtils.isEmpty(packageName)) return;
        mutate(cr, KEY_HIDE_APPLIST, packageName, false);
        mutate(cr, KEY_HIDE_LAUNCHER, packageName, false);
        mutate(cr, KEY_HIDE_DEVSTATUS, packageName, false);
        mutate(cr, KEY_DETACHED, packageName, false);
    }
}