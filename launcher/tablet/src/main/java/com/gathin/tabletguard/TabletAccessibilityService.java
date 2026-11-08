package com.gathin.tabletguard;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class TabletAccessibilityService extends AccessibilityService {

    private static volatile WeakReference<TabletAccessibilityService> instanceRef = new WeakReference<>(null);
    private static volatile String lastWindowTitle = "";
    private static volatile String lastWindowChannel = "";
    private static volatile String lastYoutubePkg = "";
    private static long lastScrapeMs = 0L;

    public static TabletAccessibilityService getInstance() {
        return instanceRef.get();
    }

    public static boolean isConnected() {
        return instanceRef.get() != null;
    }

    public static String getScrapedTitle(String pkg) {
        if (pkg != null && pkg.equals(lastYoutubePkg)) {
            return lastWindowTitle;
        }
        return "";
    }

    public static String getScrapedChannel(String pkg) {
        if (pkg != null && pkg.equals(lastYoutubePkg)) {
            return lastWindowChannel;
        }
        return "";
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instanceRef = new WeakReference<>(this);
        TabletControlManager.init(this, this);
        TabletWebServer.ensureRunning(this);
        TabletCloudClient.ensureRunning(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        if (pkg.isEmpty() || "com.android.systemui".equals(pkg) || getPackageName().equals(pkg)) {
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String prev = TabletControlManager.getForegroundPackage();
            TabletControlManager.updateForegroundPackage(pkg);
            TabletControlManager.enforceAppOrDeviceLockIfNeeded(pkg);
            if (!pkg.equals(prev)) {
                TabletCloudClient.publishStateSoon();
            }
        }

        // Scrape video title inside YouTube / YouTube Kids / SmartTube / TikTok if MediaSession lacks metadata
        if (isVideoApp(pkg)) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastScrapeMs > 1500L) {
                lastScrapeMs = now;
                scrapeActiveWindowTexts(pkg);
            }
        }
    }

    private static boolean isVideoApp(String pkg) {
        return "com.google.android.youtube".equals(pkg)
                || "com.google.android.apps.youtube.kids".equals(pkg)
                || "org.smarttube.stable".equals(pkg)
                || "com.ss.android.ugc.trill".equals(pkg)
                || "com.zhiliaoapp.musically".equals(pkg);
    }

    private void scrapeActiveWindowTexts(String pkg) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            List<String> texts = new ArrayList<>();
            collectTexts(root, texts, 0);
            root.recycle();
            String bestTitle = "";
            String bestSub = "";
            for (String t : texts) {
                if (t.length() >= 8 && !isUiBoilerplate(t)) {
                    if (bestTitle.isEmpty()) {
                        bestTitle = t;
                    } else if (bestSub.isEmpty() && !t.equals(bestTitle) && t.length() <= 60) {
                        bestSub = t;
                        break;
                    }
                }
            }
            if (!bestTitle.isEmpty()) {
                lastYoutubePkg = pkg;
                lastWindowTitle = bestTitle;
                lastWindowChannel = bestSub;
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isUiBoilerplate(String s) {
        String low = s.toLowerCase();
        return low.equals("youtube")
                || low.equals("trang chủ")
                || low.equals("home")
                || low.equals("shorts")
                || low.equals("kênh đăng ký")
                || low.equals("subscriptions")
                || low.equals("thư viện")
                || low.equals("library")
                || low.equals("tìm kiếm")
                || low.equals("search")
                || low.startsWith("đăng ký")
                || low.startsWith("chia sẻ")
                || low.matches("^[0-9:\\s/]+$");
    }

    private static void collectTexts(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 14 || out.size() >= 18) return;
        CharSequence t = node.getText();
        if (t != null) {
            String s = t.toString().trim();
            if (!s.isEmpty() && s.length() <= 140) {
                out.add(s);
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count && out.size() < 18; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectTexts(child, out, depth + 1);
                child.recycle();
            }
        }
    }

    public static boolean performAction(String action) {
        TabletAccessibilityService svc = instanceRef.get();
        if (svc == null) return false;
        try {
            if ("back".equals(action)) {
                return svc.performGlobalAction(GLOBAL_ACTION_BACK);
            } else if ("home".equals(action)) {
                return svc.performGlobalAction(GLOBAL_ACTION_HOME);
            } else if ("recents".equals(action) || "menu".equals(action)) {
                return svc.performGlobalAction(GLOBAL_ACTION_RECENTS);
            } else if ("power".equals(action) || "lock_screen".equals(action)) {
                if (Build.VERSION.SDK_INT >= 28) {
                    return svc.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                }
                return svc.performGlobalAction(GLOBAL_ACTION_HOME);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instanceRef = new WeakReference<>(null);
        super.onDestroy();
    }
}
