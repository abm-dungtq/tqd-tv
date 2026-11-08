package com.gathin.tabletguard;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Manages unique Device Identity, 6-Digit Pairing Code generation, Android TV vs Tablet detection,
 * and automatic synchronization with the cloud server (https://tv.tqd.me).
 */
public final class DeviceIdentityManager {

    public static final String SERVER_BASE_URL = "https://tv.tqd.me";
    private static final String PREFS_NAME = "tqd_device_identity";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_PAIRING_CODE = "pairing_code";
    private static final String KEY_PAIRED = "paired";
    private static final String KEY_OWNER_NAME = "owner_name";
    private static final String KEY_DEVICE_NAME = "device_name";

    private static volatile boolean syncLoopStarted = false;
    private static volatile Context appContext;

    private DeviceIdentityManager() {}

    public static void init(Context ctx) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext();
        }
        if (appContext == null) return;
        getDeviceId(appContext);
        getPairingCode(appContext);
        startSyncLoop();
    }

    public static boolean isTelevision(Context ctx) {
        if (ctx == null) return false;
        try {
            UiModeManager uiModeManager = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
            if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
            PackageManager pm = ctx.getPackageManager();
            if (pm != null) {
                if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                        || pm.hasSystemFeature("android.hardware.type.television")
                        || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static String getDeviceType(Context ctx) {
        return isTelevision(ctx) ? "tv" : "tablet";
    }

    public static String getFriendlyModel(Context ctx) {
        String raw = Build.MODEL != null ? Build.MODEL.trim() : "";
        if ("KFTRWI".equalsIgnoreCase(raw)) {
            return "Fire HD 10";
        }
        if (raw.isEmpty()) {
            return isTelevision(ctx) ? "Android TV" : "Máy tính bảng Android";
        }
        return raw;
    }

    public static synchronized String getDeviceId(Context ctx) {
        Context c = ctx != null ? ctx.getApplicationContext() : appContext;
        if (c == null) return "tablet01";
        SharedPreferences sp = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String existing = sp.getString(KEY_DEVICE_ID, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String rawAndroidId = "";
        try {
            rawAndroidId = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {}
        if (rawAndroidId == null || rawAndroidId.trim().length() < 4 || "9774d56d682e549c".equalsIgnoreCase(rawAndroidId)) {
            rawAndroidId = UUID.randomUUID().toString().replace("-", "");
        }
        String cleanHex = rawAndroidId.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
        if (cleanHex.length() > 10) {
            cleanHex = cleanHex.substring(cleanHex.length() - 10);
        }
        String prefix = isTelevision(c) ? "tv_" : "tab_";
        String devId = prefix + cleanHex;
        sp.edit().putString(KEY_DEVICE_ID, devId).apply();
        return devId;
    }

    public static synchronized String getPairingCode(Context ctx) {
        Context c = ctx != null ? ctx.getApplicationContext() : appContext;
        if (c == null) return "160101";
        SharedPreferences sp = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String existing = sp.getString(KEY_PAIRING_CODE, null);
        if (existing != null && existing.matches("\\d{6}")) {
            return existing;
        }
        String devId = getDeviceId(c);
        long hash = 2166136261L;
        for (int i = 0; i < devId.length(); i++) {
            hash ^= devId.charAt(i);
            hash *= 16777619L;
            hash &= 0x7FFFFFFFL;
        }
        int codeNum = 100000 + (int) (hash % 900000L);
        String code = String.format(Locale.US, "%06d", codeNum);
        sp.edit().putString(KEY_PAIRING_CODE, code).apply();
        return code;
    }

    public static String getFormattedPairingCode(Context ctx) {
        String code = getPairingCode(ctx);
        if (code.length() == 6) {
            return code.substring(0, 3) + " " + code.substring(3);
        }
        return code;
    }

    public static boolean isPaired(Context ctx) {
        Context c = ctx != null ? ctx.getApplicationContext() : appContext;
        if (c == null) return false;
        return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false);
    }

    public static String getOwnerName(Context ctx) {
        Context c = ctx != null ? ctx.getApplicationContext() : appContext;
        if (c == null) return "";
        return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_OWNER_NAME, "");
    }

    public static String getPairedDeviceName(Context ctx) {
        Context c = ctx != null ? ctx.getApplicationContext() : appContext;
        if (c == null) return "";
        return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEVICE_NAME, "");
    }

    public static void onPairConfirmed(Context ctx, String ownerName, String deviceName, String newPin) {
        Context c = ctx != null ? ctx.getApplicationContext() : appContext;
        if (c == null) return;
        SharedPreferences sp = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putBoolean(KEY_PAIRED, true)
                .putString(KEY_OWNER_NAME, ownerName != null && !ownerName.isEmpty() ? ownerName : "Tài khoản Bố/Mẹ")
                .putString(KEY_DEVICE_NAME, deviceName != null ? deviceName : "")
                .apply();
        if (newPin != null && newPin.trim().matches("\\d{4,6}")) {
            TabletControlManager.setPin(c, newPin.trim());
        }
        TabletCloudClient.publishStateSoon();
    }

    public static void onUnpaired(Context ctx) {
        Context c = ctx != null ? ctx.getApplicationContext() : appContext;
        if (c == null) return;
        SharedPreferences sp = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putBoolean(KEY_PAIRED, false)
                .putString(KEY_OWNER_NAME, "")
                .putString(KEY_DEVICE_NAME, "")
                .apply();
        TabletCloudClient.publishStateSoon();
    }

    private static void startSyncLoop() {
        if (syncLoopStarted) return;
        synchronized (DeviceIdentityManager.class) {
            if (syncLoopStarted) return;
            syncLoopStarted = true;
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        syncWithServerOnce();
                    } catch (Throwable ignored) {}
                    try {
                        Thread.sleep(isPaired(appContext) ? 25_000L : 8_000L);
                    } catch (InterruptedException ignored) {}
                }
            }, "tqd-device-register-sync");
            t.setDaemon(true);
            t.start();
        }
    }

    public static void triggerImmediateSync() {
        new Thread(() -> {
            try {
                syncWithServerOnce();
            } catch (Throwable ignored) {}
        }, "tqd-device-sync-now").start();
    }

    private static void syncWithServerOnce() {
        Context c = appContext;
        if (c == null) return;
        HttpURLConnection conn = null;
        try {
            String devId = getDeviceId(c);
            String code = getPairingCode(c);
            String type = getDeviceType(c);
            String model = getFriendlyModel(c);

            JSONObject req = new JSONObject();
            req.put("deviceId", devId);
            req.put("pairingCode", code);
            req.put("deviceType", type);
            req.put("appFlavor", "guard");
            req.put("model", model);
            req.put("legacyId", "KFTRWI".equalsIgnoreCase(Build.MODEL) ? "tablet01" : "");

            URL url = new URL(SERVER_BASE_URL + "/api/device/register-code");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out = conn.getOutputStream();
            out.write(body);
            out.flush();
            out.close();

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                InputStream in = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject resp = new JSONObject(sb.toString());
                if (resp.optBoolean("ok", false)) {
                    String serverCode = resp.optString("pairingCode", "").trim();
                    if (serverCode.matches("\\d{6}") && !serverCode.equals(code)) {
                        c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putString(KEY_PAIRING_CODE, serverCode).apply();
                    }
                    boolean paired = resp.optBoolean("paired", false);
                    if (paired) {
                        String owner = resp.optString("ownerName", "Tài khoản Bố/Mẹ");
                        String devName = resp.optString("deviceName", model);
                        String pin = resp.optString("pinCode", "");
                        boolean wasPaired = isPaired(c);
                        onPairConfirmed(c, owner, devName, pin);
                        if (!wasPaired) {
                            TabletCloudClient.publishStateSoon();
                        }
                    } else if (isPaired(c)) {
                        onUnpaired(c);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }
}
