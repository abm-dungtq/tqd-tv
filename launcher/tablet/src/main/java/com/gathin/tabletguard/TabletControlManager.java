package com.gathin.tabletguard;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Full Parental Control & Overlay Engine for Android Tablets:
 *  - Shows parent messages on top of YouTube / Games (`📢 Lời nhắn của Ba`)
 *  - Live top-right countdown timer pill (`⏳ Tắt sau 04:59`)
 *  - Full-screen Touch PIN Pad Lock Screen (`🔒 Máy Tính Bảng Đang Khóa Giờ Nghỉ`)
 *  - Per-app blocking (`blockedApps`) & Bedtime auto-schedule (`21:30 → 06:30`).
 */
public final class TabletControlManager {

    private static final String PREFS_NAME = "tablet_parental_control";
    private static final String KEY_LOCKED = "locked";
    private static final String KEY_LOCK_MESSAGE = "lock_message";
    private static final String KEY_PIN = "pin_code";
    private static final String KEY_SCHED_ENABLED = "sched_enabled";
    private static final String KEY_SCHED_START = "sched_start";
    private static final String KEY_SCHED_END = "sched_end";
    private static final String KEY_TEMP_UNLOCK_UNTIL = "temp_unlock_until";
    private static final String KEY_AUTO_LOCKED_BY_SCHED = "auto_locked_by_sched";
    private static final String KEY_BLOCKED_APPS = "blocked_apps";

    public static final String DEFAULT_PIN = "1601";
    public static final String DEFAULT_SCHED_START = "21:30";
    public static final String DEFAULT_SCHED_END = "06:30";

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile Context appContext;
    private static volatile WeakReference<AccessibilityService> a11yRef = new WeakReference<>(null);
    private static volatile boolean initialized = false;
    private static volatile String lastKnownFgPkg = "";

    private static volatile long countdownEndElapsedMs = 0L;
    private static volatile String activeWarningText = "";
    private static volatile boolean warned60s = false;
    private static volatile boolean warned15s = false;

    private static View bannerView;
    private static TextView bannerTitleView;
    private static TextView bannerMsgView;
    private static View countdownView;
    private static TextView countdownTextView;
    private static GradientDrawable countdownBg;

    private static View lockOverlayView;
    private static TextView lockTitleView;
    private static TextView lockMsgView;
    private static TextView lockDotsView;
    private static TextView lockStatusView;
    private static String pinBuffer = "";
    private static int pinFailCount = 0;
    private static long pinCooldownUntilElapsed = 0L;
    private static long lastLockEnforceAt = 0L;

    private TabletControlManager() {}

    public static void init(Context context, AccessibilityService service) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        if (service != null) {
            a11yRef = new WeakReference<>(service);
        }
        if (appContext == null) return;
        TabletMonitorService.ensureEnabled(appContext);
        if (!initialized) {
            initialized = true;
            mainHandler.postDelayed(tickRunnable, 1000L);
        }
    }

    public static boolean canDrawOverlays(Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(ctx);
        }
        return true;
    }

    private static int getOverlayWindowType() {
        if (Build.VERSION.SDK_INT >= 26) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
    }

    public static void updateForegroundPackage(String pkg) {
        if (pkg != null && !pkg.isEmpty()) {
            lastKnownFgPkg = pkg;
        }
    }

    public static String getForegroundPackage() {
        return lastKnownFgPkg;
    }

    private static SharedPreferences prefs() {
        if (appContext == null) return null;
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getPin() {
        SharedPreferences sp = prefs();
        if (sp == null) return DEFAULT_PIN;
        String p = sp.getString(KEY_PIN, DEFAULT_PIN);
        return (p == null || p.length() < 4) ? DEFAULT_PIN : p;
    }

    public static void setPin(Context ctx, String newPin) {
        if (ctx != null && appContext == null) appContext = ctx.getApplicationContext();
        SharedPreferences sp = prefs();
        if (sp != null && newPin != null && newPin.trim().length() >= 4) {
            sp.edit().putString(KEY_PIN, newPin.trim()).apply();
            mainHandler.post(() -> {
                pinBuffer = "";
                updatePinDots();
            });
            TabletCloudClient.publishStateSoon();
        }
    }

    public static boolean isLocked() {
        SharedPreferences sp = prefs();
        return sp != null && sp.getBoolean(KEY_LOCKED, false);
    }

    public static String getLockMessage() {
        SharedPreferences sp = prefs();
        if (sp == null) return "";
        return sp.getString(KEY_LOCK_MESSAGE, "Đã hết giờ dùng máy tính bảng. Con nghỉ ngơi giữ gìn đôi mắt nhé ❤️");
    }

    public static Set<String> getBlockedApps() {
        SharedPreferences sp = prefs();
        if (sp == null) return new HashSet<>();
        Set<String> s = sp.getStringSet(KEY_BLOCKED_APPS, null);
        return s != null ? new HashSet<>(s) : new HashSet<>();
    }

    public static boolean isAppBlocked(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return getBlockedApps().contains(pkg);
    }

    public static void setAppBlocked(String pkg, boolean blocked) {
        if (pkg == null || pkg.isEmpty()) return;
        SharedPreferences sp = prefs();
        if (sp == null) return;
        Set<String> set = getBlockedApps();
        if (blocked) {
            set.add(pkg);
        } else {
            set.remove(pkg);
        }
        sp.edit().putStringSet(KEY_BLOCKED_APPS, set).apply();
        if (blocked && pkg.equals(lastKnownFgPkg)) {
            enforceAppOrDeviceLockIfNeeded(pkg);
        }
        TabletCloudClient.publishStateSoon();
    }

    public static long getRemainingSeconds() {
        if (countdownEndElapsedMs <= 0L) return 0L;
        long remMs = countdownEndElapsedMs - SystemClock.elapsedRealtime();
        return remMs > 0L ? (remMs + 999L) / 1000L : 0L;
    }

    public static String getActiveWarningText() {
        return activeWarningText != null ? activeWarningText : "";
    }

    public static boolean isScheduleEnabled() {
        SharedPreferences sp = prefs();
        return sp != null && sp.getBoolean(KEY_SCHED_ENABLED, false);
    }

    public static String getScheduleStart() {
        SharedPreferences sp = prefs();
        return sp != null ? sp.getString(KEY_SCHED_START, DEFAULT_SCHED_START) : DEFAULT_SCHED_START;
    }

    public static String getScheduleEnd() {
        SharedPreferences sp = prefs();
        return sp != null ? sp.getString(KEY_SCHED_END, DEFAULT_SCHED_END) : DEFAULT_SCHED_END;
    }

    public static void setSchedule(boolean enabled, String startHhMm, String endHhMm) {
        SharedPreferences sp = prefs();
        if (sp == null) return;
        String st = (startHhMm != null && startHhMm.matches("\\d{2}:\\d{2}")) ? startHhMm : DEFAULT_SCHED_START;
        String ed = (endHhMm != null && endHhMm.matches("\\d{2}:\\d{2}")) ? endHhMm : DEFAULT_SCHED_END;
        sp.edit()
                .putBoolean(KEY_SCHED_ENABLED, enabled)
                .putString(KEY_SCHED_START, st)
                .putString(KEY_SCHED_END, ed)
                .putLong(KEY_TEMP_UNLOCK_UNTIL, 0L)
                .apply();
        TabletCloudClient.publishStateSoon();
    }

    public static boolean isScheduleActiveNow() {
        if (!isScheduleEnabled()) return false;
        int startMin = parseHhMmToMinutes(getScheduleStart(), 21 * 60 + 30);
        int endMin = parseHhMmToMinutes(getScheduleEnd(), 6 * 60 + 30);
        Calendar cal = Calendar.getInstance();
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (startMin <= endMin) {
            return nowMin >= startMin && nowMin < endMin;
        } else {
            return nowMin >= startMin || nowMin < endMin;
        }
    }

    private static int parseHhMmToMinutes(String hhmm, int fallback) {
        if (hhmm == null) return fallback;
        String[] parts = hhmm.trim().split(":");
        if (parts.length != 2) return fallback;
        try {
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            if (h >= 0 && h <= 23 && m >= 0 && m <= 59) return h * 60 + m;
        } catch (NumberFormatException ignored) {}
        return fallback;
    }

    public static void handleParentalCommand(String action, int minutes, String customMessage) {
        final String act = action != null ? action.trim().toLowerCase(Locale.ROOT) : "message";
        final String msg = (customMessage != null && !customMessage.trim().isEmpty())
                ? customMessage.trim()
                : defaultMessageFor(act, minutes);

        mainHandler.post(() -> {
            if ("pin".equals(act)) {
                TabletCloudClient.publishStateSoon();
                return;
            }
            SharedPreferences sp = prefs();
            if ("unlock".equals(act)) {
                countdownEndElapsedMs = 0L;
                activeWarningText = "";
                warned60s = false;
                warned15s = false;
                if (sp != null) {
                    long tempUntil = isScheduleActiveNow()
                            ? (System.currentTimeMillis() + 2 * 3600_000L)
                            : 0L;
                    sp.edit()
                            .putBoolean(KEY_LOCKED, false)
                            .putBoolean(KEY_AUTO_LOCKED_BY_SCHED, false)
                            .putLong(KEY_TEMP_UNLOCK_UNTIL, tempUntil)
                            .apply();
                }
                hideCountdownOverlay();
                hideLockOverlay();
                showBannerOverlay("🔓 Ba đã mở khóa Máy tính bảng",
                        customMessage != null && !customMessage.trim().isEmpty()
                                ? customMessage.trim()
                                : "Con có thể tiếp tục sử dụng máy tính bảng nhé ❤️",
                        7000L, false);
            } else if ("lock_now".equals(act)) {
                countdownEndElapsedMs = 0L;
                activeWarningText = msg;
                hideCountdownOverlay();
                triggerLockNow(msg);
            } else if ("timer".equals(act)) {
                int mins = Math.max(1, minutes);
                if (sp != null) {
                    sp.edit().putBoolean(KEY_LOCKED, false).apply();
                }
                hideLockOverlay();
                countdownEndElapsedMs = SystemClock.elapsedRealtime() + mins * 60_000L;
                activeWarningText = msg;
                warned60s = mins <= 1;
                warned15s = false;
                showBannerOverlay("💌 Lời nhắn từ Ba (Còn " + mins + " phút)", msg, 14_000L, true);
                showOrUpdateCountdownOverlay();
            } else if ("add".equals(act)) {
                int addMins = Math.max(1, minutes);
                if (sp != null && sp.getBoolean(KEY_LOCKED, false)) {
                    sp.edit().putBoolean(KEY_LOCKED, false).apply();
                }
                hideLockOverlay();
                long base = Math.max(SystemClock.elapsedRealtime(), countdownEndElapsedMs);
                countdownEndElapsedMs = base + addMins * 60_000L;
                warned60s = false;
                warned15s = false;
                showBannerOverlay("⏳ Ba tặng thêm " + addMins + " phút",
                        "Con xem thêm " + addMins + " phút nữa rồi nghỉ ngơi nhé ❤️", 9000L, false);
                showOrUpdateCountdownOverlay();
            } else {
                activeWarningText = msg;
                showBannerOverlay("💌 Lời nhắn từ Ba", msg, 14_000L, true);
            }
            TabletCloudClient.publishStateSoon();
        });
    }

    private static String defaultMessageFor(String act, int minutes) {
        if ("lock_now".equals(act)) {
            return "Đã hết giờ dùng máy tính bảng. Con nghỉ ngơi giữ gìn đôi mắt nhé ❤️";
        }
        if ("timer".equals(act)) {
            return "Ba nhắn: Con xem thêm " + Math.max(1, minutes) + " phút nữa rồi nghỉ ngơi cho khỏe mắt nhé ❤️";
        }
        return "Ba nhắn: Con ngồi thẳng lưng và giữ khoảng cách an toàn với màn hình nhé ❤️";
    }

    public static void enforceAppOrDeviceLockIfNeeded(String fgPkg) {
        if (isLocked()) {
            long now = SystemClock.uptimeMillis();
            if (now - lastLockEnforceAt > 1500L) {
                lastLockEnforceAt = now;
                TabletMonitorService.pauseAllMedia(appContext);
                TabletAccessibilityService.performAction("home");
                mainHandler.post(() -> showLockOverlay(getLockMessage()));
            }
            return;
        }
        if (fgPkg != null && isAppBlocked(fgPkg)) {
            long now = SystemClock.uptimeMillis();
            if (now - lastLockEnforceAt > 1200L) {
                lastLockEnforceAt = now;
                TabletMonitorService.pauseAllMedia(appContext);
                TabletAccessibilityService.performAction("home");
                String label = TabletMonitorService.resolveAppLabel(appContext, fgPkg);
                mainHandler.post(() -> showBannerOverlay(
                        "🚫 Ứng dụng đang tạm khóa",
                        "Ba đã tạm khóa ứng dụng \"" + label + "\" trên máy tính bảng.",
                        6000L, true));
            }
        }
    }

    private static void triggerLockNow(String message) {
        SharedPreferences sp = prefs();
        if (sp != null) {
            sp.edit()
                    .putBoolean(KEY_LOCKED, true)
                    .putString(KEY_LOCK_MESSAGE, message)
                    .apply();
        }
        TabletMonitorService.pauseAllMedia(appContext);
        TabletAccessibilityService.performAction("home");
        showLockOverlay(message);
        TabletCloudClient.publishStateSoon();
    }

    private static final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable ignored) {}
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private static void tick() {
        SharedPreferences sp = prefs();
        if (sp != null) {
            boolean schedNow = isScheduleActiveNow();
            long tempUnlockUntil = sp.getLong(KEY_TEMP_UNLOCK_UNTIL, 0L);
            boolean tempUnlocked = tempUnlockUntil > System.currentTimeMillis();
            if (schedNow && !tempUnlocked && !sp.getBoolean(KEY_LOCKED, false)) {
                sp.edit().putBoolean(KEY_AUTO_LOCKED_BY_SCHED, true).apply();
                triggerLockNow("🌙 Đã đến giờ đi ngủ (" + getScheduleStart() + " - " + getScheduleEnd() + "). Chúc con ngủ ngon nhé ❤️");
            } else if (!schedNow && sp.getBoolean(KEY_AUTO_LOCKED_BY_SCHED, false)) {
                sp.edit()
                        .putBoolean(KEY_LOCKED, false)
                        .putBoolean(KEY_AUTO_LOCKED_BY_SCHED, false)
                        .apply();
                hideLockOverlay();
                TabletCloudClient.publishStateSoon();
            }
        }

        if (isLocked()) {
            if (lockOverlayView == null) {
                showLockOverlay(getLockMessage());
            }
            return;
        }

        if (countdownEndElapsedMs > 0L) {
            long remSec = getRemainingSeconds();
            if (remSec <= 0L) {
                countdownEndElapsedMs = 0L;
                hideCountdownOverlay();
                triggerLockNow(activeWarningText != null && !activeWarningText.isEmpty()
                        ? activeWarningText
                        : "Đã hết giờ xem! Máy tính bảng tạm nghỉ để bảo vệ mắt cho con nhé ❤️");
            } else {
                showOrUpdateCountdownOverlay();
                if (remSec <= 60L && !warned60s) {
                    warned60s = true;
                    showBannerOverlay("⏰ Còn 1 phút nữa sẽ tắt",
                            "Con chuẩn bị kết thúc video đang xem nhé ❤️", 8000L, true);
                } else if (remSec <= 15L && !warned15s) {
                    warned15s = true;
                    showBannerOverlay("⏳ Còn 15 giây!",
                            "Tạm biệt con nhé, máy tính bảng chuẩn bị khóa giờ nghỉ!", 6000L, true);
                }
            }
        }
    }

    private static void playGentleChime() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 240);
            mainHandler.postDelayed(tg::release, 400L);
        } catch (Throwable ignored) {}
    }

    private static final Runnable hideBannerRunnable = TabletControlManager::hideBannerOverlay;

    private static void showBannerOverlay(String title, String message, long durationMs, boolean chime) {
        if (appContext == null || !canDrawOverlays(appContext)) return;
        if (chime) playGentleChime();
        try {
            WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            float d = appContext.getResources().getDisplayMetrics().density;

            if (bannerView == null) {
                LinearLayout box = new LinearLayout(appContext);
                box.setOrientation(LinearLayout.VERTICAL);
                int padH = Math.round(22 * d);
                int padV = Math.round(16 * d);
                box.setPadding(padH, padV, padH, padV);

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xF2151926);
                bg.setCornerRadius(18 * d);
                bg.setStroke(Math.max(2, Math.round(2f * d)), 0xFFF59E0B);
                box.setBackground(bg);

                bannerTitleView = new TextView(appContext);
                bannerTitleView.setTextColor(0xFFFCD34D);
                bannerTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                bannerTitleView.setTypeface(Typeface.DEFAULT_BOLD);
                box.addView(bannerTitleView);

                bannerMsgView = new TextView(appContext);
                bannerMsgView.setTextColor(Color.WHITE);
                bannerMsgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Math.round(4 * d);
                box.addView(bannerMsgView, lp);

                WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        getOverlayWindowType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT);
                wlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                wlp.y = Math.round(24 * d);
                wm.addView(box, wlp);
                bannerView = box;
            }
            bannerTitleView.setText(title);
            bannerMsgView.setText(message);
            mainHandler.removeCallbacks(hideBannerRunnable);
            mainHandler.postDelayed(hideBannerRunnable, durationMs);
        } catch (Throwable ignored) {}
    }

    private static void hideBannerOverlay() {
        if (bannerView != null && appContext != null) {
            try {
                WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) wm.removeView(bannerView);
            } catch (Throwable ignored) {}
            bannerView = null;
        }
    }

    private static void showOrUpdateCountdownOverlay() {
        if (appContext == null || !canDrawOverlays(appContext)) return;
        long rem = getRemainingSeconds();
        if (rem <= 0L) {
            hideCountdownOverlay();
            return;
        }
        try {
            WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            float d = appContext.getResources().getDisplayMetrics().density;

            if (countdownView == null) {
                LinearLayout pill = new LinearLayout(appContext);
                int padH = Math.round(14 * d);
                int padV = Math.round(8 * d);
                pill.setPadding(padH, padV, padH, padV);

                countdownBg = new GradientDrawable();
                countdownBg.setColor(0xD9111827);
                countdownBg.setCornerRadius(999 * d);
                countdownBg.setStroke(Math.max(1, Math.round(1.5f * d)), 0xFFF59E0B);
                pill.setBackground(countdownBg);

                countdownTextView = new TextView(appContext);
                countdownTextView.setTextColor(0xFFFDE68A);
                countdownTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                countdownTextView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                pill.addView(countdownTextView);

                WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        getOverlayWindowType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                wlp.gravity = Gravity.TOP | Gravity.END;
                wlp.x = Math.round(18 * d);
                wlp.y = Math.round(18 * d);
                wm.addView(pill, wlp);
                countdownView = pill;
            }
            long m = rem / 60L;
            long s = rem % 60L;
            countdownTextView.setText(String.format(Locale.US, "⏳ Nghỉ sau %02d:%02d", m, s));
            if (countdownBg != null) {
                countdownBg.setStroke(Math.max(1, Math.round(1.5f * d)), rem <= 60 ? 0xFFEF4444 : 0xFFF59E0B);
            }
        } catch (Throwable ignored) {}
    }

    private static void hideCountdownOverlay() {
        if (countdownView != null && appContext != null) {
            try {
                WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) wm.removeView(countdownView);
            } catch (Throwable ignored) {}
            countdownView = null;
        }
    }

    private static void showLockOverlay(String message) {
        if (appContext == null || !canDrawOverlays(appContext)) return;
        try {
            WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            float d = appContext.getResources().getDisplayMetrics().density;

            if (lockOverlayView != null) {
                if (lockMsgView != null) lockMsgView.setText(message);
                return;
            }
            pinBuffer = "";

            LinearLayout root = new LinearLayout(appContext);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(0xFA0B0F19);
            int pad = Math.round(28 * d);
            root.setPadding(pad, pad, pad, pad);

            lockTitleView = new TextView(appContext);
            lockTitleView.setText("🌙 ĐÃ ĐẾN GIỜ NGHỈ NGƠI");
            lockTitleView.setTextColor(0xFFFCD34D);
            lockTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            lockTitleView.setTypeface(Typeface.DEFAULT_BOLD);
            lockTitleView.setGravity(Gravity.CENTER);
            root.addView(lockTitleView);

            lockMsgView = new TextView(appContext);
            lockMsgView.setText(message);
            lockMsgView.setTextColor(Color.WHITE);
            lockMsgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            lockMsgView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            msgLp.topMargin = Math.round(10 * d);
            msgLp.bottomMargin = Math.round(16 * d);
            root.addView(lockMsgView, msgLp);

            lockDotsView = new TextView(appContext);
            lockDotsView.setText("○  ○  ○  ○");
            lockDotsView.setTextColor(0xFF60A5FA);
            lockDotsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            lockDotsView.setTypeface(Typeface.DEFAULT_BOLD);
            lockDotsView.setGravity(Gravity.CENTER);
            root.addView(lockDotsView);

            lockStatusView = new TextView(appContext);
            lockStatusView.setText("Nhập mã PIN của Ba để mở khóa (hoặc mở từ tv.tqd.me)");
            lockStatusView.setTextColor(0xFF94A3B8);
            lockStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            lockStatusView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.topMargin = Math.round(6 * d);
            stLp.bottomMargin = Math.round(16 * d);
            root.addView(lockStatusView, stLp);

            GridLayout grid = new GridLayout(appContext);
            grid.setColumnCount(3);
            grid.setRowCount(4);
            String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓"};
            int btnW = Math.round(78 * d);
            int btnH = Math.round(54 * d);
            int margin = Math.round(6 * d);
            for (final String k : keys) {
                TextView btn = new TextView(appContext);
                btn.setText(k);
                btn.setTextColor(Color.WHITE);
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setGravity(Gravity.CENTER);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor("✓".equals(k) ? 0xFF2563EB : 0xFF1E293B);
                bg.setCornerRadius(14 * d);
                bg.setStroke(Math.max(1, Math.round(1.2f * d)), 0x33FFFFFF);
                btn.setBackground(bg);
                btn.setOnClickListener(v -> onPinKeyTap(k));

                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = btnW;
                glp.height = btnH;
                glp.setMargins(margin, margin, margin, margin);
                grid.addView(btn, glp);
            }
            root.addView(grid);

            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    getOverlayWindowType(),
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSLUCENT);
            wlp.gravity = Gravity.CENTER;
            wm.addView(root, wlp);
            lockOverlayView = root;
        } catch (Throwable ignored) {}
    }

    private static void onPinKeyTap(String k) {
        long now = SystemClock.elapsedRealtime();
        if (now < pinCooldownUntilElapsed) {
            long waitSec = (pinCooldownUntilElapsed - now + 999L) / 1000L;
            if (lockStatusView != null) {
                lockStatusView.setText("Nhập sai nhiều lần. Vui lòng đợi " + waitSec + " giây...");
                lockStatusView.setTextColor(0xFFFCA5A5);
            }
            return;
        }
        if ("⌫".equals(k)) {
            if (!pinBuffer.isEmpty()) {
                pinBuffer = pinBuffer.substring(0, pinBuffer.length() - 1);
            }
            updatePinDots();
            return;
        }
        if ("✓".equals(k)) {
            verifyPinNow();
            return;
        }
        if (pinBuffer.length() < 6) {
            pinBuffer += k;
            updatePinDots();
            if (pinBuffer.length() == getPin().length()) {
                verifyPinNow();
            }
        }
    }

    private static void updatePinDots() {
        if (lockDotsView == null) return;
        int targetLen = Math.max(4, getPin().length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targetLen; i++) {
            if (i > 0) sb.append("  ");
            sb.append(i < pinBuffer.length() ? "●" : "○");
        }
        lockDotsView.setText(sb.toString());
    }

    private static void verifyPinNow() {
        if (pinBuffer.equals(getPin())) {
            pinBuffer = "";
            pinFailCount = 0;
            handleParentalCommand("unlock", 0, "Đã mở khóa bằng mã PIN trên máy tính bảng");
        } else {
            pinBuffer = "";
            pinFailCount++;
            updatePinDots();
            if (pinFailCount >= 5) {
                pinCooldownUntilElapsed = SystemClock.elapsedRealtime() + 30_000L;
                pinFailCount = 0;
                if (lockStatusView != null) {
                    lockStatusView.setText("Sai mã PIN 5 lần! Tạm khóa bàn phím 30 giây.");
                    lockStatusView.setTextColor(0xFFFCA5A5);
                }
            } else if (lockStatusView != null) {
                lockStatusView.setText("Mã PIN chưa đúng, vui lòng thử lại!");
                lockStatusView.setTextColor(0xFFFCA5A5);
            }
        }
    }

    private static void hideLockOverlay() {
        if (lockOverlayView != null && appContext != null) {
            try {
                WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) wm.removeView(lockOverlayView);
            } catch (Throwable ignored) {}
            lockOverlayView = null;
        }
    }
}
