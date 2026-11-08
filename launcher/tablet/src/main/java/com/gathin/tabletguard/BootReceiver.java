package com.gathin.tabletguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        try {
            TabletMonitorService.ensureEnabled(context);
            TabletControlManager.init(context, null);
            TabletWebServer.ensureRunning(context);
            TabletCloudClient.ensureRunning(context);
            TabletGuardService.start(context);
        } catch (Throwable ignored) {
        }
    }
}
