package com.gathin.tabletguard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView connStatusView;
    private TextView permNotifBadge;
    private TextView permUsageBadge;
    private TextView permOverlayBadge;
    private TextView permA11yBadge;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatusBadges();
            handler.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TabletMonitorService.ensureEnabled(this);
        TabletControlManager.init(this, null);
        TabletWebServer.ensureRunning(this);
        TabletCloudClient.ensureRunning(this);
        TabletGuardService.start(this);
        setContentView(buildDashboardUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusBadges();
        handler.postDelayed(refreshRunnable, 1200L);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private View buildDashboardUi() {
        float d = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0B0D13);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(22 * d);
        root.setPadding(pad, pad, pad, pad);

        // Header Card
        LinearLayout hero = createCard(d, 0xFF161F33, 0xFF3B82F6);
        TextView title = new TextView(this);
        title.setText("🛡️ Quản Lý Máy Tính Bảng TQD");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        hero.addView(title);

        connStatusView = new TextView(this);
        connStatusView.setText("Đang kết nối tới https://tv.tqd.me ...");
        connStatusView.setTextColor(0xFF93C5FD);
        connStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Math.round(6 * d);
        hero.addView(connStatusView, subLp);

        TextView guide = new TextView(this);
        guide.setText("Trên điện thoại của Ba, mở địa chỉ https://tv.tqd.me rồi chọn tab \"📱 Máy Tính Bảng\" ở trên cùng để xem con đang mở YouTube/ứng dụng gì, gửi lời nhắn hoặc hẹn giờ khóa máy.");
        guide.setTextColor(0xFFCBD5E1);
        guide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gLp.topMargin = Math.round(10 * d);
        hero.addView(guide, gLp);
        root.addView(hero);

        // Permissions Section Title
        TextView secTitle = new TextView(this);
        secTitle.setText("CẤP 4 QUYỀN ĐỂ GIÁM SÁT & KHÓA MÀN HÌNH 100%");
        secTitle.setTextColor(0xFF94A3B8);
        secTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        secTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = Math.round(18 * d);
        stLp.bottomMargin = Math.round(8 * d);
        root.addView(secTitle, stLp);

        // 1. Notification Listener
        permNotifBadge = new TextView(this);
        root.addView(createPermissionRow(d,
                "1. Quyền đọc tên Video YouTube đang phát",
                "Cho phép biết chính xác tên video và kênh YouTube / TikTok con đang xem",
                permNotifBadge,
                v -> openIntentSafely(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))));

        // 2. Usage Access
        permUsageBadge = new TextView(this);
        root.addView(createPermissionRow(d,
                "2. Quyền theo dõi ứng dụng & thời gian dùng",
                "Cho phép biết con đang mở ứng dụng gì và đã dùng bao nhiêu phút hôm nay",
                permUsageBadge,
                v -> openIntentSafely(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))));

        // 3. Draw Overlays
        permOverlayBadge = new TextView(this);
        root.addView(createPermissionRow(d,
                "3. Quyền hiện lời nhắn của Ba & Màn hình khóa PIN",
                "Cho phép hiện cảnh báo đếm ngược và khóa màn hình khi hết giờ xem",
                permOverlayBadge,
                v -> {
                    if (Build.VERSION.SDK_INT >= 23) {
                        openIntentSafely(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    }
                }));

        // 4. Accessibility Service
        permA11yBadge = new TextView(this);
        root.addView(createPermissionRow(d,
                "4. Quyền Hỗ trợ tiếp cận (Chặn ứng dụng & Khóa nhanh)",
                "Tự động thoát ứng dụng bị Ba chặn và hỗ trợ bấm nút Home/Back từ xa",
                permA11yBadge,
                v -> openIntentSafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        scroll.addView(root);
        return scroll;
    }

    private LinearLayout createCard(float d, int bgColor, int strokeColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(16 * d);
        box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(16 * d);
        bg.setStroke(Math.max(1, Math.round(1.2f * d)), strokeColor);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(10 * d);
        box.setLayoutParams(lp);
        return box;
    }

    private View createPermissionRow(float d, String titleTxt, String subTxt, TextView badgeView, View.OnClickListener onClick) {
        LinearLayout card = createCard(d, 0xFF151925, 0x33FFFFFF);
        card.setOnClickListener(onClick);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(titleTxt);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(t, tLp);

        int bPadH = Math.round(10 * d);
        int bPadV = Math.round(5 * d);
        badgeView.setPadding(bPadH, bPadV, bPadH, bPadV);
        badgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badgeView.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(badgeView);

        card.addView(top);

        TextView s = new TextView(this);
        s.setText(subTxt);
        s.setTextColor(0xFF94A3B8);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = Math.round(5 * d);
        card.addView(s, sLp);
        return card;
    }

    private void updateStatusBadges() {
        float d = getResources().getDisplayMetrics().density;
        String ip = TabletWebServer.getLocalIpAddress(this);
        boolean cloud = TabletCloudClient.isConnected();
        connStatusView.setText((cloud ? "🟢 Cloud 4G/5G: Đã kết nối (Mã: tablet01)" : "🟠 Cloud 4G/5G: Đang kết nối...")
                + (ip.isEmpty() ? "" : ("  •  Wi-Fi: http://" + ip + ":8686")));

        styleBadge(permNotifBadge, TabletMonitorService.isNotificationListenerEnabled(this), d);
        styleBadge(permUsageBadge, TabletMonitorService.isUsageStatsGranted(this), d);
        styleBadge(permOverlayBadge, TabletControlManager.canDrawOverlays(this), d);
        styleBadge(permA11yBadge, TabletAccessibilityService.isConnected(), d);
    }

    private void styleBadge(TextView tv, boolean ok, float d) {
        if (tv == null) return;
        tv.setText(ok ? "✅ ĐÃ BẬT" : "🟠 CHẠM ĐỂ BẬT");
        tv.setTextColor(ok ? 0xFF86EFAC : 0xFFFDE68A);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999 * d);
        bg.setColor(ok ? 0x2622C55E : 0x2DF59E0B);
        bg.setStroke(Math.max(1, Math.round(d)), ok ? 0xFF22C55E : 0xFFF59E0B);
        tv.setBackground(bg);
    }

    private void openIntentSafely(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored2) {}
        }
    }
}
