package com.android.internal.util.hertzify;

import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AppShieldUtils {

    public static final String KEY_HIDE_APPLIST = "appshield_hide_applist";
    public static final String KEY_HIDE_LAUNCHER = "appshield_hide_launcher";
    public static final String KEY_HIDE_DEVSTATUS = "appshield_hide_devstatus";
    public static final String KEY_DETACHED = "appshield_detached";

    public static final String PLAY_STORE_PACKAGE = "com.android.vending";

    private static final Set<String> DEV_SETTINGS_TO_HIDE = Set.of(
            Settings.Global.ADB_ENABLED,
            Settings.Global.ADB_WIFI_ENABLED,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
    );

    private static final Set<String> EMPTY = Collections.emptySet();

    private static final Map<Integer, Set<String>> sHiddenByUser = new HashMap<>();
    private static final Map<Integer, Set<String>> sLauncherHiddenByUser = new HashMap<>();
    private static final Map<Integer, Set<String>> sDevHiddenByUser = new HashMap<>();
    private static final Map<Integer, Set<String>> sDetachedByUser = new HashMap<>();
    private static volatile boolean sObserverRegistered = false;

    private AppShieldUtils() {}

    private static boolean isBootCompleted() {
        return SystemProperties.getBoolean("sys.boot_completed", false);
    }

    public static void ensureObserver(ContentResolver cr) {
        if (sObserverRegistered || cr == null) return;
        synchronized (AppShieldUtils.class) {
            if (sObserverRegistered) return;

            final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    invalidateAll();
                }

                @Override
                public void onChange(boolean selfChange, Uri uri, @UserIdInt int userId) {
                    invalidateUser(userId);
                }
            };

            if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID) {
                for (String key : new String[]{KEY_HIDE_APPLIST, KEY_HIDE_LAUNCHER,
                        KEY_HIDE_DEVSTATUS, KEY_DETACHED}) {
                    cr.registerContentObserver(Settings.Secure.getUriFor(key), true, observer,
                            UserHandle.USER_ALL);
                }
            } else {
                for (String key : new String[]{KEY_HIDE_APPLIST, KEY_HIDE_LAUNCHER,
                        KEY_HIDE_DEVSTATUS, KEY_DETACHED}) {
                    cr.registerContentObserver(Settings.Secure.getUriFor(key), true, observer);
                }
            }

            sObserverRegistered = true;
        }
    }

    private static synchronized void invalidateAll() {
        sHiddenByUser.clear();
        sLauncherHiddenByUser.clear();
        sDevHiddenByUser.clear();
        sDetachedByUser.clear();
    }

    private static synchronized void invalidateUser(@UserIdInt int userId) {
        sHiddenByUser.remove(userId);
        sLauncherHiddenByUser.remove(userId);
        sDevHiddenByUser.remove(userId);
        sDetachedByUser.remove(userId);
    }

    private static Set<String> readCsv(ContentResolver cr, String key, @UserIdInt int userId) {
        String raw = Settings.Secure.getStringForUser(cr, key, userId);
        if (TextUtils.isEmpty(raw)) return EMPTY;
        String[] parts = raw.split(",");
        Set<String> out = new HashSet<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) out.add(p);
        }
        return out.isEmpty() ? EMPTY : out;
    }

    private static synchronized Set<String> cachedSet(Map<Integer, Set<String>> cache,
            ContentResolver cr, String key, @UserIdInt int userId) {
        Set<String> cached = cache.get(userId);
        if (cached != null) return cached;
        Set<String> fresh = readCsv(cr, key, userId);
        cache.put(userId, fresh);
        return fresh;
    }

    private static synchronized void mutate(ContentResolver cr, String key, String packageName,
            boolean add, @UserIdInt int userId) {
        if (cr == null || TextUtils.isEmpty(packageName)) return;
        ensureObserver(cr);
        Set<String> fresh = new HashSet<>(readCsv(cr, key, userId));
        boolean changed = add ? fresh.add(packageName) : fresh.remove(packageName);
        if (!changed) return;
        Settings.Secure.putStringForUser(cr, key, TextUtils.join(",", fresh), userId);
        Set<String> stored = fresh.isEmpty() ? EMPTY : fresh;
        switch (key) {
            case KEY_HIDE_APPLIST -> sHiddenByUser.put(userId, stored);
            case KEY_HIDE_LAUNCHER -> sLauncherHiddenByUser.put(userId, stored);
            case KEY_HIDE_DEVSTATUS -> sDevHiddenByUser.put(userId, stored);
            case KEY_DETACHED -> sDetachedByUser.put(userId, stored);
        }
    }

    public static boolean isAppHidden(ContentResolver cr, String packageName,
            @UserIdInt int userId) {
        if (TextUtils.isEmpty(packageName) || !isBootCompleted()) return false;
        ensureObserver(cr);
        return cachedSet(sHiddenByUser, cr, KEY_HIDE_APPLIST, userId).contains(packageName);
    }

    public static boolean isHiddenFromLauncher(ContentResolver cr, String packageName,
            @UserIdInt int userId) {
        if (TextUtils.isEmpty(packageName) || !isBootCompleted()) return false;
        ensureObserver(cr);
        return cachedSet(sLauncherHiddenByUser, cr, KEY_HIDE_LAUNCHER, userId)
                .contains(packageName);
    }

    public static void setAppHidden(ContentResolver cr, String packageName, boolean hidden,
            @UserIdInt int userId) {
        mutate(cr, KEY_HIDE_APPLIST, packageName, hidden, userId);
    }

    public static void setHiddenFromLauncher(ContentResolver cr, String packageName,
            boolean hidden, @UserIdInt int userId) {
        mutate(cr, KEY_HIDE_LAUNCHER, packageName, hidden, userId);
    }

    public static boolean shouldHideDevStatus(ContentResolver cr, String callingPackage,
            String settingName, @UserIdInt int userId) {
        if (callingPackage == null || settingName == null
                || !DEV_SETTINGS_TO_HIDE.contains(settingName)) {
            return false;
        }
        if (!isBootCompleted()) return false;
        ensureObserver(cr);
        return cachedSet(sDevHiddenByUser, cr, KEY_HIDE_DEVSTATUS, userId)
                .contains(callingPackage);
    }

    public static void setDevStatusHidden(ContentResolver cr, String packageName, boolean hidden,
            @UserIdInt int userId) {
        mutate(cr, KEY_HIDE_DEVSTATUS, packageName, hidden, userId);
    }

    public static boolean isDetached(ContentResolver cr, String packageName,
            @UserIdInt int userId) {
        if (TextUtils.isEmpty(packageName) || !isBootCompleted()) return false;
        ensureObserver(cr);
        return cachedSet(sDetachedByUser, cr, KEY_DETACHED, userId).contains(packageName);
    }

    public static void setDetached(ContentResolver cr, String packageName, boolean detached,
            @UserIdInt int userId) {
        mutate(cr, KEY_DETACHED, packageName, detached, userId);
    }

    public static void removeAllForPackage(ContentResolver cr, String packageName,
            @UserIdInt int userId) {
        if (cr == null || TextUtils.isEmpty(packageName)) return;
        mutate(cr, KEY_HIDE_APPLIST, packageName, false, userId);
        mutate(cr, KEY_HIDE_LAUNCHER, packageName, false, userId);
        mutate(cr, KEY_HIDE_DEVSTATUS, packageName, false, userId);
        mutate(cr, KEY_DETACHED, packageName, false, userId);
    }
}
