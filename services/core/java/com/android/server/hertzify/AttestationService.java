/*
 * Copyright (C) 2024 The LeafOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.android.server.hertzify;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.util.Log;

import com.android.server.SystemService;
import com.android.internal.util.hertzify.HertzifyUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AttestationService extends SystemService {

    private static final String TAG = AttestationService.class.getSimpleName();

    private static final long INITIAL_DELAY = 0;
    private static final long INTERVAL = 8; // hours
    private static final long MIN_REFETCH_MS = TimeUnit.MINUTES.toMillis(5);
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final String mApiUrl;
    private final ScheduledExecutorService mScheduler;
    private final ConnectivityManager mConnectivityManager;
    private final FetchGmsCertifiedProps mFetchRunnable;

    private final AtomicBoolean mFetchScheduledByNetwork = new AtomicBoolean(false);
    private volatile long mLastSuccessFetchMs = 0L;
    private boolean mPendingUpdate = false;

    public AttestationService(Context context) {
        super(context);
        mContext = context;
        mApiUrl = mContext.getString(
                com.android.internal.R.string.config_attestationServiceApiUrl);
        mFetchRunnable = new FetchGmsCertifiedProps();
        mScheduler = Executors.newSingleThreadScheduledExecutor();
        mConnectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (HertzifyUtils.isPackageInstalled(mContext, "com.google.android.gms")
                && phase == PHASE_BOOT_COMPLETED) {
            Log.i(TAG, "Scheduling the service");
            mScheduler.scheduleAtFixedRate(
                    mFetchRunnable, INITIAL_DELAY, INTERVAL, TimeUnit.HOURS);
        }
    }

    private String fetchProps() {
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URI(mApiUrl).toURL();
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(10000);
            urlConnection.setReadTimeout(10000);
            urlConnection.setRequestProperty("User-Agent", "AttestationService/1.0");

            int code = urlConnection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Bad HTTP status: " + code);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(urlConnection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                return response.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making an API request", e);
            return null;
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }

    private boolean isInternetConnected() {
        Network network = mConnectivityManager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void registerNetworkCallback() {
        mConnectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (!mPendingUpdate) return;
                long now = System.currentTimeMillis();
                if (now - mLastSuccessFetchMs < MIN_REFETCH_MS) return;
                if (mFetchScheduledByNetwork.compareAndSet(false, true)) {
                    Log.i(TAG, "Internet available, resuming pending update");
                    mScheduler.schedule(() -> {
                        try {
                            mFetchRunnable.run();
                        } finally {
                            mFetchScheduledByNetwork.set(false);
                        }
                    }, 0, TimeUnit.SECONDS);
                }
            }
        });
    }

    private void dlog(String message) {
        if (DEBUG) Log.d(TAG, message);
    }

    private class FetchGmsCertifiedProps implements Runnable {
        @Override
        public void run() {
            if (Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.PI_ENABLE_SPOOF, 1) != 1) {
                mPendingUpdate = false;
                return;
            }

            try {
                dlog("FetchGmsCertifiedProps started");

                if (!isInternetConnected()) {
                    Log.e(TAG, "Internet unavailable, deferring update");
                    mPendingUpdate = true;
                    return;
                }
                mPendingUpdate = false;

                String props = fetchProps();

                if (props != null) {
                    try {
                        new JSONObject(props);
                    } catch (JSONException e) {
                        Log.e(TAG, "Fetched props are not valid JSON, keeping existing", e);
                        return;
                    }
                } else {
                    return;
                }

                String savedProps = Settings.Secure.getString(
                        mContext.getContentResolver(), Settings.Secure.FETCHED_PIF);

                if (!props.equals(savedProps)) {
                    dlog("Found new props, updating");
                    Settings.Secure.putString(
                            mContext.getContentResolver(), Settings.Secure.FETCHED_PIF, props);
                    mLastSuccessFetchMs = System.currentTimeMillis();
                    dlog("FetchGmsCertifiedProps completed");
                } else {
                    dlog("No change in props");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in FetchGmsCertifiedProps", e);
            }
        }
    }
}