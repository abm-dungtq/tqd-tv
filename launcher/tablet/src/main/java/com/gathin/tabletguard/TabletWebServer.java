package com.gathin.tabletguard;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class TabletWebServer {
    public static final int PORT = 8686;

    private static final Object LOCK = new Object();
    private static volatile boolean supervisorStarted = false;
    private static volatile ServerSocket serverSocket;
    private static volatile ExecutorService pool;
    private static volatile Context appContext;
    private static volatile int lastUnmutedVolume = 8;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final List<String> PRIORITY_APPS = Arrays.asList(
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "org.smarttube.stable",
            "com.teamsmart.videomanager.tv",
            "com.google.android.apps.youtube.kids",
            "com.google.android.youtube.tvkids",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.roblox.client.vnggames",
            "com.roblox.client",
            "com.blockblast.vn",
            "com.maxgames.stickwarlegacy",
            "com.maxgames.stickwar3",
            "vn.vtv.vtvgotv",
            "vn.vtv.vtvgo",
            "com.fplay.activity",
            "vn.vieon.gluetv",
            "com.netflix.ninja",
            "com.netflix.mediaclient",
            "org.telegram.messenger",
            "com.duolingo",
            "com.google.android.apps.bard",
            "com.amazon.cloud9",
            "com.android.chrome",
            "com.zing.zalo",
            "com.facebook.katana"
    );

    private TabletWebServer() {}

    public static void ensureRunning(Context ctx) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext();
        }
        TabletControlManager.init(ctx, null);
        TabletCloudClient.ensureRunning(ctx);
        synchronized (LOCK) {
            if (pool == null || pool.isShutdown()) {
                pool = new ThreadPoolExecutor(
                        4, 8, 30L, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(32),
                        new ThreadPoolExecutor.DiscardOldestPolicy());
            }
            if (supervisorStarted && serverSocket != null && !serverSocket.isClosed()) {
                return;
            }
            if (!supervisorStarted) {
                supervisorStarted = true;
                Thread acceptor = new Thread(TabletWebServer::acceptLoop, "tablet-web-acceptor");
                acceptor.setDaemon(true);
                acceptor.start();
            }
        }
    }

    private static void acceptLoop() {
        while (supervisorStarted) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0", PORT), 32);
                serverSocket = ss;
                while (!ss.isClosed()) {
                    final Socket client = ss.accept();
                    ExecutorService exec = pool;
                    if (exec != null && !exec.isShutdown()) {
                        exec.execute(() -> handleClient(client));
                    } else {
                        try { client.close(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (ss != null) {
                    try { ss.close(); } catch (Throwable ignored) {}
                }
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ignored) {}
        }
    }

    private static void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(4000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8), 2048);
            OutputStream out = socket.getOutputStream();

            String reqLine = reader.readLine();
            if (reqLine == null || reqLine.isEmpty()) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String rawUri = parts[1];

            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // drain headers
            }

            if ("OPTIONS".equals(method)) {
                writeResponse(out, "204 No Content", "text/plain", new byte[0]);
                return;
            }

            String path = rawUri;
            String query = "";
            int qIdx = rawUri.indexOf('?');
            if (qIdx >= 0) {
                path = rawUri.substring(0, qIdx);
                query = rawUri.substring(qIdx + 1);
            }
            Map<String, String> params = parseQuery(query);

            if ("/api/status".equals(path)) {
                writeJson(out, buildStatusJson());
            } else if ("/api/key".equals(path)) {
                writeJson(out, handleKey(params.get("code")));
            } else if ("/api/volume".equals(path)) {
                writeJson(out, handleVolume(params.get("level")));
            } else if ("/api/parental".equals(path)) {
                String newPin = params.get("newPin");
                if (newPin != null && newPin.trim().length() >= 4) {
                    TabletControlManager.setPin(appContext, newPin.trim());
                }
                int mins = 0;
                try { mins = Integer.parseInt(params.get("minutes")); } catch (Throwable ignored) {}
                TabletControlManager.handleParentalCommand(params.get("action"), mins, params.get("message"));
                writeJson(out, buildStatusJson());
            } else if ("/api/pin".equals(path)) {
                String newPin = params.get("newPin");
                if (newPin != null && newPin.trim().length() >= 4) {
                    TabletControlManager.setPin(appContext, newPin.trim());
                }
                writeJson(out, buildStatusJson());
            } else if ("/api/schedule".equals(path)) {
                boolean en = "1".equals(params.get("enabled")) || "true".equalsIgnoreCase(params.get("enabled"));
                TabletControlManager.setSchedule(en, params.get("start"), params.get("end"));
                writeJson(out, buildStatusJson());
            } else if ("/api/block_app".equals(path)) {
                boolean b = "1".equals(params.get("blocked")) || "true".equalsIgnoreCase(params.get("blocked"));
                TabletControlManager.setAppBlocked(params.get("pkg"), b);
                writeJson(out, buildStatusJson());
            } else if ("/api/app".equals(path)) {
                writeJson(out, handleApp(params.get("pkg")));
            } else if ("/api/youtube".equals(path)) {
                writeJson(out, handleYoutube(params.get("q")));
            } else {
                String html = "<!DOCTYPE html><html lang='vi'><head><meta charset='utf-8'>"
                        + "<meta http-equiv='refresh' content='0;url=https://tv.tqd.me?device=tablet01'>"
                        + "<title>Quản Lý Máy Tính Bảng TQD</title></head>"
                        + "<body style='background:#0b0d13;color:#fff;font-family:sans-serif;text-align:center;padding:40px'>"
                        + "<h3>Đang mở Bảng điều khiển Máy tính bảng TQD...</h3>"
                        + "<p><a href='https://tv.tqd.me?device=tablet01' style='color:#60a5fa'>Nhấn vào đây nếu không tự chuyển hướng</a></p>"
                        + "</body></html>";
                writeResponse(out, "200 OK", "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        } finally {
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeJson(OutputStream out, String json) throws Exception {
        writeResponse(out, "200 OK", "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeResponse(OutputStream out, String status, String contentType, byte[] body) throws Exception {
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq > 0 ? pair.substring(0, eq) : pair;
            String v = eq > 0 ? pair.substring(eq + 1) : "";
            try { k = URLDecoder.decode(k, "UTF-8"); } catch (Throwable ignored) {}
            try { v = URLDecoder.decode(v, "UTF-8"); } catch (Throwable ignored) {}
            map.put(k, v);
        }
        return map;
    }

    private static boolean isMusicMuted(AudioManager am) {
        if (am == null) return false;
        try {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return true;
            if (Build.VERSION.SDK_INT >= 23) return am.isStreamMute(AudioManager.STREAM_MUSIC);
        } catch (Throwable ignored) {}
        return false;
    }

    public static String handleVolume(String levelStr) {
        if (levelStr == null || appContext == null) return "{\"ok\":false}";
        int target;
        try {
            target = Integer.parseInt(levelStr.trim());
        } catch (NumberFormatException e) {
            return "{\"ok\":false}";
        }
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int before = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int clamped = Math.max(0, Math.min(max, target));
                if (before > 0 && clamped == 0) {
                    lastUnmutedVolume = before;
                } else if (clamped > 0) {
                    lastUnmutedVolume = clamped;
                    try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0); } catch (Throwable ignored) {}
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, AudioManager.FLAG_SHOW_UI);
                if (clamped == 0) {
                    try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0); } catch (Throwable ignored) {}
                }
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                boolean muted = isMusicMuted(am);
                TabletCloudClient.publishStateSoon();
                return String.format(Locale.US, "{\"ok\":true,\"vol\":%d,\"max\":%d,\"muted\":%b}", cur, max, muted);
            }
        } catch (Throwable ignored) {}
        return "{\"ok\":false}";
    }

    public static String handleKey(String code) {
        if (code == null || code.isEmpty() || appContext == null) return "{\"ok\":false}";
        String c = code.trim().toLowerCase(Locale.ROOT);
        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);

        if ("volup5".equals(c) || "voldown5".equals(c)) {
            if (am != null) {
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int next = Math.max(0, Math.min(max, cur + ("volup5".equals(c) ? 5 : -5)));
                return handleVolume(String.valueOf(next));
            }
        }
        if ("volup".equals(c) || "voldown".equals(c)) {
            if (am != null) {
                int before = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int target = Math.max(0, Math.min(max, before + ("volup".equals(c) ? 1 : -1)));
                if (target > 0) {
                    lastUnmutedVolume = target;
                    try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0); } catch (Throwable ignored) {}
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                boolean muted = isMusicMuted(am);
                TabletCloudClient.publishStateSoon();
                return String.format(Locale.US, "{\"ok\":true,\"vol\":%d,\"max\":%d,\"muted\":%b}", cur, max, muted);
            }
        }
        if ("mute".equals(c)) {
            if (am != null) {
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                boolean currentlyMuted = isMusicMuted(am);
                if (!currentlyMuted && cur > 0) {
                    lastUnmutedVolume = cur;
                    try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); } catch (Throwable ignored) {}
                    try { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI); } catch (Throwable ignored) {}
                } else {
                    int restore = (lastUnmutedVolume > 0 && lastUnmutedVolume <= max) ? lastUnmutedVolume : Math.min(max, 8);
                    try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0); } catch (Throwable ignored) {}
                    try { am.setStreamVolume(AudioManager.STREAM_MUSIC, restore, AudioManager.FLAG_SHOW_UI); } catch (Throwable ignored) {}
                }
                int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                boolean muted = isMusicMuted(am);
                TabletCloudClient.publishStateSoon();
                return String.format(Locale.US, "{\"ok\":true,\"vol\":%d,\"max\":%d,\"muted\":%b}", vol, max, muted);
            }
        }
        if ("playpause".equals(c) || "prev".equals(c) || "next".equals(c) || "rew".equals(c) || "ff".equals(c)) {
            if (am != null) {
                int kc = "playpause".equals(c) ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        : "prev".equals(c) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        : "next".equals(c) ? KeyEvent.KEYCODE_MEDIA_NEXT
                        : "rew".equals(c) ? KeyEvent.KEYCODE_MEDIA_REWIND
                        : KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, kc, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, kc, 0));
                TabletCloudClient.publishStateSoon();
                return "{\"ok\":true}";
            }
        }
        if ("back".equals(c) || "home".equals(c) || "menu".equals(c) || "power".equals(c)) {
            boolean ok = TabletAccessibilityService.performAction(c);
            if ("home".equals(c) && !ok) {
                mainHandler.post(() -> {
                    try {
                        Intent h = new Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        appContext.startActivity(h);
                    } catch (Throwable ignored) {}
                });
                ok = true;
            }
            TabletCloudClient.publishStateSoon();
            return ok ? "{\"ok\":true}" : "{\"ok\":false}";
        }
        return "{\"ok\":true}";
    }

    public static String handleApp(String pkg) {
        if (pkg == null || pkg.trim().isEmpty() || appContext == null) return "{\"ok\":false}";
        final String target = pkg.trim();
        mainHandler.post(() -> {
            try {
                Intent launch = appContext.getPackageManager().getLaunchIntentForPackage(target);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(launch);
                }
            } catch (Throwable ignored) {}
        });
        TabletCloudClient.publishStateSoon();
        return "{\"ok\":true}";
    }

    public static String handleYoutube(String query) {
        if (query == null || query.trim().isEmpty() || appContext == null) return "{\"ok\":false}";
        final String q = query.trim();
        mainHandler.post(() -> {
            try {
                String url;
                String lower = q.toLowerCase(Locale.ROOT);
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    url = q;
                } else {
                    url = "https://www.youtube.com/results?search_query=" + Uri.encode(q);
                }
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(view);
            } catch (Throwable ignored) {}
        });
        TabletCloudClient.publishStateSoon();
        return "{\"ok\":true}";
    }

    public static String buildStatusJson() {
        int vol = 0, max = 15;
        boolean muted = false;
        long ramFreeMb = 0, ramTotalMb = 0, storageFreeMb = 0, storageTotalMb = 0;
        if (appContext != null) {
            try {
                AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    muted = isMusicMuted(am);
                }
            } catch (Throwable ignored) {}
            try {
                ActivityManager actMgr = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
                if (actMgr != null) {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    actMgr.getMemoryInfo(mi);
                    ramFreeMb = mi.availMem / (1024L * 1024L);
                    ramTotalMb = mi.totalMem / (1024L * 1024L);
                }
            } catch (Throwable ignored) {}
            try {
                StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
                storageFreeMb = stat.getAvailableBytes() / (1024L * 1024L);
                storageTotalMb = stat.getTotalBytes() / (1024L * 1024L);
            } catch (Throwable ignored) {}
        }

        String ip = getLocalIpAddress(appContext);
        String model = DeviceIdentityManager.getFriendlyModel(appContext);
        String devType = DeviceIdentityManager.getDeviceType(appContext);
        String devId = DeviceIdentityManager.getDeviceId(appContext);
        String pairCode = DeviceIdentityManager.getPairingCode(appContext);
        boolean isPaired = DeviceIdentityManager.isPaired(appContext);
        String ownerName = DeviceIdentityManager.getOwnerName(appContext);
        String pairedName = DeviceIdentityManager.getPairedDeviceName(appContext);
        int batteryPct = TabletMonitorService.getBatteryPercent(appContext);
        boolean charging = TabletMonitorService.isCharging(appContext);
        boolean screenOn = TabletMonitorService.isScreenOn(appContext);
        long todayMin = TabletMonitorService.getTodayScreenMinutes(appContext);

        TabletMonitorService.NowPlaying np = TabletMonitorService.getNowPlaying(
                appContext, TabletControlManager.getForegroundPackage());
        boolean locked = TabletControlManager.isLocked();
        long remSec = TabletControlManager.getRemainingSeconds();
        String warnText = TabletControlManager.getActiveWarningText();
        String lockMsg = TabletControlManager.getLockMessage();
        boolean schedOn = TabletControlManager.isScheduleEnabled();
        String schedStart = TabletControlManager.getScheduleStart();
        String schedEnd = TabletControlManager.getScheduleEnd();
        boolean schedActive = TabletControlManager.isScheduleActiveNow();
        boolean cloudOn = TabletCloudClient.isConnected();

        String weatherSummary = "tv".equals(devType)
                ? String.format(Locale.US, "📺 Android TV · ⏱ Hôm nay: %d phút", todayMin)
                : String.format(Locale.US,
                        "🔋 Pin %d%%%s · ⏱ Hôm nay: %d phút",
                        Math.max(0, batteryPct),
                        charging ? " (Đang sạc)" : "",
                        todayMin);

        String appsJson = buildAppsListJson();

        return String.format(Locale.US,
                "{\"ok\":true,\"deviceType\":\"%s\",\"appFlavor\":\"guard\",\"model\":\"%s\",\"ip\":\"%s\",\"port\":%d,"
                        + "\"vol\":%d,\"max\":%d,\"muted\":%b,"
                        + "\"batteryPct\":%d,\"charging\":%b,\"screenOn\":%b,\"todayScreenMin\":%d,"
                        + "\"adbEnabled\":true,\"cloudConnected\":%b,\"deviceId\":\"%s\","
                        + "\"pairingCode\":\"%s\",\"paired\":%b,\"ownerName\":\"%s\",\"pairedDeviceName\":\"%s\","
                        + "\"weather\":\"%s\","
                        + "\"ramFreeMb\":%d,\"ramTotalMb\":%d,\"storageFreeMb\":%d,\"storageTotalMb\":%d,"
                        + "\"nowPlaying\":{\"hasMedia\":%b,\"playing\":%b,\"pkg\":\"%s\",\"appLabel\":\"%s\","
                        + "\"title\":\"%s\",\"channel\":\"%s\",\"positionSec\":%d,\"durationSec\":%d},"
                        + "\"parental\":{\"locked\":%b,\"remainingSec\":%d,\"warningText\":\"%s\",\"lockMessage\":\"%s\","
                        + "\"scheduleEnabled\":%b,\"scheduleStart\":\"%s\",\"scheduleEnd\":\"%s\",\"scheduleActiveNow\":%b},"
                        + "\"tabletApps\":%s}",
                escapeJson(devType), escapeJson(model), escapeJson(ip), PORT,
                vol, max, muted,
                batteryPct, charging, screenOn, todayMin,
                cloudOn, escapeJson(devId),
                escapeJson(pairCode), isPaired, escapeJson(ownerName), escapeJson(pairedName),
                escapeJson(weatherSummary),
                ramFreeMb, ramTotalMb, storageFreeMb, storageTotalMb,
                np.hasMedia, np.playing, escapeJson(np.pkg), escapeJson(np.appLabel),
                escapeJson(np.title), escapeJson(np.channel), np.positionSec, np.durationSec,
                locked, remSec, escapeJson(warnText), escapeJson(lockMsg),
                schedOn, escapeJson(schedStart), escapeJson(schedEnd), schedActive,
                appsJson);
    }

    private static String buildAppsListJson() {
        if (appContext == null) return "[]";
        try {
            PackageManager pm = appContext.getPackageManager();
            Set<String> blocked = TabletControlManager.getBlockedApps();
            Map<String, String> found = new LinkedHashMap<>();

            for (String pkg : PRIORITY_APPS) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if (ai != null && ai.enabled) {
                        found.put(pkg, TabletMonitorService.resolveAppLabel(appContext, pkg));
                    }
                } catch (Throwable ignored) {}
            }

            List<ResolveInfo> list = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            List<ResolveInfo> tvList = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
            if (tvList != null) {
                if (list == null) list = tvList;
                else list.addAll(tvList);
            }
            if (list != null) {
                Collections.sort(list, (a, b) -> {
                    boolean aUser = a.activityInfo != null && a.activityInfo.applicationInfo != null
                            && (a.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                    boolean bUser = b.activityInfo != null && b.activityInfo.applicationInfo != null
                            && (b.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                    if (aUser != bUser) return aUser ? -1 : 1;
                    return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
                });
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(appContext.getPackageName()) || found.containsKey(pkg)) continue;
                    if (pkg.startsWith("com.android.settings") || pkg.startsWith("com.android.systemui")) continue;
                    found.put(pkg, TabletMonitorService.resolveAppLabel(appContext, pkg));
                    if (found.size() >= 24) break;
                }
            }

            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Map.Entry<String, String> e : found.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                boolean isBlocked = blocked.contains(e.getKey());
                sb.append("{\"pkg\":\"").append(escapeJson(e.getKey()))
                        .append("\",\"label\":\"").append(escapeJson(e.getValue()))
                        .append("\",\"blocked\":").append(isBlocked).append('}');
            }
            sb.append(']');
            return sb.toString();
        } catch (Throwable e) {
            return "[]";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "");
    }

    public static String getLocalIpAddress(Context ctx) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String candidate = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName().toLowerCase(Locale.ROOT);
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("eth")) return ip;
                        if (candidate == null) candidate = ip;
                    }
                }
            }
            if (candidate != null) return candidate;
        } catch (Throwable ignored) {}
        if (ctx != null) {
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    WifiInfo info = wm.getConnectionInfo();
                    int ipInt = info != null ? info.getIpAddress() : 0;
                    if (ipInt != 0) {
                        return String.format(Locale.US, "%d.%d.%d.%d",
                                (ipInt & 0xff), (ipInt >> 8 & 0xff), (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
                    }
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }
}
