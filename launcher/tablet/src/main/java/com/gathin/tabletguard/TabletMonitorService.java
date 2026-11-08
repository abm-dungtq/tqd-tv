package com.gathin.tabletguard;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.List;

public class TabletMonitorService extends NotificationListenerService {

    private static volatile WeakReference<TabletMonitorService> instanceRef = new WeakReference<>(null);

    public static final class NowPlaying {
        public final boolean hasMedia;
        public final boolean playing;
        public final String pkg;
        public final String appLabel;
        public final String title;
        public final String channel;
        public final long positionSec;
        public final long durationSec;

        public NowPlaying(boolean hasMedia, boolean playing, String pkg, String appLabel,
                          String title, String channel, long positionSec, long durationSec) {
            this.hasMedia = hasMedia;
            this.playing = playing;
            this.pkg = pkg != null ? pkg : "";
            this.appLabel = appLabel != null ? appLabel : "";
            this.title = title != null ? title : "";
            this.channel = channel != null ? channel : "";
            this.positionSec = Math.max(0L, positionSec);
            this.durationSec = Math.max(0L, durationSec);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instanceRef = new WeakReference<>(this);
        TabletControlManager.init(this, null);
        TabletWebServer.ensureRunning(this);
        TabletCloudClient.ensureRunning(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instanceRef = new WeakReference<>(this);
        TabletControlManager.init(this, null);
        TabletWebServer.ensureRunning(this);
        TabletCloudClient.ensureRunning(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        TabletCloudClient.publishStateSoon();
    }

    public static boolean isNotificationListenerEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            String cur = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            return cur != null && cur.contains(ctx.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isUsageStatsGranted(Context ctx) {
        if (ctx == null) return false;
        try {
            AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void ensureEnabled(Context ctx) {
        if (ctx == null) return;
        try {
            String flat = new ComponentName(ctx.getPackageName(), TabletMonitorService.class.getName()).flattenToString();
            String shortFlat = ctx.getPackageName() + "/.TabletMonitorService";
            String cur = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            if (cur == null || cur.trim().isEmpty()) {
                Settings.Secure.putString(ctx.getContentResolver(), "enabled_notification_listeners", flat);
            } else if (!cur.contains(flat) && !cur.contains(shortFlat)) {
                Settings.Secure.putString(ctx.getContentResolver(), "enabled_notification_listeners", cur + ":" + flat);
            }
        } catch (Throwable ignored) {
        }
    }

    public static NowPlaying getNowPlaying(Context ctx, String fallbackFgPkg) {
        if (ctx == null) {
            return new NowPlaying(false, false, "", "", "", "", 0, 0);
        }
        ensureEnabled(ctx);
        String fgPkg = detectForegroundPackage(ctx, fallbackFgPkg);
        String fgLabel = resolveAppLabel(ctx, fgPkg);

        // Layer 1: Active MediaSession
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                ComponentName cn = new ComponentName(ctx.getPackageName(), TabletMonitorService.class.getName());
                List<MediaController> list = msm.getActiveSessions(cn);
                if (list != null && !list.isEmpty()) {
                    MediaController chosen = null;
                    for (MediaController mc : list) {
                        PlaybackState ps = mc.getPlaybackState();
                        if (ps != null && (ps.getState() == PlaybackState.STATE_PLAYING
                                || ps.getState() == PlaybackState.STATE_BUFFERING)) {
                            chosen = mc;
                            break;
                        }
                    }
                    if (chosen == null) {
                        chosen = list.get(0);
                    }
                    String pkg = chosen.getPackageName();
                    String appLabel = resolveAppLabel(ctx, pkg);
                    PlaybackState ps = chosen.getPlaybackState();
                    boolean playing = ps != null && (ps.getState() == PlaybackState.STATE_PLAYING
                            || ps.getState() == PlaybackState.STATE_BUFFERING);
                    long posSec = 0L;
                    if (ps != null && ps.getPosition() > 0) {
                        long posMs = ps.getPosition();
                        if (playing && ps.getLastPositionUpdateTime() > 0) {
                            long delta = SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime();
                            if (delta > 0 && delta < 3600_000L) {
                                posMs += (long) (delta * ps.getPlaybackSpeed());
                            }
                        }
                        posSec = posMs / 1000L;
                    }
                    String title = "";
                    String channel = "";
                    long durSec = 0L;
                    MediaMetadata meta = chosen.getMetadata();
                    if (meta != null) {
                        durSec = Math.max(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000L);
                        title = firstNonEmpty(
                                meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                                meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                        );
                        channel = firstNonEmpty(
                                meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                                meta.getString(MediaMetadata.METADATA_KEY_ARTIST),
                                meta.getString(MediaMetadata.METADATA_KEY_AUTHOR),
                                meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
                                meta.getString(MediaMetadata.METADATA_KEY_WRITER)
                        );
                        if (title.isEmpty() || channel.isEmpty()) {
                            MediaDescription desc = meta.getDescription();
                            if (desc != null) {
                                if (title.isEmpty() && desc.getTitle() != null) title = desc.getTitle().toString();
                                if (channel.isEmpty() && desc.getSubtitle() != null) channel = desc.getSubtitle().toString();
                            }
                        }
                    }
                    if (title.isEmpty()) {
                        title = TabletAccessibilityService.getScrapedTitle(pkg);
                        if (channel.isEmpty()) channel = TabletAccessibilityService.getScrapedChannel(pkg);
                    }
                    if (playing || (!title.isEmpty() && (pkg.equals(fgPkg) || fgPkg.isEmpty()))) {
                        return new NowPlaying(true, playing, pkg, appLabel, title, channel, posSec, durSec);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Layer 2: Active media notification fallback
        try {
            TabletMonitorService svc = instanceRef.get();
            if (svc != null) {
                StatusBarNotification[] sbns = svc.getActiveNotifications();
                if (sbns != null) {
                    for (StatusBarNotification sbn : sbns) {
                        String npkg = sbn.getPackageName();
                        if (npkg == null || npkg.equals(ctx.getPackageName()) || "android".equals(npkg) || "com.android.systemui".equals(npkg)) {
                            continue;
                        }
                        Notification n = sbn.getNotification();
                        if (n == null || n.extras == null) continue;
                        Bundle ex = n.extras;
                        String template = ex.getString(Notification.EXTRA_TEMPLATE, "");
                        boolean isMediaNotif = template.contains("MediaStyle")
                                || Notification.CATEGORY_TRANSPORT.equals(n.category)
                                || npkg.contains("youtube")
                                || npkg.contains("tiktok")
                                || npkg.contains("musically");
                        if (!isMediaNotif) continue;
                        CharSequence t = ex.getCharSequence(Notification.EXTRA_TITLE);
                        CharSequence sub = ex.getCharSequence(Notification.EXTRA_TEXT);
                        if (t != null && t.length() > 0) {
                            return new NowPlaying(true, true, npkg, resolveAppLabel(ctx, npkg),
                                    t.toString(), sub != null ? sub.toString() : "", 0, 0);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Layer 3: Accessibility window title fallback inside YouTube / YouTube Kids
        String scrapedTitle = TabletAccessibilityService.getScrapedTitle(fgPkg);
        String scrapedCh = TabletAccessibilityService.getScrapedChannel(fgPkg);
        if (!scrapedTitle.isEmpty()) {
            return new NowPlaying(true, true, fgPkg, fgLabel, scrapedTitle, scrapedCh, 0, 0);
        }

        boolean screenOn = isScreenOn(ctx);
        boolean activeApp = screenOn && !fgPkg.isEmpty() && !isLauncherPackage(fgPkg);
        String displayTitle = !screenOn
                ? "Màn hình máy tính bảng đang tắt (Nghỉ)"
                : (activeApp ? ("Đang mở ứng dụng: " + fgLabel) : "Đang ở Màn hình chính");
        return new NowPlaying(activeApp, activeApp, fgPkg, fgLabel, displayTitle, "", 0, 0);
    }

    private static boolean isLauncherPackage(String pkg) {
        if (pkg == null) return true;
        String low = pkg.toLowerCase();
        return low.contains("launcher") || low.contains("miui.home") || low.contains("trebuchet") || low.equals("com.android.settings");
    }

    public static void pauseAllMedia(Context ctx) {
        if (ctx == null) return;
        ensureEnabled(ctx);
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                ComponentName cn = new ComponentName(ctx.getPackageName(), TabletMonitorService.class.getName());
                List<MediaController> list = msm.getActiveSessions(cn);
                if (list != null) {
                    for (MediaController mc : list) {
                        try {
                            MediaController.TransportControls tc = mc.getTransportControls();
                            if (tc != null) {
                                tc.pause();
                                tc.stop();
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            }
        } catch (Throwable ignored) {}
    }

    public static String detectForegroundPackage(Context ctx, String fallbackFgPkg) {
        if (fallbackFgPkg != null && !fallbackFgPkg.isEmpty() && TabletAccessibilityService.isConnected()) {
            return fallbackFgPkg;
        }
        if (ctx != null) {
            try {
                UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    long now = System.currentTimeMillis();
                    UsageEvents events = usm.queryEvents(now - 120_000L, now);
                    if (events != null) {
                        UsageEvents.Event ev = new UsageEvents.Event();
                        String newestPkg = null;
                        long newestAt = 0L;
                        while (events.hasNextEvent()) {
                            events.getNextEvent(ev);
                            if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                                    && ev.getPackageName() != null
                                    && ev.getTimeStamp() >= newestAt) {
                                newestPkg = ev.getPackageName();
                                newestAt = ev.getTimeStamp();
                            }
                        }
                        if (newestPkg != null && !newestPkg.isEmpty()) {
                            return newestPkg;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return fallbackFgPkg != null ? fallbackFgPkg : "";
    }

    public static long getTodayScreenMinutes(Context ctx) {
        if (ctx == null) return 0L;
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return 0L;
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long start = cal.getTimeInMillis();
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now);
            if (stats == null) return 0L;
            long totalMs = 0L;
            for (UsageStats s : stats) {
                String pkg = s.getPackageName();
                if (pkg == null || isLauncherPackage(pkg) || "com.android.systemui".equals(pkg) || ctx.getPackageName().equals(pkg)) {
                    continue;
                }
                totalMs += Math.max(0L, s.getTotalTimeInForeground());
            }
            return totalMs / 60_000L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static int getBatteryPercent(Context ctx) {
        if (ctx == null) return -1;
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    return Math.round((level * 100f) / scale);
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    public static boolean isCharging(Context ctx) {
        if (ctx == null) return false;
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                return status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isScreenOn(Context ctx) {
        if (ctx == null) return true;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static String resolveAppLabel(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "Màn hình chính";
        if ("com.google.android.youtube".equals(pkg)) return "YouTube";
        if ("com.google.android.apps.youtube.kids".equals(pkg)) return "YouTube Kids";
        if ("org.smarttube.stable".equals(pkg)) return "SmartTube (YouTube)";
        if ("com.android.chrome".equals(pkg)) return "Trình duyệt Chrome";
        if ("com.amazon.cloud9".equals(pkg)) return "Trình duyệt Silk";
        if ("com.ss.android.ugc.trill".equals(pkg) || "com.zhiliaoapp.musically".equals(pkg)) return "TikTok";
        if ("com.roblox.client".equals(pkg) || "com.roblox.client.vnggames".equals(pkg)) return "Roblox (Game)";
        if ("com.blockblast.vn".equals(pkg)) return "Block Blast! (Game)";
        if ("com.maxgames.stickwarlegacy".equals(pkg)) return "Stick War: Legacy (Game)";
        if ("com.maxgames.stickwar3".equals(pkg)) return "Stick War: Saga (Game)";
        if ("com.duolingo".equals(pkg)) return "Duolingo (Học tiếng Anh)";
        if ("org.telegram.messenger".equals(pkg)) return "Telegram";
        if ("com.google.android.apps.bard".equals(pkg)) return "Gemini AI";
        if ("com.zing.zalo".equals(pkg)) return "Zalo";
        if ("com.facebook.katana".equals(pkg)) return "Facebook";
        if ("com.netflix.mediaclient".equals(pkg)) return "Netflix";
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (ai != null) {
                    CharSequence cs = pm.getApplicationLabel(ai);
                    if (cs != null && cs.length() > 0) return cs.toString();
                }
            } catch (Throwable ignored) {}
        }
        return pkg;
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }
}
