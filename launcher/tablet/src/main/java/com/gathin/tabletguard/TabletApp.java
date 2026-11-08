package com.gathin.tabletguard;

import android.app.Application;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.provider.Settings;

public class TabletApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            TabletMonitorService.ensureEnabled(this);
            ensureAccessibilityIfPermitted();
            TabletControlManager.init(this, null);
            TabletWebServer.ensureRunning(this);
            TabletCloudClient.ensureRunning(this);
            TabletGuardService.start(this);
        } catch (Throwable ignored) {
        }
    }

    private void ensureAccessibilityIfPermitted() {
        try {
            ContentResolver cr = getContentResolver();
            String flat = new ComponentName(this, TabletAccessibilityService.class).flattenToString();
            String shortFlat = getPackageName() + "/.TabletAccessibilityService";
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null || cur.trim().isEmpty()) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, flat);
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } else if (!cur.contains(flat) && !cur.contains(shortFlat)) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, cur + ":" + flat);
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (Throwable ignored) {
        }
    }
}
