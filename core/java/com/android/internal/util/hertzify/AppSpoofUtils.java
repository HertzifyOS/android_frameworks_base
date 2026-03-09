/*
 * Copyright (C) 2026 HertzifyOS
 * Copyright (C) 2020 The Pixel Experience Project
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.hertzify;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public final class AppSpoofUtils {

    private static final String TAG = "AppSpoofUtils";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final Set<String> ALLOWED_KEYS = new HashSet<>(Arrays.asList(
            "BRAND", "DEVICE", "MANUFACTURER", "MODEL", "FINGERPRINT", "PRODUCT"
    ));

    private AppSpoofUtils() {}

    public static void setProps(Context context) {
        final String packageName = context.getPackageName();
        final String processName = Application.getProcessName();
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(processName)) {
            Log.e(TAG, "Null package or process name");
            return;
        }
        if (context.getResources() == null) {
            Log.e(TAG, "Null resources");
            return;
        }
        if (android.os.Process.isIsolated()) {
            if (DEBUG) Log.d(TAG, "Skipping setProps in isolated process");
            return;
        }
        if (Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.APP_SPOOF_ENABLED, 1) != 1) return;

        try {
            String json = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.APP_SPOOF_DATA);
            if (TextUtils.isEmpty(json)) return;
            if (!json.contains(packageName)) return;

            JSONObject root = new JSONObject(json);
            if (!root.has(packageName)) return;
            JSONObject propsObj = root.getJSONObject(packageName);

            if (DEBUG) Log.d(TAG, "Spoofing Build for: " + packageName);
            for (Iterator<String> keyIt = propsObj.keys(); keyIt.hasNext(); ) {
                String key = keyIt.next();
                if (!ALLOWED_KEYS.contains(key)) continue;
                String value = propsObj.optString(key, "").trim();
                if (!value.isEmpty()) setPropValue(key, value);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load app spoof data", e);
        }
    }

    private static void setPropValue(String key, String value) {
        try {
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, field.getType().equals(Integer.TYPE)
                    ? Integer.parseInt(value) : value);
            field.setAccessible(false);
            if (DEBUG) Log.d(TAG, "  Set Build." + key + " = " + value);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set Build." + key, e);
        }
    }
}