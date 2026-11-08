package com.gathin.tvhome;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.KeyEvent;

import java.util.List;
import java.util.Locale;

/**
 * Lightweight NotificationListenerService that allows CleanYourTV Launcher (with WRITE_SECURE_SETTINGS)
 * to query MediaSessionManager.getActiveSessions() and report in real time what video/channel the child
 * is watching on SmartTube or any other media app, as well as pause playback cleanly when time is up.
 */
public class MediaMonitorService extends NotificationListenerService {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            WebRemoteServer.ensureRunning(this, null);
            ParentalControlManager.init(this, null);
            CloudTunnelClient.ensureRunning(this);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            WebRemoteServer.ensureRunning(this, null);
            ParentalControlManager.init(this, null);
            CloudTunnelClient.ensureRunning(this);
        } catch (Throwable ignored) {}
    }

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

    /** Self-registers this NotificationListenerService in Settings.Secure using WRITE_SECURE_SETTINGS. */
    public static void ensureEnabled(Context ctx) {
        if (ctx == null) return;
        try {
            String flat = new ComponentName(ctx.getPackageName(), MediaMonitorService.class.getName()).flattenToString();
            String shortFlat = ctx.getPackageName() + "/.MediaMonitorService";
            String cur = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            if (cur == null || cur.trim().isEmpty()) {
                Settings.Secure.putString(ctx.getContentResolver(), "enabled_notification_listeners", flat);
            } else if (!cur.contains(flat) && !cur.contains(shortFlat)) {
                Settings.Secure.putString(ctx.getContentResolver(), "enabled_notification_listeners", cur + ":" + flat);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Queries active MediaSessions (and UsageStats fallback) to see what is playing on the TV right now. */
    public static NowPlaying getNowPlaying(Context ctx, String fallbackFgPkg) {
        if (ctx == null) {
            return new NowPlaying(false, false, "", "", "", "", 0, 0);
        }
        ensureEnabled(ctx);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                if (msm != null) {
                    ComponentName cn = new ComponentName(ctx.getPackageName(), MediaMonitorService.class.getName());
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
                                    if (title.isEmpty() && desc.getTitle() != null) {
                                        title = desc.getTitle().toString();
                                    }
                                    if (channel.isEmpty() && desc.getSubtitle() != null) {
                                        channel = desc.getSubtitle().toString();
                                    }
                                }
                            }
                        }
                        return new NowPlaying(true, playing, pkg, appLabel, title, channel, posSec, durSec);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        String fg = detectForegroundPackage(ctx, fallbackFgPkg);
        String label = resolveAppLabel(ctx, fg);
        return new NowPlaying(false, false, fg, label, "", "", 0, 0);
    }

    /** Pauses and stops any active media session on the TV immediately. */
    public static void pauseAllMedia(Context ctx) {
        if (ctx == null) return;
        ensureEnabled(ctx);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                if (msm != null) {
                    ComponentName cn = new ComponentName(ctx.getPackageName(), MediaMonitorService.class.getName());
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
        if (ctx != null && Build.VERSION.SDK_INT >= 21) {
            try {
                UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    long now = System.currentTimeMillis();
                    UsageEvents events = usm.queryEvents(now - 60_000L, now);
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

    public static String resolveAppLabel(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "Màn hình chờ";
        if ("org.smarttube.stable".equals(pkg)) return "SmartTube (YouTube)";
        if ("com.gathin.tvhome".equals(pkg)) return "Màn hình chính TV";
        if ("vn.vtv.vtvgotv".equals(pkg)) return "VTV Go";
        if ("kodi.tungbui.movies".equals(pkg)) return "MKodi Phim";
        if ("org.courville.nova".equals(pkg)) return "Nova Video Player";
        if ("org.videolan.vlc".equals(pkg)) return "VLC Player";
        if ("com.phlox.tvwebbrowser".equals(pkg)) return "TV Bro (Trình duyệt)";
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

    public static String formatTime(long totalSec) {
        if (totalSec <= 0) return "00:00";
        long hrs = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (hrs > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs);
        }
        return String.format(Locale.US, "%02d:%02d", mins, secs);
    }
}
