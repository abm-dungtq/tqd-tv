package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Manages:
 *  1) Always-on ADB persistence across reboots (overrides Xiaomi's sys.set_adb_disabled).
 *  2) Non-intrusive on-screen parental warning banners + live top-right countdown overlay over SmartTube.
 *  3) Auto-pause + TV sleep + persistent SmartTube/video lock when countdown reaches 00:00.
 */
public final class ParentalControlManager {

    private static final String PREFS_NAME = "parental_control";
    private static final String KEY_LOCKED = "locked";
    private static final String KEY_LOCK_MESSAGE = "lock_message";
    private static final String KEY_PIN = "pin_code";
    private static final String KEY_SCHED_ENABLED = "sched_enabled";
    private static final String KEY_SCHED_START = "sched_start";
    private static final String KEY_SCHED_END = "sched_end";
    private static final String KEY_TEMP_UNLOCK_UNTIL = "temp_unlock_until";
    private static final String KEY_AUTO_LOCKED_BY_SCHED = "auto_locked_by_sched";
    public static final String DEFAULT_PIN = "1601";
    public static final String DEFAULT_SCHED_START = "21:30";
    public static final String DEFAULT_SCHED_END = "06:30";

    private static final Set<String> GUARDED_MEDIA_PKGS = new HashSet<>(Arrays.asList(
            "org.smarttube.stable",
            "vn.vtv.vtvgotv",
            "kodi.tungbui.movies",
            "org.courville.nova",
            "org.videolan.vlc",
            "com.phlox.tvwebbrowser"
    ));

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile Context appContext;
    private static volatile WeakReference<AccessibilityService> a11yRef = new WeakReference<>(null);
    private static volatile boolean initialized = false;
    private static volatile String lastKnownFgPkg = "";

    // Countdown state
    private static volatile long countdownEndElapsedMs = 0L;
    private static volatile String activeWarningText = "";
    private static volatile boolean warned60s = false;
    private static volatile boolean warned15s = false;

    // Overlay views attached to WindowManager
    private static View bannerView;
    private static TextView bannerTitleView;
    private static TextView bannerMsgView;
    private static View countdownView;
    private static TextView countdownTextView;
    private static GradientDrawable countdownBg;

    // Full-screen On-TV PIN Pad Lock Overlay (U3)
    private static View lockOverlayView;
    private static TextView lockTitleView;
    private static TextView lockMsgView;
    private static TextView lockDotsView;
    private static TextView lockStatusView;
    private static final TextView[] lockKeyViews = new TextView[12];
    private static final String[] KEYPAD_LABELS = new String[]{
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "⌫", "0", "✓"
    };
    private static int pinCursorRow = 0;
    private static int pinCursorCol = 0;
    private static String pinBuffer = "";
    private static int pinFailCount = 0;
    private static long pinCooldownUntilElapsed = 0L;

    private static long lastLockEnforceAt = 0L;

    private ParentalControlManager() {}

    public static void init(Context context, AccessibilityService service) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        if (service != null) {
            a11yRef = new WeakReference<>(service);
        }
        if (appContext == null) return;

        ensureAdbAlwaysOn(appContext);
        MediaMonitorService.ensureEnabled(appContext);

        if (!initialized) {
            initialized = true;
            registerAdbObserver(appContext);
            // Delayed boot re-assertions to beat Xiaomi's sys.set_adb_disabled boot script
            for (long delayMs : new long[]{5_000L, 15_000L, 30_000L, 60_000L}) {
                mainHandler.postDelayed(() -> ensureAdbAlwaysOn(appContext), delayMs);
            }
            mainHandler.postDelayed(tickRunnable, 1000L);
        }
    }

    public static void updateForegroundPackage(String pkg) {
        if (pkg != null && !pkg.isEmpty()) {
            lastKnownFgPkg = pkg;
        }
    }

    public static String getForegroundPackage() {
        return lastKnownFgPkg;
    }

    /** Ensures Settings.Global.ADB_ENABLED is 1 using WRITE_SECURE_SETTINGS. */
    public static boolean ensureAdbAlwaysOn(Context ctx) {
        if (ctx == null) return false;
        try {
            int cur = Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
            if (cur != 1) {
                Settings.Global.putInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            }
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isAdbEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void registerAdbObserver(final Context ctx) {
        try {
            ctx.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                    false,
                    new ContentObserver(mainHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            ensureAdbAlwaysOn(ctx);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    public static String getPin(Context ctx) {
        Context c = ctx != null ? ctx : appContext;
        if (c == null) return DEFAULT_PIN;
        SharedPreferences sp = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String p = sp.getString(KEY_PIN, DEFAULT_PIN);
        return (p == null || p.trim().isEmpty()) ? DEFAULT_PIN : p.trim();
    }

    public static void setPin(Context ctx, String newPin) {
        Context c = ctx != null ? ctx : appContext;
        if (c == null || newPin == null || newPin.trim().length() < 4) return;
        c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PIN, newPin.trim()).apply();
    }

    public static boolean verifyPin(Context ctx, String candidate) {
        if (candidate == null) return false;
        return getPin(ctx).equals(candidate.trim());
    }

    public static boolean isLocked() {
        if (appContext == null) return false;
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCKED, false);
    }

    public static String getLockMessage() {
        if (appContext == null) return "Đã hết giờ xem Tivi, hẹn con ngày mai nhé ❤️";
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LOCK_MESSAGE, "Đã hết giờ xem Tivi, hẹn con ngày mai nhé ❤️");
    }

    public static void setLocked(boolean locked, String message) {
        if (appContext == null) return;
        SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean(KEY_LOCKED, locked);
        if (!locked) {
            ed.putBoolean(KEY_AUTO_LOCKED_BY_SCHED, false);
        }
        if (message != null && !message.trim().isEmpty()) {
            ed.putString(KEY_LOCK_MESSAGE, message.trim());
        }
        ed.apply();
    }

    public static boolean isScheduleEnabled() {
        if (appContext == null) return false;
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SCHED_ENABLED, false);
    }

    public static String getScheduleStart() {
        if (appContext == null) return DEFAULT_SCHED_START;
        String s = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SCHED_START, DEFAULT_SCHED_START);
        return (s == null || !s.contains(":")) ? DEFAULT_SCHED_START : s.trim();
    }

    public static String getScheduleEnd() {
        if (appContext == null) return DEFAULT_SCHED_END;
        String s = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SCHED_END, DEFAULT_SCHED_END);
        return (s == null || !s.contains(":")) ? DEFAULT_SCHED_END : s.trim();
    }

    public static long getTempUnlockUntilMs() {
        if (appContext == null) return 0L;
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_TEMP_UNLOCK_UNTIL, 0L);
    }

    public static void grantTemporaryUnlockMinutes(int minutes) {
        if (appContext == null) return;
        long until = System.currentTimeMillis() + Math.max(1, minutes) * 60_000L;
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_TEMP_UNLOCK_UNTIL, until).apply();
    }

    public static void setSchedule(boolean enabled, String startHhMm, String endHhMm) {
        if (appContext == null) return;
        String s = (startHhMm != null && startHhMm.contains(":")) ? startHhMm.trim() : getScheduleStart();
        String e = (endHhMm != null && endHhMm.contains(":")) ? endHhMm.trim() : getScheduleEnd();
        SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean(KEY_SCHED_ENABLED, enabled);
        ed.putString(KEY_SCHED_START, s);
        ed.putString(KEY_SCHED_END, e);
        if (!enabled) {
            ed.putLong(KEY_TEMP_UNLOCK_UNTIL, 0L);
            if (appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_AUTO_LOCKED_BY_SCHED, false)) {
                ed.putBoolean(KEY_LOCKED, false);
                ed.putBoolean(KEY_AUTO_LOCKED_BY_SCHED, false);
                mainHandler.post(ParentalControlManager::hideFullScreenLockOverlay);
            }
        }
        ed.apply();
        CloudTunnelClient.publishStateSoon();
    }

    private static int parseMinutesOfDay(String hhmm, int fallbackMin) {
        if (hhmm == null) return fallbackMin;
        try {
            String[] p = hhmm.trim().split(":");
            if (p.length >= 2) {
                int h = Integer.parseInt(p[0].trim());
                int m = Integer.parseInt(p[1].trim());
                if (h >= 0 && h < 24 && m >= 0 && m < 60) {
                    return h * 60 + m;
                }
            }
        } catch (Throwable ignored) {}
        return fallbackMin;
    }

    public static boolean isWithinScheduleWindow() {
        int startMin = parseMinutesOfDay(getScheduleStart(), 21 * 60 + 30);
        int endMin = parseMinutesOfDay(getScheduleEnd(), 6 * 60 + 30);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int curMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        if (startMin == endMin) return false;
        if (startMin < endMin) {
            return curMin >= startMin && curMin < endMin;
        } else {
            return curMin >= startMin || curMin < endMin;
        }
    }

    public static boolean isScheduleActiveNow() {
        if (!isScheduleEnabled()) return false;
        if (!isWithinScheduleWindow()) return false;
        return System.currentTimeMillis() >= getTempUnlockUntilMs();
    }

    /**
     * QuietBehind (U4): When returning to Home or locking, pauses active MediaSessions and briefly
     * claims/releases STREAM_MUSIC audio focus (SDK 23 compatible) so background apps stop playing sound.
     */
    public static void hushBackgroundPlayers(Context context) {
        final Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return;
        MediaMonitorService.pauseAllMedia(ctx);
        try {
            final AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                final AudioManager.OnAudioFocusChangeListener listener = focusChange -> {};
                am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                mainHandler.postDelayed(() -> {
                    try { am.abandonAudioFocus(listener); } catch (Throwable ignored) {}
                }, 400L);
            }
        } catch (Throwable ignored) {}
    }

    public static long getRemainingSeconds() {
        long end = countdownEndElapsedMs;
        if (end <= 0L) return 0L;
        long remMs = end - SystemClock.elapsedRealtime();
        return remMs > 0L ? (remMs + 999L) / 1000L : 0L;
    }

    public static String getActiveWarningText() {
        return activeWarningText;
    }

    /**
     * Triggered from Phone Web Remote (LAN or Cloud):
     *  - action = "message" -> shows message banner for 14s without starting/changing timer
     *  - action = "timer"   -> starts countdown for `minutes` (or `seconds`), shows banner + countdown pill
     *  - action = "add"     -> adds `minutes` to current timer (or starts new timer)
     *  - action = "lock_now"-> immediately locks SmartTube, shows message, and puts TV to sleep
     *  - action = "unlock"  -> cancels any countdown, unlocks SmartTube, and hides lock banner
     */
    public static void handleParentalCommand(String action, int minutes, String customMessage) {
        final String act = action != null ? action.trim().toLowerCase(Locale.ROOT) : "message";
        final String cleanMsg = (customMessage != null && !customMessage.trim().isEmpty())
                ? customMessage.trim()
                : "";

        mainHandler.post(() -> {
            if ("pin".equals(act)) {
                CloudTunnelClient.publishStateSoon();
                return;
            }
            if ("unlock".equals(act) || "cancel".equals(act)) {
                countdownEndElapsedMs = 0L;
                activeWarningText = "";
                warned60s = false;
                warned15s = false;
                pinBuffer = "";
                pinFailCount = 0;
                pinCooldownUntilElapsed = 0L;
                if (isScheduleEnabled() && isWithinScheduleWindow()) {
                    grantTemporaryUnlockMinutes(60);
                }
                setLocked(false, null);
                hideCountdownOverlay();
                hideFullScreenLockOverlay();
                String msg = cleanMsg.isEmpty()
                        ? "Ba đã mở khóa Tivi / hủy hẹn giờ tắt. Chúc con xem vui vẻ nhé ❤️"
                        : cleanMsg;
                showBannerOverlay("🔓 ĐÃ MỞ KHÓA GIỜ XEM", msg, 8, false);
                CloudTunnelClient.publishStateSoon();
                return;
            }

            if ("lock_now".equals(act)) {
                countdownEndElapsedMs = 0L;
                hideCountdownOverlay();
                if (appContext != null) {
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putLong(KEY_TEMP_UNLOCK_UNTIL, 0L).apply();
                }
                String msg = cleanMsg.isEmpty()
                        ? "Đã hết giờ xem Tivi rồi con nhé! Mình tắt Tivi nghỉ ngơi thôi ❤️"
                        : cleanMsg;
                activeWarningText = msg;
                executeLockAndSleep(msg);
                CloudTunnelClient.publishStateSoon();
                return;
            }

            if ("add".equals(act)) {
                int addMin = Math.max(1, minutes);
                if (isScheduleEnabled() && isWithinScheduleWindow()) {
                    grantTemporaryUnlockMinutes(Math.max(60, addMin + 5));
                }
                setLocked(false, null);
                hideFullScreenLockOverlay();
                long now = SystemClock.elapsedRealtime();
                if (countdownEndElapsedMs > now) {
                    countdownEndElapsedMs += addMin * 60_000L;
                } else {
                    countdownEndElapsedMs = now + addMin * 60_000L;
                }
                warned60s = false;
                warned15s = false;
                long remMin = Math.max(1L, (getRemainingSeconds() + 30L) / 60L);
                String msg = cleanMsg.isEmpty()
                        ? ("Ba vừa cộng thêm " + addMin + " phút! Tivi sẽ tự nghỉ sau " + remMin + " phút nữa con nhé ❤️")
                        : cleanMsg;
                activeWarningText = msg;
                showBannerOverlay("💌 LỜI NHẮN TỪ BA", msg, 12, false);
                updateCountdownOverlay(getRemainingSeconds());
                CloudTunnelClient.publishStateSoon();
                return;
            }

            if ("timer".equals(act) && minutes > 0) {
                if (isScheduleEnabled() && isWithinScheduleWindow()) {
                    grantTemporaryUnlockMinutes(Math.max(60, minutes + 5));
                }
                setLocked(false, null);
                hideFullScreenLockOverlay();
                countdownEndElapsedMs = SystemClock.elapsedRealtime() + (minutes * 60_000L);
                warned60s = minutes <= 1;
                warned15s = false;
                String msg = cleanMsg.isEmpty()
                        ? ("Con xem thêm " + minutes + " phút nữa nhé! Hết giờ Tivi sẽ tự nghỉ ngơi ❤️")
                        : cleanMsg;
                activeWarningText = msg;
                showBannerOverlay("💌 LỜI NHẮN TỪ BA (TẮT SAU " + minutes + " PHÚT)", msg, 14, false);
                updateCountdownOverlay(getRemainingSeconds());
                CloudTunnelClient.publishStateSoon();
                return;
            }

            // Default: message only
            String msg = cleanMsg.isEmpty()
                    ? "Con ngồi xa màn hình để bảo vệ mắt nhé ❤️"
                    : cleanMsg;
            showBannerOverlay("💌 LỜI NHẮN TỪ BA", msg, 14, false);
            CloudTunnelClient.publishStateSoon();
        });
    }

    private static final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                onTick();
            } catch (Throwable ignored) {
            } finally {
                mainHandler.postDelayed(this, 1000L);
            }
        }
    };

    private static int tickCounter = 0;

    private static void onTick() {
        tickCounter++;
        if (tickCounter % 15 == 0 && appContext != null) {
            ensureAdbAlwaysOn(appContext);
        }

        // 0. Scheduled Bedtime Auto-Lock check (U3)
        if (appContext != null) {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean autoLocked = sp.getBoolean(KEY_AUTO_LOCKED_BY_SCHED, false);
            if (isScheduleActiveNow() && !isLocked() && countdownEndElapsedMs <= 0L) {
                String schedMsg = "Đã đến giờ đi ngủ (" + getScheduleStart() + " – " + getScheduleEnd() + "), hẹn con sáng mai nhé ❤️";
                sp.edit()
                        .putBoolean(KEY_LOCKED, true)
                        .putBoolean(KEY_AUTO_LOCKED_BY_SCHED, true)
                        .putString(KEY_LOCK_MESSAGE, schedMsg)
                        .apply();
                hushBackgroundPlayers(appContext);
                bringLauncherHomeToFront();
                showFullScreenLockOverlay();
                CloudTunnelClient.publishStateSoon();
            } else if (autoLocked && isLocked() && !isWithinScheduleWindow()) {
                // Morning arrived: automatically lift bedtime schedule lock
                sp.edit()
                        .putBoolean(KEY_LOCKED, false)
                        .putBoolean(KEY_AUTO_LOCKED_BY_SCHED, false)
                        .apply();
                hideFullScreenLockOverlay();
                CloudTunnelClient.publishStateSoon();
            }
        }

        // 1. Handle active countdown
        if (countdownEndElapsedMs > 0L) {
            long remSec = getRemainingSeconds();
            if (remSec <= 0L) {
                countdownEndElapsedMs = 0L;
                hideCountdownOverlay();
                String finalMsg = activeWarningText.isEmpty()
                        ? "Đã hết giờ xem Tivi rồi con nhé! Hẹn con lần sau ❤️"
                        : activeWarningText;
                executeLockAndSleep(finalMsg);
                CloudTunnelClient.publishStateSoon();
                return;
            }

            updateCountdownOverlay(remSec);

            if (remSec <= 60L && !warned60s) {
                warned60s = true;
                showBannerOverlay("⏳ CÒN 1 PHÚT CUỐI",
                        "Chỉ còn 1 phút nữa là Tivi tự nghỉ rồi, con chuẩn bị tạm biệt nhân vật nhé ❤️",
                        10, false);
            } else if (remSec <= 15L && !warned15s) {
                warned15s = true;
                showBannerOverlay("⏳ TIVI SẮP NGHỈ (" + remSec + " GIÂY)",
                        "Tạm biệt con gái yêu, Tivi chuẩn bị tắt màn hình nghỉ ngơi đây ❤️",
                        15, true);
            }
        }

        // 2. Enforce parental lock if active (prevents turning TV back on to watch SmartTube and shows PIN pad)
        if (isLocked() && appContext != null) {
            showFullScreenLockOverlay();
            updateLockOverlayUi();
            long now = SystemClock.uptimeMillis();
            if (now - lastLockEnforceAt >= 2000L) {
                MediaMonitorService.NowPlaying np = MediaMonitorService.getNowPlaying(appContext, lastKnownFgPkg);
                String fg = MediaMonitorService.detectForegroundPackage(appContext, lastKnownFgPkg);
                boolean inGuardedApp = GUARDED_MEDIA_PKGS.contains(fg)
                        || (np.playing && GUARDED_MEDIA_PKGS.contains(np.pkg));
                if (inGuardedApp) {
                    lastLockEnforceAt = now;
                    hushBackgroundPlayers(appContext);
                    bringLauncherHomeToFront();
                }
            }
        } else {
            hideFullScreenLockOverlay();
        }
    }

    private static void executeLockAndSleep(String reasonMsg) {
        setLocked(true, reasonMsg);
        if (appContext != null) {
            hushBackgroundPlayers(appContext);
            bringLauncherHomeToFront();
        }
        showFullScreenLockOverlay();
        showBannerOverlay("🌙 ĐÃ HẾT GIỜ XEM TIVI RỒI CON NHÉ!",
                reasonMsg + "\n(Tivi sẽ tự tắt màn hình ngay bây giờ...)",
                8, true);
        // Put TV to sleep after 3.2 seconds so the child sees the friendly goodbye message first
        mainHandler.postDelayed(() -> {
            if (!isLocked()) return;
            if (appContext != null) {
                hushBackgroundPlayers(appContext);
            }
            sendSleepKey();
        }, 3200L);
    }

    private static void bringLauncherHomeToFront() {
        if (appContext == null) return;
        AccessibilityService svc = a11yRef.get();
        if (svc != null) {
            try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); } catch (Throwable ignored) {}
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(appContext.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            appContext.startActivity(home);
        } catch (Throwable ignored) {}
    }

    private static void sendSleepKey() {
        new Thread(() -> {
            Process proc = null;
            try {
                // KEYCODE_SLEEP (223) puts Xiaomi TV into standby/screen-off cleanly
                proc = Runtime.getRuntime().exec(new String[]{"/system/bin/input", "keyevent", String.valueOf(KeyEvent.KEYCODE_SLEEP)});
                try { proc.getOutputStream().close(); } catch (Throwable ignored) {}
                try { proc.getInputStream().close(); } catch (Throwable ignored) {}
                try { proc.getErrorStream().close(); } catch (Throwable ignored) {}
                Thread.sleep(300L);
            } catch (Throwable ignored) {
            } finally {
                if (proc != null) {
                    try { proc.destroy(); } catch (Throwable ignored) {}
                }
            }
        }, "parental-sleep-key").start();
    }

    private static Context getOverlayContext() {
        AccessibilityService svc = a11yRef.get();
        if (svc != null) return svc;
        return appContext;
    }

    private static void playGentleChime() {
        new Thread(() -> {
            ToneGenerator tg = null;
            try {
                tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
                tg.startTone(ToneGenerator.TONE_PROP_ACK, 260);
                Thread.sleep(320L);
            } catch (Throwable ignored) {
            } finally {
                if (tg != null) {
                    try { tg.release(); } catch (Throwable ignored) {}
                }
            }
        }, "parental-chime").start();
    }

    private static final Runnable hideBannerRunnable = ParentalControlManager::hideBannerOverlay;

    public static void showBannerOverlay(String titleText, String messageText, int durationSec, boolean urgent) {
        Context ctx = getOverlayContext();
        if (ctx == null) return;
        playGentleChime();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            float d = ctx.getResources().getDisplayMetrics().density;

            if (bannerView == null) {
                LinearLayout card = new LinearLayout(ctx);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER_HORIZONTAL);
                int padH = Math.round(28 * d);
                int padV = Math.round(18 * d);
                card.setPadding(padH, padV, padH, padV);

                TextView tvTitle = new TextView(ctx);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                tvTitle.setGravity(Gravity.CENTER);
                card.addView(tvTitle);

                TextView tvMsg = new TextView(ctx);
                tvMsg.setTextColor(Color.WHITE);
                tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
                tvMsg.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                tvMsg.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                msgLp.topMargin = Math.round(6 * d);
                card.addView(tvMsg, msgLp);

                bannerView = card;
                bannerTitleView = tvTitle;
                bannerMsgView = tvMsg;
                attachOverlayView(wm, bannerView, Math.round(680 * d), ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, Math.round(28 * d));
            }

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(urgent ? 0xF2450A0A : 0xF20F172A);
            bg.setCornerRadius(20 * d);
            bg.setStroke(Math.max(2, Math.round(2f * d)), urgent ? 0xFFEF4444 : 0xFFF59E0B);
            bannerView.setBackground(bg);

            bannerTitleView.setTextColor(urgent ? 0xFFFCA5A5 : 0xFFFBBF24);
            bannerTitleView.setText(titleText);
            bannerMsgView.setText(messageText);
            bannerView.setVisibility(View.VISIBLE);

            mainHandler.removeCallbacks(hideBannerRunnable);
            mainHandler.postDelayed(hideBannerRunnable, Math.max(4, durationSec) * 1000L);
        } catch (Throwable ignored) {
        }
    }

    public static void hideBannerOverlay() {
        if (bannerView != null) {
            try {
                bannerView.setVisibility(View.GONE);
            } catch (Throwable ignored) {}
        }
    }

    private static void updateCountdownOverlay(long remSec) {
        Context ctx = getOverlayContext();
        if (ctx == null) return;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            float d = ctx.getResources().getDisplayMetrics().density;

            if (countdownView == null) {
                LinearLayout pill = new LinearLayout(ctx);
                pill.setOrientation(LinearLayout.HORIZONTAL);
                pill.setGravity(Gravity.CENTER_VERTICAL);
                int padH = Math.round(16 * d);
                int padV = Math.round(9 * d);
                pill.setPadding(padH, padV, padH, padV);

                countdownBg = new GradientDrawable();
                countdownBg.setCornerRadius(999 * d);
                pill.setBackground(countdownBg);

                TextView tv = new TextView(ctx);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                pill.addView(tv);

                countdownView = pill;
                countdownTextView = tv;
                attachOverlayView(wm, countdownView, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END,
                        Math.round(32 * d), Math.round(24 * d));
            }

            if (countdownBg != null) {
                if (remSec <= 15L) {
                    countdownBg.setColor((remSec % 2 == 0) ? 0xEE991B1B : 0xEE7F1D1D);
                    countdownBg.setStroke(Math.max(2, Math.round(1.5f * d)), 0xFFF87171);
                } else if (remSec <= 60L) {
                    countdownBg.setColor(0xEE7C2D12);
                    countdownBg.setStroke(Math.max(2, Math.round(1.5f * d)), 0xFFFB923C);
                } else {
                    countdownBg.setColor(0xDD0F172A);
                    countdownBg.setStroke(Math.max(1, Math.round(1.2f * d)), 0xFF38BDF8);
                }
            }
            if (countdownTextView != null) {
                countdownTextView.setText("⏳ Tắt sau " + MediaMonitorService.formatTime(remSec));
            }
            countdownView.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
    }

    private static void hideCountdownOverlay() {
        if (countdownView != null) {
            try {
                countdownView.setVisibility(View.GONE);
            } catch (Throwable ignored) {}
        }
    }

    public static void showFullScreenLockOverlay() {
        Context ctx = getOverlayContext();
        if (ctx == null) return;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            float d = ctx.getResources().getDisplayMetrics().density;

            if (lockOverlayView == null) {
                LinearLayout root = new LinearLayout(ctx);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setGravity(Gravity.CENTER);
                root.setBackgroundColor(0xF6060A14);
                int pad = Math.round(32 * d);
                root.setPadding(pad, pad, pad, pad);

                LinearLayout panel = new LinearLayout(ctx);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setGravity(Gravity.CENTER_HORIZONTAL);
                int pPadH = Math.round(36 * d);
                int pPadV = Math.round(28 * d);
                panel.setPadding(pPadH, pPadV, pPadH, pPadV);
                GradientDrawable panelBg = new GradientDrawable();
                panelBg.setColor(0xFF0F172A);
                panelBg.setCornerRadius(24 * d);
                panelBg.setStroke(Math.max(2, Math.round(1.5f * d)), 0xFF334155);
                panel.setBackground(panelBg);

                TextView tvTitle = new TextView(ctx);
                tvTitle.setText("🌙 TIVI ĐANG TRONG GIỜ NGHỈ NGƠI");
                tvTitle.setTextColor(0xFFFBBF24);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
                tvTitle.setTypeface(UiFont.medium(ctx), Typeface.BOLD);
                tvTitle.setGravity(Gravity.CENTER);
                panel.addView(tvTitle);

                TextView tvMsg = new TextView(ctx);
                tvMsg.setTextColor(0xFFE2E8F0);
                tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                tvMsg.setTypeface(UiFont.regular(ctx));
                tvMsg.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                msgLp.topMargin = Math.round(8 * d);
                panel.addView(tvMsg, msgLp);

                TextView tvDots = new TextView(ctx);
                tvDots.setTextColor(0xFF38BDF8);
                tvDots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
                tvDots.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                tvDots.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dotsLp.topMargin = Math.round(16 * d);
                dotsLp.bottomMargin = Math.round(6 * d);
                panel.addView(tvDots, dotsLp);

                TextView tvStatus = new TextView(ctx);
                tvStatus.setTextColor(0xFF94A3B8);
                tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                tvStatus.setTypeface(UiFont.light(ctx));
                tvStatus.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                stLp.bottomMargin = Math.round(14 * d);
                panel.addView(tvStatus, stLp);

                // 3x4 PIN Keypad Grid
                LinearLayout grid = new LinearLayout(ctx);
                grid.setOrientation(LinearLayout.VERTICAL);
                grid.setGravity(Gravity.CENTER);
                int btnW = Math.round(76 * d);
                int btnH = Math.round(46 * d);
                int gap = Math.round(6 * d);

                for (int r = 0; r < 4; r++) {
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER);
                    for (int c = 0; c < 3; c++) {
                        final int idx = r * 3 + c;
                        TextView keyTv = new TextView(ctx);
                        keyTv.setText(KEYPAD_LABELS[idx]);
                        keyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
                        keyTv.setTypeface(UiFont.medium(ctx), Typeface.BOLD);
                        keyTv.setGravity(Gravity.CENTER);
                        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(btnW, btnH);
                        klp.setMargins(gap, gap, gap, gap);
                        row.addView(keyTv, klp);
                        lockKeyViews[idx] = keyTv;
                    }
                    grid.addView(row);
                }
                panel.addView(grid);

                TextView tvFooter = new TextView(ctx);
                tvFooter.setText("Phụ huynh dùng phím điều hướng ▲▼◄► + OK để nhập PIN, hoặc mở khóa tại https://tv.tqd.me");
                tvFooter.setTextColor(0xFF64748B);
                tvFooter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                tvFooter.setTypeface(UiFont.light(ctx));
                tvFooter.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                fLp.topMargin = Math.round(14 * d);
                panel.addView(tvFooter, fLp);

                root.addView(panel, new LinearLayout.LayoutParams(
                        Math.round(540 * d), ViewGroup.LayoutParams.WRAP_CONTENT));

                lockOverlayView = root;
                lockTitleView = tvTitle;
                lockMsgView = tvMsg;
                lockDotsView = tvDots;
                lockStatusView = tvStatus;

                attachOverlayView(wm, lockOverlayView,
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER, 0, 0);
            }

            updateLockOverlayUi();
            lockOverlayView.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
    }

    public static void hideFullScreenLockOverlay() {
        if (lockOverlayView != null) {
            try {
                lockOverlayView.setVisibility(View.GONE);
            } catch (Throwable ignored) {}
        }
    }

    private static void updateLockOverlayUi() {
        if (lockOverlayView == null || appContext == null) return;
        float d = appContext.getResources().getDisplayMetrics().density;
        if (lockMsgView != null) {
            lockMsgView.setText(getLockMessage());
        }
        if (lockDotsView != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) sb.append("   ");
                sb.append(i < pinBuffer.length() ? "●" : "○");
            }
            lockDotsView.setText(sb.toString());
        }
        if (lockStatusView != null) {
            long nowEl = SystemClock.elapsedRealtime();
            if (pinCooldownUntilElapsed > nowEl) {
                long rem = (pinCooldownUntilElapsed - nowEl + 999L) / 1000L;
                lockStatusView.setTextColor(0xFFF87171);
                lockStatusView.setText("⚠️ Nhập sai 5 lần. Vui lòng thử lại sau " + rem + " giây");
            } else if (pinFailCount > 0 && pinBuffer.isEmpty()) {
                lockStatusView.setTextColor(0xFFF87171);
                lockStatusView.setText("❌ Mã PIN chưa đúng (" + pinFailCount + "/5). Mời phụ huynh nhập lại");
            } else {
                lockStatusView.setTextColor(0xFF94A3B8);
                lockStatusView.setText("Nhập 4 số mã PIN phụ huynh để mở khóa Tivi (60 phút)");
            }
        }
        int activeIdx = pinCursorRow * 3 + pinCursorCol;
        for (int i = 0; i < 12; i++) {
            TextView tv = lockKeyViews[i];
            if (tv == null) continue;
            boolean focused = (i == activeIdx);
            GradientDrawable kbg = new GradientDrawable();
            kbg.setCornerRadius(12 * d);
            if (focused) {
                kbg.setColor(0xFF0284C7);
                kbg.setStroke(Math.max(2, Math.round(2f * d)), 0xFF7DD3FC);
                tv.setTextColor(Color.WHITE);
            } else {
                kbg.setColor(0xFF1E293B);
                kbg.setStroke(Math.max(1, Math.round(1f * d)), 0xFF334155);
                tv.setTextColor(0xFFE2E8F0);
            }
            tv.setBackground(kbg);
        }
    }

    /**
     * Intercepts remote keys when the TV is locked so the parent can enter the 4-digit PIN on TV
     * using D-Pad + OK (or direct number keys) while blocking Home/Back/App keys for the child.
     */
    public static boolean handleLockKeyEvent(KeyEvent event) {
        if (!isLocked()) return false;
        int code = event.getKeyCode();
        // Allow power/sleep and volume down/mute to pass through to hardware
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_SLEEP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        showFullScreenLockOverlay();
        long nowEl = SystemClock.elapsedRealtime();
        if (pinCooldownUntilElapsed > nowEl) {
            updateLockOverlayUi();
            return true;
        }

        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            pinCursorRow = (pinCursorRow + 3) % 4;
            updateLockOverlayUi();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            pinCursorRow = (pinCursorRow + 1) % 4;
            updateLockOverlayUi();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            pinCursorCol = (pinCursorCol + 2) % 3;
            updateLockOverlayUi();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            pinCursorCol = (pinCursorCol + 1) % 3;
            updateLockOverlayUi();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_BACK) {
            if (!pinBuffer.isEmpty()) {
                pinBuffer = pinBuffer.substring(0, pinBuffer.length() - 1);
                updateLockOverlayUi();
            }
            return true;
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            char digit = (char) ('0' + (code - KeyEvent.KEYCODE_0));
            appendPinDigitOrSubmit(String.valueOf(digit));
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            int idx = pinCursorRow * 3 + pinCursorCol;
            if (idx >= 0 && idx < KEYPAD_LABELS.length) {
                appendPinDigitOrSubmit(KEYPAD_LABELS[idx]);
            }
            return true;
        }
        // Consume all other keys (Home, App shortcuts, Voice, Volume Up) while locked
        return true;
    }

    private static void appendPinDigitOrSubmit(String label) {
        if ("⌫".equals(label)) {
            if (!pinBuffer.isEmpty()) {
                pinBuffer = pinBuffer.substring(0, pinBuffer.length() - 1);
            }
            updateLockOverlayUi();
            return;
        }
        if ("✓".equals(label)) {
            checkPinSubmission();
            return;
        }
        if (pinBuffer.length() < 4) {
            pinBuffer += label;
            updateLockOverlayUi();
            if (pinBuffer.length() == 4) {
                mainHandler.postDelayed(ParentalControlManager::checkPinSubmission, 160L);
            }
        }
    }

    private static void checkPinSubmission() {
        String candidate = pinBuffer;
        pinBuffer = "";
        if (verifyPin(appContext, candidate)) {
            pinFailCount = 0;
            pinCooldownUntilElapsed = 0L;
            grantTemporaryUnlockMinutes(60);
            setLocked(false, null);
            hideFullScreenLockOverlay();
            showBannerOverlay("🔓 ĐÃ MỞ KHÓA BẰNG MÃ PIN",
                    "Chào phụ huynh! Tivi đã được mở khóa trong 60 phút ❤️", 7, false);
            CloudTunnelClient.publishStateSoon();
        } else {
            pinFailCount++;
            if (pinFailCount >= 5) {
                pinCooldownUntilElapsed = SystemClock.elapsedRealtime() + 60_000L;
                pinFailCount = 0;
            }
            updateLockOverlayUi();
        }
    }

    private static void attachOverlayView(WindowManager wm, View view, int w, int h,
                                          int gravity, int x, int y) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        int[] candidateTypes = new int[]{
                Build.VERSION.SDK_INT >= 22 ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_TOAST,
                WindowManager.LayoutParams.TYPE_TOAST,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.TYPE_PHONE
        };
        for (int type : candidateTypes) {
            try {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        w, h, type, flags, PixelFormat.TRANSLUCENT);
                lp.gravity = gravity;
                lp.x = x;
                lp.y = y;
                wm.addView(view, lp);
                return;
            } catch (Throwable ignored) {
            }
        }
    }
}
