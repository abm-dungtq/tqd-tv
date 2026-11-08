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
import android.widget.Toast;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView connStatusView;
    private TextView pairingCodeView;
    private TextView pairingStatusBadge;
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
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            }
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {}
        DeviceIdentityManager.init(this);
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
        DeviceIdentityManager.triggerImmediateSync();
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
        boolean isTv = DeviceIdentityManager.isTelevision(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0B0D13);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        root.setPadding(pad, pad, pad, pad);

        // 1. Pairing Code & Account Sync Card
        LinearLayout pairCard = createCard(d, 0xFF141E33, 0xFF38BDF8);

        TextView title = new TextView(this);
        title.setText(isTv ? "🛡️ TQD KidGuard — Quản Lý Android TV" : "🛡️ TQD KidGuard — Quản Lý Máy Tính Bảng");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        pairCard.addView(title);

        connStatusView = new TextView(this);
        connStatusView.setText("Đang kết nối tới Server https://tv.tqd.me ...");
        connStatusView.setTextColor(0xFF93C5FD);
        connStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Math.round(4 * d);
        pairCard.addView(connStatusView, subLp);

        // Big 6-digit Pairing Code Box
        LinearLayout codeBox = new LinearLayout(this);
        codeBox.setOrientation(LinearLayout.VERTICAL);
        codeBox.setGravity(Gravity.CENTER);
        int cPadV = Math.round(14 * d);
        int cPadH = Math.round(18 * d);
        codeBox.setPadding(cPadH, cPadV, cPadH, cPadV);
        GradientDrawable codeBg = new GradientDrawable();
        codeBg.setColor(0xFF0B0F1A);
        codeBg.setCornerRadius(14 * d);
        codeBg.setStroke(Math.max(1, Math.round(1.5f * d)), 0xFFF59E0B);
        codeBox.setBackground(codeBg);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = Math.round(12 * d);
        cbLp.bottomMargin = Math.round(10 * d);

        TextView codeLabel = new TextView(this);
        codeLabel.setText("🔗 MÃ GHÉP NỐI THIẾT BỊ (NHẬP TRÊN WEB BỐ MẸ)");
        codeLabel.setTextColor(0xFFFCD34D);
        codeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        codeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        codeLabel.setGravity(Gravity.CENTER);
        codeBox.addView(codeLabel);

        pairingCodeView = new TextView(this);
        pairingCodeView.setText(DeviceIdentityManager.getFormattedPairingCode(this));
        pairingCodeView.setTextColor(0xFFFFFFFF);
        pairingCodeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        pairingCodeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pairingCodeView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pcLp.topMargin = Math.round(4 * d);
        pcLp.bottomMargin = Math.round(6 * d);
        codeBox.addView(pairingCodeView, pcLp);

        pairingStatusBadge = new TextView(this);
        pairingStatusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        pairingStatusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        pairingStatusBadge.setGravity(Gravity.CENTER);
        int bPadH = Math.round(12 * d);
        int bPadV = Math.round(5 * d);
        pairingStatusBadge.setPadding(bPadH, bPadV, bPadH, bPadV);
        codeBox.addView(pairingStatusBadge);

        pairCard.addView(codeBox, cbLp);

        TextView guide = new TextView(this);
        guide.setText("👉 Bước 1: Mở https://tv.tqd.me trên điện thoại của Bố/Mẹ và Đăng ký / Đăng nhập tài khoản.\n"
                + "👉 Bước 2: Bấm nút \"➕ Thêm Thiết Bị\" trên Web rồi nhập mã 6 chữ số ở trên.\n"
                + "👉 Bước 3: Server sẽ tự động đồng bộ kết nối với " + (isTv ? "Tivi" : "Máy tính bảng") + " này ngay lập tức.");
        guide.setTextColor(0xFFCBD5E1);
        guide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        guide.setLineSpacing(Math.round(3 * d), 1.0f);
        pairCard.addView(guide);

        // Sync now button (supports both touch & TV D-Pad)
        TextView syncBtn = new TextView(this);
        syncBtn.setText("🔄 Kiểm Tra & Đồng Bộ Kết Nối Ngay");
        syncBtn.setTextColor(Color.WHITE);
        syncBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        syncBtn.setTypeface(Typeface.DEFAULT_BOLD);
        syncBtn.setGravity(Gravity.CENTER);
        int sPad = Math.round(11 * d);
        syncBtn.setPadding(sPad, sPad, sPad, sPad);
        final GradientDrawable syncBg = new GradientDrawable();
        syncBg.setColor(0xFF2563EB);
        syncBg.setCornerRadius(12 * d);
        syncBg.setStroke(Math.max(1, Math.round(1.5f * d)), 0xFF60A5FA);
        syncBtn.setBackground(syncBg);
        syncBtn.setFocusable(true);
        syncBtn.setClickable(true);
        syncBtn.setOnFocusChangeListener((v, hasFocus) -> {
            syncBg.setColor(hasFocus ? 0xFF1D4ED8 : 0xFF2563EB);
            syncBg.setStroke(Math.max(2, Math.round(2.5f * d)), hasFocus ? 0xFFFDE047 : 0xFF60A5FA);
        });
        syncBtn.setOnClickListener(v -> {
            DeviceIdentityManager.triggerImmediateSync();
            TabletCloudClient.publishStateSoon();
            Toast.makeText(this, "Đang đồng bộ mã ghép nối " + DeviceIdentityManager.getPairingCode(this) + " với Server...", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::updateStatusBadges, 900L);
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = Math.round(12 * d);
        pairCard.addView(syncBtn, sbLp);

        root.addView(pairCard);

        // Permissions Section Title
        TextView secTitle = new TextView(this);
        secTitle.setText("CẤP 4 QUYỀN ĐỂ GIÁM SÁT & KHÓA MÀN HÌNH 100%");
        secTitle.setTextColor(0xFF94A3B8);
        secTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        secTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = Math.round(14 * d);
        stLp.bottomMargin = Math.round(8 * d);
        root.addView(secTitle, stLp);

        // 1. Notification Listener
        permNotifBadge = new TextView(this);
        root.addView(createPermissionRow(d,
                "1. Quyền đọc tên Video YouTube đang phát",
                "Cho phép biết chính xác tên video và kênh YouTube / SmartTube / TikTok con đang xem",
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
                "3. Quyền hiện lời nhắn của Bố/Mẹ & Màn hình khóa PIN",
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
                "Tự động thoát ứng dụng bị chặn và hỗ trợ bấm nút Home/Back từ xa",
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
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(15 * d);
        card.setPadding(p, p, p, p);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF151925);
        bg.setCornerRadius(16 * d);
        bg.setStroke(Math.max(1, Math.round(1.2f * d)), 0x33FFFFFF);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(10 * d);
        card.setLayoutParams(lp);

        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener((v, hasFocus) -> {
            bg.setColor(hasFocus ? 0xFF1E293B : 0xFF151925);
            bg.setStroke(Math.max(2, Math.round(2.2f * d)), hasFocus ? 0xFFFDE047 : 0x33FFFFFF);
        });
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
        String devId = DeviceIdentityManager.getDeviceId(this);
        connStatusView.setText((cloud ? "● Cloud Online (ID: " + devId + ")" : "⏳ Đang kết nối Cloud...")
                + (ip.isEmpty() ? "" : ("  •  LAN: " + ip + ":8686")));

        if (pairingCodeView != null) {
            pairingCodeView.setText(DeviceIdentityManager.getFormattedPairingCode(this));
        }
        if (pairingStatusBadge != null) {
            boolean paired = DeviceIdentityManager.isPaired(this);
            String owner = DeviceIdentityManager.getOwnerName(this);
            String devName = DeviceIdentityManager.getPairedDeviceName(this);
            GradientDrawable pBg = new GradientDrawable();
            pBg.setCornerRadius(999 * d);
            if (paired) {
                String label = "✓ ĐÃ ĐỒNG BỘ VỚI: " + (owner.isEmpty() ? "TÀI KHOẢN BỐ/MẸ" : owner.toUpperCase());
                if (!devName.isEmpty()) label += " (" + devName + ")";
                pairingStatusBadge.setText(label);
                pairingStatusBadge.setTextColor(0xFF86EFAC);
                pBg.setColor(0x2622C55E);
                pBg.setStroke(Math.max(1, Math.round(d)), 0xFF22C55E);
            } else {
                pairingStatusBadge.setText("⏳ ĐANG CHỜ GHÉP NỐI • Nhập mã 6 số trên https://tv.tqd.me");
                pairingStatusBadge.setTextColor(0xFFFDE68A);
                pBg.setColor(0x2DF59E0B);
                pBg.setStroke(Math.max(1, Math.round(d)), 0xFFF59E0B);
            }
            pairingStatusBadge.setBackground(pBg);
        }

        styleBadge(permNotifBadge, TabletMonitorService.isNotificationListenerEnabled(this), d);
        styleBadge(permUsageBadge, TabletMonitorService.isUsageStatsGranted(this), d);
        styleBadge(permOverlayBadge, TabletControlManager.canDrawOverlays(this), d);
        styleBadge(permA11yBadge, TabletAccessibilityService.isConnected(), d);
    }

    private void styleBadge(TextView tv, boolean ok, float d) {
        if (tv == null) return;
        tv.setText(ok ? "✓ ĐÃ BẬT" : "⚠️ BẤM ĐỂ BẬT");
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
