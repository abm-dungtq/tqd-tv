package com.gathin.tvhome;

import android.app.Application;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.provider.Settings;

/**
 * Application entry point ensuring WebRemoteServer (port 8686), CloudTunnelClient (4G/5G MQTT),
 * ParentalControlManager, and HomeKeyService are initialized as soon as the process starts
 * (including when started in the background by MediaMonitorService while another app is playing).
 */
public class TvHomeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            MediaMonitorService.ensureEnabled(this);
            WebRemoteServer.ensureRunning(this, null);
            ParentalControlManager.init(this, null);
            CloudTunnelClient.ensureRunning(this);
            ensureHomeKeyServiceBound();
        } catch (Throwable ignored) {
        }
    }

    private void ensureHomeKeyServiceBound() {
        new Thread(() -> {
            try {
                ContentResolver cr = getContentResolver();
                String flat = new ComponentName(this, HomeKeyService.class).flattenToString();
                String shortFlat = getPackageName() + "/.HomeKeyService";
                String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (cur == null || (!cur.contains(flat) && !cur.contains(shortFlat))) {
                    String next = (cur == null || cur.trim().isEmpty()) ? flat : (cur + ":" + flat);
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                }
                Thread.sleep(1500L);
                if (!WebRemoteServer.hasAccessibilityService()) {
                    // Force AccessibilityManagerService to drop any stale DEAD ConnectionRecord and rebind
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
                    Thread.sleep(350L);
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, flat);
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                }
            } catch (Throwable ignored) {
            }
        }, "TvHomeApp-A11yBind").start();
    }
}
