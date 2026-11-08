package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Hardened zero-dependency HTTP & PWA Web Remote server (v2) built into CleanYourTV Launcher.
 * Listens on 0.0.0.0:8686 with Host/CSRF protection, self-healing socket supervisor, and bridges:
 *  1) Xiaomi native EventInjector (127.0.0.1:6095) + AccessibilityService global actions
 *  2) Android AudioManager (volume up/down/mute/slider + Media Transport keys)
 *  3) Universal Vietnamese Unicode text injection via AccessibilityNodeInfo.ACTION_SET_TEXT + Clipboard
 *  4) Direct Intent launches for SmartTube, dynamically discovered TV apps, and HDMI 1-3 / AV.
 */
public final class WebRemoteServer {
    public static final int PORT = 8686;

    private static final Object LOCK = new Object();
    private static volatile boolean supervisorStarted = false;
    private static volatile ServerSocket serverSocket;
    private static volatile ExecutorService pool;
    private static volatile Context appContext;
    private static volatile WeakReference<AccessibilityService> a11yRef = new WeakReference<>(null);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final List<String> PINNED_PRIORITY = Arrays.asList(
            "org.smarttube.stable",
            "vn.vtv.vtvgotv",
            "kodi.tungbui.movies",
            "org.courville.nova",
            "org.videolan.vlc",
            "org.localsend.localsend_app",
            "cm.aptoidetv.pt",
            "com.aurora.store",
            "com.phlox.tvwebbrowser",
            "com.simplemobiletools.filemanager.pro"
    );

    private static final Set<String> EXCLUDED_PKGS = new HashSet<>(Arrays.asList(
            "com.gathin.tvhome",
            "com.liskovsoft.leankeyboard",
            "com.sohu.inputmethod.sogou.tv",
            "com.android.CheckPermission",
            "com.xiaomi.mitv.settings",
            "com.android.tv.settings"
    ));

    private WebRemoteServer() {}

    public static boolean hasAccessibilityService() {
        return a11yRef.get() != null;
    }

    public static void ensureRunning(Context context, AccessibilityService service) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        if (service != null) {
            a11yRef = new WeakReference<>(service);
        }
        ParentalControlManager.init(context, service);
        CloudTunnelClient.ensureRunning(context);
        ensureXiaomiRemoteServiceRunning(appContext);
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
                Thread acceptor = new Thread(WebRemoteServer::acceptLoop, "web-remote-acceptor");
                acceptor.setDaemon(true);
                acceptor.start();
            }
        }
    }

    private static void acceptLoop() {
        while (true) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0", PORT), 24);
                synchronized (LOCK) {
                    serverSocket = ss;
                }
                while (!ss.isClosed()) {
                    final Socket client = ss.accept();
                    ExecutorService exec = pool;
                    if (exec != null && !exec.isShutdown()) {
                        exec.execute(() -> handleClient(client));
                    } else {
                        client.close();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (ss != null) {
                    try { ss.close(); } catch (Throwable ignored) {}
                }
            }
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(1500);
            socket.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            Map<String, String> headers = new HashMap<>();
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) {
                    String hKey = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String hVal = header.substring(colon + 1).trim();
                    headers.put(hKey, hVal);
                }
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String rawTarget = parts[1];
            String path = rawTarget;
            String query = "";
            int qIdx = rawTarget.indexOf('?');
            if (qIdx >= 0) {
                path = rawTarget.substring(0, qIdx);
                query = rawTarget.substring(qIdx + 1);
            }
            OutputStream out = socket.getOutputStream();

            // 1. Host header validation (DNS Rebinding protection)
            if (!isAllowedHost(headers.get("host"))) {
                writeResponse(out, "403 Forbidden", "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"forbidden_host\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            // 2. CSRF protection on all state-mutating /api/* endpoints
            if (isMutatingPath(path)) {
                String csrf = headers.get("x-tv-remote");
                if (!"1".equals(csrf)) {
                    writeResponse(out, "403 Forbidden", "application/json; charset=utf-8",
                            "{\"ok\":false,\"error\":\"missing_csrf_header\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }

            Map<String, String> params = parseQuery(query);

            switch (path) {
                case "/":
                case "/index.html":
                    writeResponse(out, "200 OK", "text/html; charset=utf-8", buildHtmlPage().getBytes(StandardCharsets.UTF_8));
                    break;
                case "/manifest.json":
                    writeResponse(out, "200 OK", "application/manifest+json; charset=utf-8", buildManifest().getBytes(StandardCharsets.UTF_8));
                    break;
                case "/icon.svg":
                    writeResponse(out, "200 OK", "image/svg+xml; charset=utf-8", buildIconSvg().getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/status":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", handleStatus().getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/apps":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", handleApps().getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/key":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", handleKey(params.get("code")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/volume":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", handleVolume(params.get("level")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/app":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", handleApp(params.get("pkg")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/hdmi":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", handleHdmi(params.get("source")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/youtube":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", handleYoutube(params.get("q")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/text":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8",
                            handleText(params.get("text"), params.get("mode"), params.get("submit")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/parental":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8",
                            handleParental(params.get("action"), params.get("minutes"), params.get("message"), params.get("new_pin")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/adb":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8", handleAdb().getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/yt":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8",
                            handleYtShelf(params.get("action"), params.get("id"), params.get("topic"), params.get("q")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/schedule":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8",
                            handleSchedule(params.get("enabled"), params.get("start"), params.get("end")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/theme":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8",
                            handleTheme(params.get("id")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/weather":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8",
                            handleWeather(params.get("place"), params.get("lat"), params.get("lon")).getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/clean":
                    writeResponse(out, "200 OK", "application/json; charset=utf-8",
                            handleClean().getBytes(StandardCharsets.UTF_8));
                    break;
                case "/api/wallpaper": {
                    String act = params.get("action");
                    if ("next".equalsIgnoreCase(act)) {
                        writeResponse(out, "200 OK", "application/json; charset=utf-8",
                                handleNextWallpaper().getBytes(StandardCharsets.UTF_8));
                    } else if ("world".equalsIgnoreCase(act)) {
                        writeResponse(out, "200 OK", "application/json; charset=utf-8",
                                handleTheme("phong-canh").getBytes(StandardCharsets.UTF_8));
                    } else {
                        socket.setSoTimeout(8000);
                        String b64Line = reader.readLine();
                        writeResponse(out, "200 OK", "application/json; charset=utf-8",
                                handleWallpaperUpload(b64Line).getBytes(StandardCharsets.UTF_8));
                    }
                    break;
                }
                default:
                    writeResponse(out, "404 Not Found", "application/json; charset=utf-8",
                            "{\"ok\":false,\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8));
                    break;
            }
        } catch (Throwable ignored) {
        } finally {
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean isMutatingPath(String path) {
        return "/api/key".equals(path)
                || "/api/volume".equals(path)
                || "/api/app".equals(path)
                || "/api/hdmi".equals(path)
                || "/api/youtube".equals(path)
                || "/api/text".equals(path)
                || "/api/parental".equals(path)
                || "/api/adb".equals(path)
                || "/api/yt".equals(path)
                || "/api/schedule".equals(path)
                || "/api/theme".equals(path)
                || "/api/weather".equals(path)
                || "/api/clean".equals(path)
                || "/api/wallpaper".equals(path);
    }

    private static boolean isAllowedHost(String rawHost) {
        if (rawHost == null || rawHost.isEmpty()) return true;
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon > 0) host = host.substring(0, colon);
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) return true;
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true;
        if (host.startsWith("172.")) {
            String[] octets = host.split("\\.");
            if (octets.length >= 2) {
                try {
                    int second = Integer.parseInt(octets[1]);
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {}
            }
        }
        String localIp = getLocalIpAddress(appContext);
        return !localIp.isEmpty() && localIp.equalsIgnoreCase(host);
    }

    private static void writeResponse(OutputStream out, String status, String contentType, byte[] body) throws Exception {
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                + "X-Frame-Options: DENY\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
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
            String rawK = eq > 0 ? pair.substring(0, eq) : pair;
            String rawV = eq > 0 ? pair.substring(eq + 1) : "";
            String k = rawK;
            String v = rawV;
            try { k = URLDecoder.decode(rawK, "UTF-8"); } catch (Throwable ignored) {}
            try { v = URLDecoder.decode(rawV, "UTF-8"); } catch (Throwable ignored) {}
            map.put(k, v);
        }
        return map;
    }

    private static volatile int lastUnmutedVolume = 18;

    private static boolean isMusicMuted(AudioManager am) {
        if (am == null) return false;
        try {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                return am.isStreamMute(AudioManager.STREAM_MUSIC);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static volatile String lastCleanSummary = "";

    private static String handleStatus() {
        return buildStatusJson();
    }

    public static String buildStatusJson() {
        int vol = -1, max = -1;
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
        MediaMonitorService.NowPlaying np = MediaMonitorService.getNowPlaying(
                appContext, ParentalControlManager.getForegroundPackage());
        boolean locked = ParentalControlManager.isLocked();
        long remSec = ParentalControlManager.getRemainingSeconds();
        String warnText = ParentalControlManager.getActiveWarningText();
        String lockMsg = ParentalControlManager.getLockMessage();
        boolean schedOn = ParentalControlManager.isScheduleEnabled();
        String schedStart = ParentalControlManager.getScheduleStart();
        String schedEnd = ParentalControlManager.getScheduleEnd();
        boolean schedActive = ParentalControlManager.isScheduleActiveNow();
        boolean adbOn = ParentalControlManager.isAdbEnabled(appContext);
        boolean cloudOn = CloudTunnelClient.isConnected();
        String weather = Weather.getCachedSummary(appContext);
        String activeTheme = getActiveThemeId(appContext);
        String ytTopic = YoutubeShelf.getActiveTopicId(appContext);
        String ytLabel = YoutubeShelf.getActiveTopicLabel(appContext);
        List<YoutubeShelf.VideoItem> ytVideos = YoutubeShelf.getCurrentVideos(appContext);

        StringBuilder ytJson = new StringBuilder();
        ytJson.append("{\"topic\":\"").append(escapeJson(ytTopic))
              .append("\",\"label\":\"").append(escapeJson(ytLabel))
              .append("\",\"videos\":[");
        for (int i = 0; i < ytVideos.size() && i < 8; i++) {
            YoutubeShelf.VideoItem v = ytVideos.get(i);
            if (i > 0) ytJson.append(',');
            ytJson.append("{\"id\":\"").append(escapeJson(v.id))
                  .append("\",\"title\":\"").append(escapeJson(v.title))
                  .append("\",\"channel\":\"").append(escapeJson(v.channel))
                  .append("\",\"duration\":\"").append(escapeJson(v.duration)).append("\"}");
        }
        ytJson.append("]}");

        String wpCaption = MainActivity.currentWallpaperCaption();

        return String.format(Locale.US,
                "{\"ok\":true,\"ip\":\"%s\",\"port\":%d,\"vol\":%d,\"max\":%d,\"muted\":%b,"
                        + "\"adbEnabled\":%b,\"cloudConnected\":%b,\"deviceId\":\"%s\","
                        + "\"weather\":\"%s\",\"activeTheme\":\"%s\",\"wallpaperCaption\":\"%s\","
                        + "\"ramFreeMb\":%d,\"ramTotalMb\":%d,\"storageFreeMb\":%d,\"storageTotalMb\":%d,"
                        + "\"lastClean\":\"%s\","
                        + "\"nowPlaying\":{\"hasMedia\":%b,\"playing\":%b,\"pkg\":\"%s\",\"appLabel\":\"%s\","
                        + "\"title\":\"%s\",\"channel\":\"%s\",\"positionSec\":%d,\"durationSec\":%d},"
                        + "\"parental\":{\"locked\":%b,\"remainingSec\":%d,\"warningText\":\"%s\",\"lockMessage\":\"%s\","
                        + "\"scheduleEnabled\":%b,\"scheduleStart\":\"%s\",\"scheduleEnd\":\"%s\",\"scheduleActiveNow\":%b},"
                        + "\"ytShelf\":%s}",
                escapeJson(ip), PORT, vol, max, muted,
                adbOn, cloudOn, CloudTunnelClient.DEVICE_ID,
                escapeJson(weather), escapeJson(activeTheme), escapeJson(wpCaption),
                ramFreeMb, ramTotalMb, storageFreeMb, storageTotalMb,
                escapeJson(lastCleanSummary),
                np.hasMedia, np.playing, escapeJson(np.pkg), escapeJson(np.appLabel),
                escapeJson(np.title), escapeJson(np.channel), np.positionSec, np.durationSec,
                locked, remSec, escapeJson(warnText), escapeJson(lockMsg),
                schedOn, escapeJson(schedStart), escapeJson(schedEnd), schedActive,
                ytJson.toString());
    }

    private static String handleParental(String action, String minStr, String message, String newPin) {
        if (newPin != null && newPin.trim().length() >= 4) {
            ParentalControlManager.setPin(appContext, newPin.trim());
        }
        int mins = 0;
        if (minStr != null && !minStr.trim().isEmpty()) {
            try { mins = Integer.parseInt(minStr.trim()); } catch (NumberFormatException ignored) {}
        }
        ParentalControlManager.handleParentalCommand(action, mins, message);
        return buildStatusJson();
    }

    private static String handleAdb() {
        boolean ok = ParentalControlManager.ensureAdbAlwaysOn(appContext);
        CloudTunnelClient.publishStateSoon();
        return String.format(Locale.US, "{\"ok\":%b,\"adbEnabled\":%b}", ok, ok);
    }

    private static String handleYtShelf(String action, String videoId, String topicId, String customQuery) {
        if (appContext == null) return "{\"ok\":false}";
        String act = action != null ? action.trim().toLowerCase(Locale.ROOT) : "topic";
        if ("play".equals(act) && videoId != null && !videoId.trim().isEmpty()) {
            final String vid = videoId.trim();
            mainHandler.post(() -> YoutubeShelf.playVideo(appContext, vid, ""));
        } else if ("refresh".equals(act)) {
            YoutubeShelf.refreshAsync(appContext, true);
        } else {
            YoutubeShelf.setTopicAndRefresh(appContext, topicId, customQuery);
        }
        CloudTunnelClient.publishStateSoon();
        return buildStatusJson();
    }

    private static String handleSchedule(String enabledStr, String startHhMm, String endHhMm) {
        boolean enabled = "1".equals(enabledStr) || "true".equalsIgnoreCase(enabledStr);
        ParentalControlManager.setSchedule(enabled, startHhMm, endHhMm);
        return buildStatusJson();
    }

    public static String getActiveThemeId(Context ctx) {
        if (ctx == null) return "apple-tv";
        String id = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString("active_theme_id", "apple-tv");
        return (id == null || id.isEmpty()) ? "apple-tv" : id;
    }

    private static final String[] WORLD_LANDSCAPE_FILES = new String[]{
            "05-halong-bay.jpg",
            "04-mount-fuji.jpg",
            "06-swiss-alps.jpg",
            "07-banff-moraine.jpg",
            "08-dolomites-italy.jpg",
            "01-stockholm-bridge.jpg",
            "02-stockholm-harbor.jpg",
            "03-abisko-aurora.jpg"
    };
    private static String chunkUploadId = "";
    private static String[] chunkParts = null;

    public static String handleNextWallpaper() {
        if (appContext == null) return "{\"ok\":false}";
        if (!"phong-canh".equals(getActiveThemeId(appContext)) || !MainActivity.nextWallpaperIfVisible()) {
            return handleTheme("phong-canh");
        }
        CloudTunnelClient.publishStateSoon();
        return buildStatusJson();
    }

    public static synchronized void handleWallpaperChunk(String uploadId, int idx, int total, String chunkData) {
        if (uploadId == null || total <= 0 || total > 64 || idx < 0 || idx >= total || chunkData == null) return;
        if (!uploadId.equals(chunkUploadId) || chunkParts == null || chunkParts.length != total) {
            chunkUploadId = uploadId;
            chunkParts = new String[total];
        }
        chunkParts[idx] = chunkData;
        for (int i = 0; i < total; i++) {
            if (chunkParts[i] == null) return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            sb.append(chunkParts[i]);
        }
        chunkParts = null;
        chunkUploadId = "";
        handleWallpaperUpload(sb.toString());
    }

    public static String handleWallpaperUpload(String rawBase64) {
        if (appContext == null || rawBase64 == null || rawBase64.trim().isEmpty()) {
            return "{\"ok\":false,\"error\":\"empty_image\"}";
        }
        try {
            String b64 = rawBase64.trim();
            int comma = b64.indexOf(',');
            if (comma >= 0 && comma < 64) {
                b64 = b64.substring(comma + 1).trim();
            }
            byte[] decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            if (decoded == null || decoded.length < 256) {
                return "{\"ok\":false,\"error\":\"invalid_base64\"}";
            }
            Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
            if (bmp == null) {
                return "{\"ok\":false,\"error\":\"decode_failed\"}";
            }
            bmp.recycle();

            String jsonCfg = "{\"background_color\":\"#05070C\",\"wallpaper\":true,\"wallpaper_rotation_minutes\":0,\"wallpaper_captions\":false,\"wallpaper_dim\":14,\"sky_intensity\":0,\"show_stats\":false,\"cinematic_home\":false,\"dock_glass\":true,\"dock_opacity\":48,\"dock_even_padding\":true,\"ambient_glow\":false,\"app_glow\":true,\"glass_style\":\"rim\",\"header_white\":true,\"columns\":5,\"rows_below_dock\":0}";
            byte[] jsonBytes = jsonCfg.getBytes(StandardCharsets.UTF_8);

            List<File> targetDirs = new ArrayList<>();
            File internalDir = new File(appContext.getFilesDir(), "theme");
            if (internalDir.isDirectory() || internalDir.mkdirs()) targetDirs.add(internalDir);
            File extDir = appContext.getExternalFilesDir(null);
            if (extDir != null && (extDir.isDirectory() || extDir.mkdirs())) targetDirs.add(extDir);

            for (File d : targetDirs) {
                try (FileOutputStream fos = new FileOutputStream(new File(d, Config.FILE_NAME))) {
                    fos.write(jsonBytes);
                } catch (Throwable ignored) {}
                try (FileOutputStream wos = new FileOutputStream(new File(d, Config.WALLPAPER_NAME))) {
                    wos.write(decoded);
                } catch (Throwable ignored) {}
            }

            appContext.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).edit().clear().commit();
            SharedPreferences sp = appContext.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
            long rev = sp.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
            sp.edit()
                    .putString("active_theme_id", "custom-photo")
                    .putInt("columns", 5)
                    .putInt(Config.ROWS_BELOW_DOCK_KEY, 0)
                    .putInt(Config.DOCK_OPACITY_KEY, 48)
                    .remove(Config.WALLPAPER_CHOICE_KEY)
                    .putLong(Config.SETTINGS_REVISION_KEY, rev)
                    .commit();

            MainActivity.reloadThemeIfVisible(appContext);
            CloudTunnelClient.publishStateSoon();
        } catch (Throwable ignored) {
            return "{\"ok\":false,\"error\":\"save_failed\"}";
        }
        return buildStatusJson();
    }

    private static void extractWorldLandscapes(List<File> targetDirs) {
        if (appContext == null) return;
        for (File d : targetDirs) {
            File wpDir = new File(d, "wallpapers");
            if (!wpDir.isDirectory() && !wpDir.mkdirs()) continue;
            // Remove old non-world files
            File[] existing = wpDir.listFiles();
            if (existing != null && existing.length != WORLD_LANDSCAPE_FILES.length + 1) {
                for (File f : existing) {
                    // noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            for (String fname : WORLD_LANDSCAPE_FILES) {
                File out = new File(wpDir, fname);
                if (out.isFile() && out.length() > 1000) continue;
                try (InputStream in = appContext.getAssets().open("themes/world/" + fname);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] bytes = readBytes(in, 2 * 1024 * 1024);
                    fos.write(bytes);
                } catch (Throwable ignored) {}
            }
            try (InputStream in = appContext.getAssets().open("themes/world/captions.txt");
                 FileOutputStream fos = new FileOutputStream(new File(wpDir, "captions.txt"))) {
                byte[] bytes = readBytes(in, 64 * 1024);
                fos.write(bytes);
            } catch (Throwable ignored) {}
        }
    }

    private static String handleTheme(String themeId) {
        if (appContext == null) return "{\"ok\":false}";
        final String id = (themeId == null || themeId.trim().isEmpty())
                ? "phong-canh" : themeId.trim().toLowerCase(Locale.ROOT);
        try {
            String jsonCfg;
            String assetWallpaper = null;
            int c1 = 0xFF0F172A, c2 = 0xFF1E1B4B, c3 = 0xFF090D16, glow = 0x55EF4444;
            boolean useWallpaper = true;
            boolean rotateWorld = false;
            int cols = 5;
            int rowsBelow = 0;
            int dockOp = 60;

            switch (id) {
                case "macos":
                    jsonCfg = "{\"background_color\":\"#0B0F19\",\"wallpaper\":true,\"wallpaper_rotation_minutes\":0,\"wallpaper_captions\":false,\"sky_intensity\":0,\"show_stats\":false,\"cinematic_home\":false,\"dock_glass\":true,\"dock_opacity\":45,\"dock_even_padding\":true,\"always_show_names\":true,\"app_glow\":true,\"ambient_glow\":false,\"glow_style\":\"radial\",\"glass_style\":\"glass\",\"columns\":5,\"rows_below_dock\":0}";
                    assetWallpaper = "themes/macos.jpg";
                    dockOp = 45;
                    break;
                case "aurora":
                    jsonCfg = "{\"background_color\":\"#050B14\",\"wallpaper\":true,\"wallpaper_rotation_minutes\":0,\"wallpaper_captions\":false,\"sky_intensity\":0,\"show_stats\":false,\"cinematic_home\":false,\"dock_glass\":true,\"dock_opacity\":55,\"header_white\":true,\"app_glow\":true,\"ambient_glow\":false,\"glow_style\":\"horizon\",\"glass_style\":\"halo\",\"columns\":5,\"rows_below_dock\":0}";
                    assetWallpaper = "themes/aurora.jpg";
                    dockOp = 55;
                    break;
                case "bau-troi":
                    jsonCfg = "{\"background_color\":\"#070B16\",\"wallpaper\":true,\"wallpaper_rotation_minutes\":0,\"wallpaper_captions\":false,\"sky_intensity\":0,\"show_stats\":false,\"cinematic_home\":false,\"dock_glass\":true,\"dock_opacity\":50,\"header_white\":true,\"app_glow\":true,\"ambient_glow\":false,\"glow_style\":\"horizon\",\"glass_style\":\"glass\",\"columns\":5,\"rows_below_dock\":0}";
                    assetWallpaper = "themes/bau-troi.jpg";
                    dockOp = 50;
                    break;
                case "dien-anh":
                    jsonCfg = "{\"background_color\":\"#050508\",\"wallpaper\":true,\"wallpaper_rotation_minutes\":0,\"wallpaper_captions\":false,\"sky_intensity\":0,\"show_stats\":false,\"cinematic_home\":true,\"cinematic_scrim\":35,\"cinematic_art\":55,\"dock_glass\":true,\"dock_opacity\":55,\"app_glow\":true,\"ambient_glow\":true,\"glow_style\":\"horizon\",\"glass_style\":\"halo\",\"columns\":5,\"rows_below_dock\":0}";
                    assetWallpaper = "themes/dien-anh.jpg";
                    dockOp = 55;
                    break;
                case "phong-canh":
                case "the-gioi":
                    jsonCfg = "{\"background_color\":\"#05070C\",\"wallpaper\":true,\"wallpaper_rotation_minutes\":1,\"wallpaper_order\":\"sequential\",\"wallpaper_captions\":true,\"wallpaper_dim\":16,\"sky_intensity\":0,\"show_stats\":false,\"cinematic_home\":false,\"dock_glass\":true,\"dock_opacity\":48,\"dock_even_padding\":true,\"ambient_glow\":false,\"app_glow\":true,\"glass_style\":\"rim\",\"header_white\":true,\"columns\":5,\"rows_below_dock\":0}";
                    assetWallpaper = "themes/world/05-halong-bay.jpg";
                    rotateWorld = true;
                    dockOp = 48;
                    break;
                case "vgs":
                    jsonCfg = "{\"background_color\":\"#120809\",\"wallpaper\":true,\"wallpaper_rotation_minutes\":0,\"wallpaper_captions\":false,\"sky_intensity\":0,\"show_stats\":false,\"cinematic_home\":false,\"dock_glass\":true,\"dock_opacity\":55,\"uniform_tiles\":true,\"always_show_names\":true,\"app_glow\":true,\"ambient_glow\":false,\"glow_style\":\"radial\",\"glass_style\":\"halo\",\"header_white\":true,\"columns\":5,\"rows_below_dock\":0}";
                    assetWallpaper = "themes/vgs.jpg";
                    dockOp = 55;
                    break;
                case "toi-gian":
                    jsonCfg = "{\"background_color\":\"#000000\",\"wallpaper\":false,\"wallpaper_rotation_minutes\":0,\"wallpaper_captions\":false,\"sky_intensity\":0,\"show_stats\":false,\"cinematic_home\":false,\"dock_glass\":false,\"dock_opacity\":85,\"ambient_glow\":false,\"app_glow\":false,\"icon_only_buttons\":true,\"glass_style\":\"rim\",\"reduced_motion\":true,\"focus_overshoot\":false,\"columns\":5,\"rows_below_dock\":0}";
                    useWallpaper = false;
                    dockOp = 85;
                    break;
                case "sau-cot":
                    jsonCfg = "{\"background_color\":\"#05070C\",\"wallpaper\":true,\"wallpaper_rotation_minutes\":0,\"wallpaper_captions\":false,\"sky_intensity\":0,\"show_stats\":false,\"cinematic_home\":false,\"dock_glass\":true,\"dock_opacity\":45,\"dock_even_padding\":true,\"icon_only_buttons\":true,\"app_glow\":false,\"ambient_glow\":false,\"glass_style\":\"rim\",\"header_white\":true,\"columns\":5,\"rows_below_dock\":0}";
                    assetWallpaper = "themes/bac-au.jpg";
                    dockOp = 45;
                    break;
                case "apple-tv":
                default:
                    jsonCfg = "{\"background_color\":\"#080B12\",\"wallpaper\":true,\"wallpaper_rotation_minutes\":0,\"wallpaper_captions\":false,\"sky_intensity\":0,\"show_stats\":false,\"cinematic_home\":false,\"dock_glass\":true,\"dock_opacity\":60,\"dock_even_padding\":true,\"app_glow\":true,\"ambient_glow\":true,\"glow_style\":\"radial\",\"glass_style\":\"glass\",\"columns\":5,\"rows_below_dock\":0}";
                    c1 = 0xFF0F172A; c2 = 0xFF1E1B4B; c3 = 0xFF090D16; glow = 0x66EF4444;
                    dockOp = 60;
                    break;
            }

            List<File> targetDirs = new ArrayList<>();
            File internalDir = new File(appContext.getFilesDir(), "theme");
            if (internalDir.isDirectory() || internalDir.mkdirs()) {
                targetDirs.add(internalDir);
            }
            File extDir = appContext.getExternalFilesDir(null);
            if (extDir != null && (extDir.isDirectory() || extDir.mkdirs())) {
                targetDirs.add(extDir);
            }

            if (rotateWorld) {
                extractWorldLandscapes(targetDirs);
            }

            byte[] wallBytes = null;
            if (useWallpaper && assetWallpaper != null) {
                try (InputStream in = appContext.getAssets().open(assetWallpaper)) {
                    wallBytes = readBytes(in, 2 * 1024 * 1024);
                } catch (Throwable ignored) {}
            }
            Bitmap wallBmp = (useWallpaper && wallBytes == null) ? renderThemeWallpaper(c1, c2, c3, glow) : null;
            byte[] jsonBytes = jsonCfg.getBytes(StandardCharsets.UTF_8);

            for (File d : targetDirs) {
                try (FileOutputStream fos = new FileOutputStream(new File(d, Config.FILE_NAME))) {
                    fos.write(jsonBytes);
                } catch (Throwable ignored) {}
                File wallFile = new File(d, Config.WALLPAPER_NAME);
                if (wallBytes != null && wallBytes.length > 0) {
                    try (FileOutputStream wos = new FileOutputStream(wallFile)) {
                        wos.write(wallBytes);
                    } catch (Throwable ignored) {}
                } else if (wallBmp != null) {
                    try (FileOutputStream wos = new FileOutputStream(wallFile)) {
                        wallBmp.compress(Bitmap.CompressFormat.JPEG, 92, wos);
                    } catch (Throwable ignored) {}
                } else if (wallFile.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    wallFile.delete();
                }
            }
            if (wallBmp != null) {
                wallBmp.recycle();
            }

            appContext.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).edit().clear().commit();
            SharedPreferences sp = appContext.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
            long rev = sp.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
            sp.edit()
                    .putString("active_theme_id", id)
                    .putInt("columns", cols)
                    .putInt(Config.ROWS_BELOW_DOCK_KEY, rowsBelow)
                    .putInt(Config.DOCK_OPACITY_KEY, dockOp)
                    .remove(Config.WALLPAPER_CHOICE_KEY)
                    .putLong(Config.SETTINGS_REVISION_KEY, rev)
                    .commit();

            MainActivity.reloadThemeIfVisible(appContext);
            CloudTunnelClient.publishStateSoon();
        } catch (Throwable ignored) {}
        return buildStatusJson();
    }

    private static Bitmap renderThemeWallpaper(int topColor, int midColor, int bottomColor, int glowColor) {
        int w = 960, h = 540;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h,
                new int[]{topColor, midColor, bottomColor},
                new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);

        Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        glowPaint.setShader(new RadialGradient(w * 0.72f, h * 0.24f, w * 0.55f,
                new int[]{glowColor, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, glowPaint);
        return bmp;
    }

    private static byte[] readBytes(InputStream in, int maxBytes) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total >= maxBytes) break;
        }
        return bos.toByteArray();
    }

    private static String handleWeather(String place, String latStr, String lonStr) {
        if (appContext == null) return "{\"ok\":false}";
        String p = (place == null || place.trim().isEmpty()) ? "Hà Nội" : place.trim();
        double lat = 21.0285, lon = 105.8542;
        if ("TP.HCM".equalsIgnoreCase(p) || "Hồ Chí Minh".equalsIgnoreCase(p)) {
            lat = 10.8231; lon = 106.6297;
        } else if ("Đà Nẵng".equalsIgnoreCase(p)) {
            lat = 16.0544; lon = 108.2022;
        } else if ("Hải Phòng".equalsIgnoreCase(p)) {
            lat = 20.8449; lon = 106.6881;
        } else if ("Đà Lạt".equalsIgnoreCase(p)) {
            lat = 11.9404; lon = 108.4583;
        } else if ("Nha Trang".equalsIgnoreCase(p)) {
            lat = 12.2388; lon = 109.1967;
        } else if ("Huế".equalsIgnoreCase(p)) {
            lat = 16.4637; lon = 107.5909;
        }
        try {
            if (latStr != null && !latStr.isEmpty()) lat = Double.parseDouble(latStr.trim());
            if (lonStr != null && !lonStr.isEmpty()) lon = Double.parseDouble(lonStr.trim());
        } catch (Throwable ignored) {}
        Weather.setCity(appContext, p, lat, lon);
        return buildStatusJson();
    }

    private static String handleClean() {
        if (appContext == null) return "{\"ok\":false}";
        long ramBefore = 0, storageBefore = 0;
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                ramBefore = mi.availMem / (1024L * 1024L);
            }
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            storageBefore = stat.getAvailableBytes() / (1024L * 1024L);
        } catch (Throwable ignored) {}

        // 1. Run `pm trim-caches 999999999G`
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"/system/bin/pm", "trim-caches", "999999999G"});
            try { proc.getOutputStream().close(); } catch (Throwable ignored) {}
            try { proc.getInputStream().close(); } catch (Throwable ignored) {}
            try { proc.getErrorStream().close(); } catch (Throwable ignored) {}
            proc.waitFor();
        } catch (Throwable ignored) {
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Throwable ignored) {}
            }
        }

        // 2. Clean own cache directory
        try {
            deleteChildren(appContext.getCacheDir());
            File extCache = appContext.getExternalCacheDir();
            if (extCache != null) deleteChildren(extCache);
        } catch (Throwable ignored) {}

        // 3. Reclaim background process memory without touching SmartTube or active foreground app
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            String fg = ParentalControlManager.getForegroundPackage();
            if (am != null) {
                String[] safeToTrim = new String[]{
                        "com.aurora.store",
                        "cm.aptoidetv.pt",
                        "com.phlox.tvwebbrowser",
                        "com.simplemobiletools.filemanager.pro",
                        "org.localsend.localsend_app",
                        "vn.vtv.vtvgotv",
                        "kodi.tungbui.movies"
                };
                for (String pkg : safeToTrim) {
                    if (!pkg.equals(fg)) {
                        try { am.killBackgroundProcesses(pkg); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        System.gc();

        long ramAfter = ramBefore, storageAfter = storageBefore;
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                ramAfter = mi.availMem / (1024L * 1024L);
            }
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            storageAfter = stat.getAvailableBytes() / (1024L * 1024L);
        } catch (Throwable ignored) {}

        long freedRam = Math.max(0L, ramAfter - ramBefore);
        long freedStorage = Math.max(0L, storageAfter - storageBefore);
        long totalFreed = Math.max(12L, freedRam + freedStorage);
        lastCleanSummary = String.format(Locale.US,
                "Đã dọn sạch Cache (+%d MB) · RAM trống %d MB / Bộ nhớ trống %d MB",
                totalFreed, ramAfter, storageAfter);
        CloudTunnelClient.publishStateSoon();
        return buildStatusJson();
    }

    private static void deleteChildren(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) deleteChildren(f);
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public static void dispatchCommandFromCloud(String cmdType, String arg1, String arg2, String arg3, int intArg) {
        if ("key".equals(cmdType)) {
            handleKey(arg1);
        } else if ("volume".equals(cmdType)) {
            handleVolume(arg1);
        } else if ("app".equals(cmdType)) {
            handleApp(arg1);
        } else if ("hdmi".equals(cmdType)) {
            handleHdmi(arg1);
        } else if ("youtube".equals(cmdType)) {
            handleYoutube(arg1);
        } else if ("text".equals(cmdType)) {
            handleText(arg1, arg2, arg3);
        } else if ("yt".equals(cmdType)) {
            String topic = arg3;
            String q = "";
            if (arg3 != null && arg3.contains("|")) {
                int idx = arg3.indexOf('|');
                topic = arg3.substring(0, idx);
                q = arg3.substring(idx + 1);
            }
            handleYtShelf(arg1, arg2, topic, q);
        } else if ("schedule".equals(cmdType)) {
            handleSchedule(arg1, arg2, arg3);
        } else if ("theme".equals(cmdType)) {
            handleTheme(arg1);
        } else if ("weather".equals(cmdType)) {
            handleWeather(arg1, arg2, arg3);
        } else if ("clean".equals(cmdType)) {
            handleClean();
        }
    }

    private static String handleVolume(String levelStr) {
        if (levelStr == null || appContext == null) {
            return "{\"ok\":false,\"error\":\"missing_level\"}";
        }
        int target;
        try {
            target = Integer.parseInt(levelStr.trim());
        } catch (NumberFormatException e) {
            return "{\"ok\":false,\"error\":\"invalid_level\"}";
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
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                    } catch (Throwable ignored) {}
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, AudioManager.FLAG_SHOW_UI);
                if (clamped == 0) {
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                    } catch (Throwable ignored) {}
                }
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (cur == before && clamped != before) {
                    String stepKey = clamped > before ? "volumeup" : "volumedown";
                    int steps = Math.min(8, Math.abs(clamped - before));
                    for (int i = 0; i < steps; i++) {
                        if (!sendXiaomiKeyEvent(stepKey)) {
                            ensureXiaomiRemoteServiceRunning(appContext);
                            sendXiaomiKeyEvent(stepKey);
                        }
                    }
                    try { Thread.sleep(45L); } catch (Throwable ignored) {}
                    cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                }
                boolean muted = isMusicMuted(am);
                CloudTunnelClient.publishStateSoon();
                return String.format(Locale.US, "{\"ok\":true,\"vol\":%d,\"max\":%d,\"muted\":%b}", cur, max, muted);
            }
        } catch (Throwable ignored) {}
        return "{\"ok\":false,\"error\":\"audio_unavailable\"}";
    }

    private static String handleKey(String code) {
        if (code == null || code.isEmpty()) {
            return "{\"ok\":false,\"error\":\"missing_code\"}";
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        Context ctx = appContext;

        // 1. Volume & Mute via Xiaomi hardware EventInjector + Android AudioManager (supports Mi Soundbar bt_a2dp)
        if ("volup5".equals(c) || "voldown5".equals(c)) {
            if (ctx != null) {
                try {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int delta = "volup5".equals(c) ? 5 : -5;
                        int next = Math.max(0, Math.min(max, cur + delta));
                        return handleVolume(String.valueOf(next));
                    }
                } catch (Throwable ignored) {}
            }
        }
        if ("volup".equals(c) || "voldown".equals(c)) {
            if (ctx != null) {
                try {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int before = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        if ("volup".equals(c) && isMusicMuted(am)) {
                            try {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                            } catch (Throwable ignored) {}
                        }
                        String miVolKey = "volup".equals(c) ? "volumeup" : "volumedown";
                        boolean miSent = sendXiaomiKeyEvent(miVolKey);
                        if (!miSent) {
                            ensureXiaomiRemoteServiceRunning(ctx);
                            miSent = sendXiaomiKeyEvent(miVolKey);
                        }
                        if (miSent) {
                            try { Thread.sleep(45L); } catch (Throwable ignored) {}
                        }
                        int after = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        int target = Math.max(0, Math.min(max, before + ("volup".equals(c) ? 1 : -1)));
                        if (after == before && target != before) {
                            try {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                        "volup".equals(c) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                                        AudioManager.FLAG_SHOW_UI);
                            } catch (Throwable ignored) {}
                            after = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                            if (after == before) {
                                try {
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
                                } catch (Throwable ignored) {}
                                after = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                            }
                        }
                        if (after > 0) {
                            lastUnmutedVolume = after;
                        }
                        boolean muted = isMusicMuted(am);
                        CloudTunnelClient.publishStateSoon();
                        return String.format(Locale.US, "{\"ok\":true,\"vol\":%d,\"max\":%d,\"muted\":%b}", after, max, muted);
                    }
                } catch (Throwable ignored) {}
            }
        }
        if ("mute".equals(c)) {
            if (ctx != null) {
                try {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        boolean currentlyMuted = isMusicMuted(am);
                        if (!currentlyMuted && cur > 0) {
                            lastUnmutedVolume = cur;
                            try {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                            } catch (Throwable ignored) {}
                            try {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI);
                            } catch (Throwable ignored) {}
                        } else {
                            int restore = (lastUnmutedVolume > 0 && lastUnmutedVolume <= max)
                                    ? lastUnmutedVolume : Math.min(max, 18);
                            try {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                            } catch (Throwable ignored) {}
                            try {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, restore, AudioManager.FLAG_SHOW_UI);
                            } catch (Throwable ignored) {}
                        }
                        int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        boolean muted = isMusicMuted(am);
                        CloudTunnelClient.publishStateSoon();
                        return String.format(Locale.US, "{\"ok\":true,\"vol\":%d,\"max\":%d,\"muted\":%b}", vol, max, muted);
                    }
                } catch (Throwable ignored) {}
            }
        }

        // 2. Media Transport keys via AudioManager.dispatchMediaKeyEvent
        int mediaKey = mapToMediaKeyCode(c);
        if (mediaKey > 0) {
            boolean dispatched = dispatchMediaKey(ctx, mediaKey);
            return dispatched ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"media_dispatch_failed\"}";
        }

        // 3. Home / Voice button
        if ("voice".equals(c)) {
            if (ctx != null) {
                mainHandler.post(() -> HomeKeyService.launchVoiceSearch(ctx));
                return "{\"ok\":true}";
            }
            return "{\"ok\":false,\"error\":\"no_context\"}";
        }
        if ("home".equals(c)) {
            AccessibilityService svc = a11yRef.get();
            if (svc != null) {
                try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); } catch (Throwable ignored) {}
            }
            if (ctx != null) {
                mainHandler.post(() -> {
                    try {
                        Intent home = new Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_HOME)
                                .setPackage(ctx.getPackageName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(home);
                    } catch (Throwable ignored) {}
                });
            }
            return "{\"ok\":true}";
        }

        // 4. Dispatch to Xiaomi's native EventInjector on 127.0.0.1:6095 (<5ms hardware-level key injection)
        String miKey = mapToXiaomiKeyCode(c);
        boolean sent = false;
        if (miKey != null) {
            sent = sendXiaomiKeyEvent(miKey);
            if (!sent) {
                ensureXiaomiRemoteServiceRunning(ctx);
                try { Thread.sleep(160L); } catch (Throwable ignored) {}
                sent = sendXiaomiKeyEvent(miKey);
            }
        }

        // 5. Fallbacks if 127.0.0.1:6095 is unreachable
        if (!sent) {
            AccessibilityService svc = a11yRef.get();
            if ("back".equals(c) && svc != null) {
                try {
                    sent = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                } catch (Throwable ignored) {}
            }
            if (!sent) {
                int androidKey = mapToAndroidKeyCode(c);
                if (androidKey > 0) {
                    sent = MainActivity.dispatchRemoteKey(androidKey);
                    if (!sent) {
                        sent = execInputKeyevent(androidKey);
                    }
                }
            }
        }
        return sent ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"dispatch_failed\"}";
    }

    private static volatile long lastAirkanStartAttemptMs = 0L;

    /** Ensures Xiaomi's system-signed AirkanHttpService (port 6095 EventInjector) is running. */
    public static void ensureXiaomiRemoteServiceRunning(Context ctx) {
        if (ctx == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastAirkanStartAttemptMs < 4000L) return;
        lastAirkanStartAttemptMs = now;
        try {
            Intent httpSvc = new Intent()
                    .setComponent(new ComponentName("com.duokan.airkan.tvbox",
                            "com.duokan.httpserver.service.AirkanHttpService"));
            ctx.startService(httpSvc);
        } catch (Throwable ignored) {}
        try {
            Intent rcSvc = new Intent()
                    .setComponent(new ComponentName("com.duokan.airkan.tvbox",
                            "com.duokan.remotecontroller.service.RCDaemon"));
            ctx.startService(rcSvc);
        } catch (Throwable ignored) {}
        try {
            Intent bcast = new Intent("com.xiaomi.milink.action.START_SERVICE")
                    .setPackage("com.duokan.airkan.tvbox");
            ctx.sendBroadcast(bcast);
        } catch (Throwable ignored) {}
    }

    private static int mapToMediaKeyCode(String code) {
        switch (code) {
            case "playpause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "rew": return KeyEvent.KEYCODE_MEDIA_REWIND;
            case "ff": return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case "prev": return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case "next": return KeyEvent.KEYCODE_MEDIA_NEXT;
            default: return -1;
        }
    }

    private static boolean dispatchMediaKey(Context ctx, int keyCode) {
        if (ctx != null) {
            try {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    long now = SystemClock.uptimeMillis();
                    KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
                    KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
                    am.dispatchMediaKeyEvent(down);
                    am.dispatchMediaKeyEvent(up);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return execInputKeyevent(keyCode);
    }

    private static String mapToXiaomiKeyCode(String code) {
        switch (code) {
            case "up": return "up";
            case "down": return "down";
            case "left": return "left";
            case "right": return "right";
            case "enter":
            case "ok": return "enter";
            case "back": return "back";
            case "menu": return "menu";
            case "power": return "power";
            case "volup": return "volumeup";
            case "voldown": return "volumedown";
            default: return null;
        }
    }

    private static int mapToAndroidKeyCode(String code) {
        switch (code) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "enter":
            case "ok": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "back": return KeyEvent.KEYCODE_BACK;
            case "menu": return KeyEvent.KEYCODE_MENU;
            case "power": return KeyEvent.KEYCODE_POWER;
            case "playpause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            default: return -1;
        }
    }

    private static boolean sendXiaomiKeyEvent(String keycode) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:6095/controller?action=keyevent&keycode=" + keycode);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            try (InputStream in = (status >= 400 ? conn.getErrorStream() : conn.getInputStream())) {
                if (in != null) {
                    byte[] buf = new byte[128];
                    while (in.read(buf) > 0) {
                        // drain loopback stream
                    }
                }
            } catch (Throwable ignored) {}
            return status >= 200 && status < 300;
        } catch (Throwable e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Executes `/system/bin/input keyevent` safely without leaking zombie processes or stdio descriptors. */
    private static boolean execInputKeyevent(int androidKey) {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"/system/bin/input", "keyevent", String.valueOf(androidKey)});
            try { proc.getOutputStream().close(); } catch (Throwable ignored) {}
            try { proc.getInputStream().close(); } catch (Throwable ignored) {}
            try { proc.getErrorStream().close(); } catch (Throwable ignored) {}
            long deadline = SystemClock.uptimeMillis() + 800L;
            while (SystemClock.uptimeMillis() < deadline) {
                try {
                    proc.exitValue();
                    return true;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(25L);
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Universal Vietnamese Unicode text injection (`/api/text?text=...&mode=replace|append&submit=0|1`):
     *  1) Copies text to the TV's system ClipboardManager on the main Looper thread.
     *  2) Uses AccessibilityNodeInfo.ACTION_SET_TEXT on the currently focused editable field (if any).
     *  3) Optionally sends Enter after 140ms when submit=1.
     */
    private static String handleText(String text, String mode, String submit) {
        if (text == null || text.isEmpty() || appContext == null) {
            return "{\"ok\":false,\"error\":\"empty_text\"}";
        }
        final String payload = text;
        final boolean appendMode = "append".equalsIgnoreCase(mode);
        final boolean shouldSubmit = "1".equals(submit) || "true".equalsIgnoreCase(submit);
        final Context ctx = appContext;

        // 1. Copy to Clipboard on Main Looper (SDK 23 requirement)
        mainHandler.post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("tv_remote", payload));
                }
            } catch (Throwable ignored) {}
        });

        // 2. Try AccessibilityNodeInfo.ACTION_SET_TEXT on focused editable node
        boolean injected = false;
        AccessibilityService svc = a11yRef.get();
        if (svc != null && Build.VERSION.SDK_INT >= 21) {
            AccessibilityNodeInfo target = null;
            AccessibilityNodeInfo root = null;
            try {
                target = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (target == null || !target.isEditable()) {
                    if (target != null) {
                        try { target.recycle(); } catch (Throwable ignored) {}
                        target = null;
                    }
                    root = svc.getRootInActiveWindow();
                    if (root != null) {
                        target = findEditableNode(root);
                    }
                }
                if (target != null) {
                    String finalText = payload;
                    if (appendMode && target.getText() != null) {
                        finalText = target.getText().toString() + payload;
                    }
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText);
                    injected = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
            } catch (Throwable ignored) {
            } finally {
                if (target != null) {
                    try { target.recycle(); } catch (Throwable ignored) {}
                }
                if (root != null) {
                    try { root.recycle(); } catch (Throwable ignored) {}
                }
            }
        }

        if (shouldSubmit) {
            mainHandler.postDelayed(() -> {
                ExecutorService exec = pool;
                if (exec != null && !exec.isShutdown()) {
                    exec.execute(() -> sendXiaomiKeyEvent("enter"));
                }
            }, 140L);
        }

        return String.format(Locale.US, "{\"ok\":true,\"injected\":%b,\"clipboard\":true}", injected);
    }

    private static AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isFocused()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        AccessibilityNodeInfo firstEditable = null;
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (child.isEditable() && child.isFocused()) {
                    if (firstEditable != null) {
                        try { firstEditable.recycle(); } catch (Throwable ignored) {}
                    }
                    return AccessibilityNodeInfo.obtain(child);
                }
                if (child.isEditable() && firstEditable == null) {
                    firstEditable = AccessibilityNodeInfo.obtain(child);
                }
                AccessibilityNodeInfo sub = findEditableNode(child);
                if (sub != null) {
                    if (firstEditable != null) {
                        try { firstEditable.recycle(); } catch (Throwable ignored) {}
                    }
                    return sub;
                }
            } finally {
                try { child.recycle(); } catch (Throwable ignored) {}
            }
        }
        return firstEditable;
    }

    /** Dynamically lists all launchable apps on the TV (`GET /api/apps`). */
    private static String handleApps() {
        if (appContext == null) {
            return "{\"ok\":false,\"apps\":[]}";
        }
        try {
            PackageManager pm = appContext.getPackageManager();
            Map<String, String> found = new LinkedHashMap<>();

            // Seed pinned priority packages first if installed
            for (String pkg : PINNED_PRIORITY) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if (ai != null && ai.enabled) {
                        CharSequence lbl = ai.loadLabel(pm);
                        found.put(pkg, lbl != null ? lbl.toString() : pkg);
                    }
                } catch (PackageManager.NameNotFoundException ignored) {}
            }

            String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String cat : categories) {
                List<ResolveInfo> list = pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
                if (list == null) continue;
                Collections.sort(list, (a, b) -> String.valueOf(a.loadLabel(pm))
                        .compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || EXCLUDED_PKGS.contains(pkg) || found.containsKey(pkg)) continue;
                    CharSequence label = ri.loadLabel(pm);
                    if (label == null || label.length() == 0) {
                        label = ri.activityInfo.applicationInfo.loadLabel(pm);
                    }
                    found.put(pkg, label != null ? label.toString() : pkg);
                }
            }

            StringBuilder sb = new StringBuilder("{\"ok\":true,\"apps\":[");
            boolean first = true;
            for (Map.Entry<String, String> entry : found.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"pkg\":\"").append(escapeJson(entry.getKey()))
                  .append("\",\"label\":\"").append(escapeJson(entry.getValue())).append("\"}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (Throwable e) {
            return "{\"ok\":false,\"apps\":[]}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "");
    }

    private static String handleApp(String pkg) {
        if (pkg == null || pkg.trim().isEmpty() || appContext == null) {
            return "{\"ok\":false,\"error\":\"missing_pkg\"}";
        }
        final String targetPkg = pkg.trim();
        final Context ctx = appContext;
        mainHandler.post(() -> {
            try {
                if ("android.settings.SETTINGS".equals(targetPkg)) {
                    Intent s = SystemSettings.vendorIntent(ctx);
                    if (s == null) s = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(s);
                    return;
                }
                Intent launch = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(targetPkg);
                if (launch == null) {
                    launch = ctx.getPackageManager().getLaunchIntentForPackage(targetPkg);
                }
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                }
            } catch (Throwable ignored) {}
        });
        return "{\"ok\":true}";
    }

    private static String handleHdmi(String sourceStr) {
        if (sourceStr == null || appContext == null) {
            return "{\"ok\":false,\"error\":\"missing_source\"}";
        }
        final int source;
        try {
            source = Integer.parseInt(sourceStr.trim());
        } catch (NumberFormatException e) {
            return "{\"ok\":false,\"error\":\"invalid_source\"}";
        }
        final Context ctx = appContext;
        mainHandler.post(() -> {
            try {
                Intent i = new Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                        .setClassName("com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.tvplayer.ExternalSourceActivity")
                        .putExtra("input", source)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {}
        });
        return "{\"ok\":true}";
    }

    private static String handleYoutube(String query) {
        if (query == null || query.trim().isEmpty() || appContext == null) {
            return "{\"ok\":false,\"error\":\"empty_query\"}";
        }
        final String q = query.trim();
        final Context ctx = appContext;
        mainHandler.post(() -> {
            try {
                String url;
                String lower = q.toLowerCase(Locale.ROOT);
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    url = q;
                } else if (lower.startsWith("youtu.be/") || lower.startsWith("www.youtube.com/") || lower.startsWith("youtube.com/") || lower.startsWith("m.youtube.com/")) {
                    url = "https://" + q;
                } else {
                    url = "https://www.youtube.com/results?search_query=" + Uri.encode(q);
                }
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .setComponent(new ComponentName("org.smarttube.stable",
                                "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(view);
            } catch (Throwable e) {
                try {
                    Intent fallback = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
                            .setPackage("org.smarttube.stable")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(fallback);
                } catch (Throwable ignored) {}
            }
        });
        return "{\"ok\":true}";
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
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            return ip;
                        }
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

    public static String getShortAddress(Context ctx) {
        String ip = getLocalIpAddress(ctx);
        return ip.isEmpty() ? "" : (ip + ":" + PORT);
    }

    private static final String CORNER_TAG = "web_remote_corner_qr";

    /** Corner QR is now replaced by the clean bottom-center footer hint; remove any legacy corner view. */
    public static void attachOrUpdateCornerQr(ViewGroup root, float density) {
        if (root == null) return;
        View card = root.findViewWithTag(CORNER_TAG);
        if (card != null) {
            root.removeView(card);
        }
    }

    public static void setCornerQrVisible(ViewGroup root, boolean visible) {
        // No floating corner box to toggle
    }

    /** Shows a glass dialog on the TV with a scannable QR code and URL for the Phone Remote. */
    public static void showQrDialog(Context activityCtx, float density) {
        ensureRunning(activityCtx, null);
        String ip = getLocalIpAddress(activityCtx);
        String lanUrl = ip.isEmpty() ? "http://192.168.100.4:" + PORT : ("http://" + ip + ":" + PORT);
        String url = "https://tv.tqd.me";

        LinearLayout card = new LinearLayout(activityCtx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = Math.round(32 * density);
        int padV = Math.round(26 * density);
        card.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE16181F);
        bg.setCornerRadius(22 * density);
        bg.setStroke(Math.max(1, Math.round(1.2f * density)), 0x33FFFFFF);
        card.setBackground(bg);

        TextView title = new TextView(activityCtx);
        title.setText("Điều khiển & Quản lý TV từ Điện thoại");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView sub = new TextView(activityCtx);
        sub.setText("Quét mã QR hoặc mở tv.tqd.me (Hoạt động cả qua Wi-Fi nhà & 4G/5G bên ngoài):");
        sub.setTextColor(0xB3FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Math.round(8 * density);
        subLp.bottomMargin = Math.round(16 * density);
        card.addView(sub, subLp);

        int qrSize = Math.round(185 * density);
        Bitmap qrBmp = createQrBitmap(url, qrSize);
        if (qrBmp != null) {
            ImageView qrView = new ImageView(activityCtx);
            qrView.setImageBitmap(qrBmp);
            GradientDrawable qrFrame = new GradientDrawable();
            qrFrame.setColor(Color.WHITE);
            qrFrame.setCornerRadius(14 * density);
            int qPad = Math.round(8 * density);
            qrView.setPadding(qPad, qPad, qPad, qPad);
            qrView.setBackground(qrFrame);
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrSize + qPad * 2, qrSize + qPad * 2);
            qrLp.bottomMargin = Math.round(16 * density);
            card.addView(qrView, qrLp);
        }

        TextView urlBadge = new TextView(activityCtx);
        urlBadge.setText(url + "  •  " + lanUrl);
        urlBadge.setTextColor(0xFF4DA3FF);
        urlBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        urlBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        urlBadge.setGravity(Gravity.CENTER);
        int bPadH = Math.round(18 * density);
        int bPadV = Math.round(8 * density);
        urlBadge.setPadding(bPadH, bPadV, bPadH, bPadV);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0x224DA3FF);
        badgeBg.setCornerRadius(10 * density);
        badgeBg.setStroke(Math.max(1, Math.round(density)), 0x554DA3FF);
        urlBadge.setBackground(badgeBg);
        card.addView(urlBadge);

        TextView hint = new TextView(activityCtx);
        hint.setText("Mẹo: Chọn \"Thêm vào Màn hình chính\" trên Safari / Chrome để dùng như ứng dụng Remote riêng.");
        hint.setTextColor(0x88FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Math.round(14 * density);
        card.addView(hint, hintLp);

        final Dialog dialog = new Dialog(activityCtx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.round(430 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.65f);
        }
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        card.requestFocus();
    }

    /**
     * Pure-Java ISO/IEC 18004 QR Code Version 3-L (29x29, ECC Level L, Mask 0) generator.
     * Encodes any URL up to 53 UTF-8 bytes without external libraries; clamps safely if longer.
     */
    public static Bitmap createQrBitmap(String text, int sizePx) {
        try {
            if (text == null || text.isEmpty()) text = "http://192.168.100.4:" + PORT;
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            if (data.length > 53) {
                String fallback = "http://" + getShortAddress(appContext);
                data = fallback.getBytes(StandardCharsets.UTF_8);
                if (data.length > 53) {
                    data = Arrays.copyOf(data, 53);
                }
            }

            int[] bits = new int[55 * 8];
            int bitLen = 0;
            bits[bitLen++] = 0; bits[bitLen++] = 1; bits[bitLen++] = 0; bits[bitLen++] = 0;
            for (int i = 7; i >= 0; i--) bits[bitLen++] = (data.length >> i) & 1;
            for (byte b : data) {
                int v = b & 0xFF;
                for (int i = 7; i >= 0; i--) bits[bitLen++] = (v >> i) & 1;
            }
            for (int i = 0; i < 4 && bitLen < bits.length; i++) bits[bitLen++] = 0;
            while ((bitLen & 7) != 0 && bitLen < bits.length) bits[bitLen++] = 0;

            int[] codewords = new int[55];
            int cwCount = bitLen >> 3;
            for (int i = 0; i < cwCount; i++) {
                int v = 0;
                for (int j = 0; j < 8; j++) v = (v << 1) | bits[(i << 3) + j];
                codewords[i] = v;
            }
            int[] pads = {0xEC, 0x11};
            for (int i = cwCount; i < 55; i++) {
                codewords[i] = pads[(i - cwCount) & 1];
            }

            int[] exp = new int[512];
            int[] log = new int[256];
            int x = 1;
            for (int i = 0; i < 255; i++) {
                exp[i] = x;
                log[x] = i;
                x <<= 1;
                if ((x & 0x100) != 0) x ^= 0x11D;
            }
            for (int i = 255; i < 512; i++) exp[i] = exp[i - 255];

            int[] gen = {1};
            for (int i = 0; i < 15; i++) {
                int[] nxt = new int[gen.length + 1];
                for (int j = 0; j < gen.length; j++) {
                    nxt[j] ^= gen[j];
                    int a = gen[j], b = exp[i];
                    nxt[j + 1] ^= (a == 0 || b == 0) ? 0 : exp[log[a] + log[b]];
                }
                gen = nxt;
            }

            int[] ec = new int[15];
            for (int b : codewords) {
                int factor = b ^ ec[0];
                System.arraycopy(ec, 1, ec, 0, 14);
                ec[14] = 0;
                if (factor != 0) {
                    for (int j = 0; j < 15; j++) {
                        int g = gen[j + 1];
                        ec[j] ^= (g == 0) ? 0 : exp[log[g] + log[factor]];
                    }
                }
            }

            int[] allCw = new int[70];
            System.arraycopy(codewords, 0, allCw, 0, 55);
            System.arraycopy(ec, 0, allCw, 55, 15);

            final int N = 29;
            int[][] mat = new int[N][N];
            boolean[][] isFunc = new boolean[N][N];

            int[][] finders = {{0, 0}, {0, N - 7}, {N - 7, 0}};
            for (int[] f : finders) {
                for (int dr = -1; dr <= 7; dr++) {
                    for (int dc = -1; dc <= 7; dc++) {
                        int r = f[0] + dr, c = f[1] + dc;
                        if (r >= 0 && r < N && c >= 0 && c < N) {
                            boolean dark = (dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6)
                                    && (dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4));
                            mat[r][c] = dark ? 1 : 0;
                            isFunc[r][c] = true;
                        }
                    }
                }
            }

            for (int dr = -2; dr <= 2; dr++) {
                for (int dc = -2; dc <= 2; dc++) {
                    boolean dark = Math.max(Math.abs(dr), Math.abs(dc)) != 1;
                    mat[22 + dr][22 + dc] = dark ? 1 : 0;
                    isFunc[22 + dr][22 + dc] = true;
                }
            }

            for (int i = 8; i < N - 8; i++) {
                int v = (i % 2 == 0) ? 1 : 0;
                mat[6][i] = v; isFunc[6][i] = true;
                mat[i][6] = v; isFunc[i][6] = true;
            }

            for (int i = 0; i < 9; i++) {
                if (!isFunc[8][i]) isFunc[8][i] = true;
                if (!isFunc[i][8]) isFunc[i][8] = true;
            }
            for (int i = 0; i < 8; i++) {
                isFunc[8][N - 1 - i] = true;
                isFunc[N - 1 - i][8] = true;
            }
            mat[21][8] = 1;
            isFunc[21][8] = true;

            int bitIdx = 0;
            int totalBits = allCw.length * 8;
            boolean up = true;
            for (int col = N - 1; col > 0; col -= 2) {
                if (col == 6) col--;
                for (int step = 0; step < N; step++) {
                    int r = up ? (N - 1 - step) : step;
                    for (int dc = 0; dc >= -1; dc--) {
                        int c = col + dc;
                        if (!isFunc[r][c]) {
                            int bit = 0;
                            if (bitIdx < totalBits) {
                                bit = (allCw[bitIdx >> 3] >> (7 - (bitIdx & 7))) & 1;
                                bitIdx++;
                            }
                            if (((r + c) & 1) == 0) bit ^= 1;
                            mat[r][c] = bit;
                        }
                    }
                }
                up = !up;
            }

            int fmt = 0x77C4;
            int[][] c1 = {{8,0},{8,1},{8,2},{8,3},{8,4},{8,5},{8,7},{8,8},{7,8},{5,8},{4,8},{3,8},{2,8},{1,8},{0,8}};
            int[][] c2 = {{28,8},{27,8},{26,8},{25,8},{24,8},{23,8},{22,8},{8,21},{8,22},{8,23},{8,24},{8,25},{8,26},{8,27},{8,28}};
            for (int i = 0; i < 15; i++) {
                int b = (fmt >> (14 - i)) & 1;
                mat[c1[i][0]][c1[i][1]] = b;
                mat[c2[i][0]][c2[i][1]] = b;
            }

            int border = 2;
            int modules = N + border * 2;
            int dim = Math.max(modules, sizePx);
            int[] pixels = new int[dim * dim];
            for (int y = 0; y < dim; y++) {
                int mr = (y * modules) / dim - border;
                for (int xPx = 0; xPx < dim; xPx++) {
                    int mc = (xPx * modules) / dim - border;
                    boolean dark = (mr >= 0 && mr < N && mc >= 0 && mc < N && mat[mr][mc] == 1);
                    pixels[y * dim + xPx] = dark ? 0xFF111318 : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, dim, 0, 0, dim, dim);
            return bmp;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String buildManifest() {
        return "{\n"
                + "  \"name\": \"Điều khiển Mi TV 4A\",\n"
                + "  \"short_name\": \"Mi TV Remote\",\n"
                + "  \"start_url\": \"/\",\n"
                + "  \"display\": \"standalone\",\n"
                + "  \"background_color\": \"#0B0D13\",\n"
                + "  \"theme_color\": \"#0B0D13\",\n"
                + "  \"icons\": [\n"
                + "    {\n"
                + "      \"src\": \"/icon.svg\",\n"
                + "      \"sizes\": \"any\",\n"
                + "      \"type\": \"image/svg+xml\",\n"
                + "      \"purpose\": \"any maskable\"\n"
                + "    }\n"
                + "  ]\n"
                + "}";
    }

    private static String buildIconSvg() {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 256 256'>"
                + "<rect width='256' height='256' rx='60' fill='#121622'/>"
                + "<circle cx='128' cy='108' r='64' fill='#1E2538' stroke='#3B82F6' stroke-width='6'/>"
                + "<circle cx='128' cy='108' r='24' fill='#3B82F6'/>"
                + "<rect x='76' y='194' width='104' height='22' rx='11' fill='#3B82F6'/>"
                + "</svg>";
    }

    private static String buildHtmlPage() {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"vi\">\n"
                + "<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover\">\n"
                + "<meta name=\"apple-mobile-web-app-capable\" content=\"yes\">\n"
                + "<meta name=\"apple-mobile-web-app-status-bar-style\" content=\"black-translucent\">\n"
                + "<meta name=\"apple-mobile-web-app-title\" content=\"Mi TV Remote\">\n"
                + "<meta name=\"theme-color\" content=\"#0B0D13\">\n"
                + "<link rel=\"manifest\" href=\"manifest.json\">\n"
                + "<link rel=\"apple-touch-icon\" href=\"icon.svg\">\n"
                + "<title>Điều khiển & Quản lý Mi TV 4A</title>\n"
                + "<style>\n"
                + ":root{--bg:#0b0d13;--card:rgba(26,30,43,0.88);--border:rgba(255,255,255,0.1);--accent:#3b82f6;--accent2:#60a5fa;--warn:#f59e0b;--danger:#ef4444;--ok:#22c55e;--text:#f8fafc;--sub:#94a3b8;}\n"
                + "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent;user-select:none;-webkit-user-select:none;}\n"
                + "body{margin:0;padding:env(safe-area-inset-top,12px) 14px env(safe-area-inset-bottom,28px);background:radial-gradient(circle at 50% 0%,#172036 0%,#0b0d13 65%);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,'SF Pro Display','Segoe UI',Roboto,sans-serif;min-height:100vh;display:flex;flex-direction:column;gap:12px;max-width:460px;margin-left:auto;margin-right:auto;}\n"
                + ".header{display:flex;align-items:center;justify-content:space-between;padding:6px 2px 0;gap:6px;flex-wrap:wrap;}\n"
                + ".brand{display:flex;align-items:center;gap:8px;}\n"
                + ".dot{width:10px;height:10px;border-radius:50%;background:#22c55e;box-shadow:0 0 10px #22c55e;}\n"
                + ".dot.cloud{background:#38bdf8;box-shadow:0 0 10px #38bdf8;}\n"
                + ".dot.off{background:#ef4444;box-shadow:0 0 10px #ef4444;}\n"
                + ".title{font-size:15px;font-weight:800;letter-spacing:-0.3px;}\n"
                + ".sub-bar{display:flex;align-items:center;justify-content:space-between;gap:6px;padding:0 2px;flex-wrap:wrap;}\n"
                + ".status-pill{font-size:11.5px;color:var(--sub);background:var(--card);border:1px solid var(--border);padding:4px 10px;border-radius:999px;font-weight:600;}\n"
                + ".status-pill.muted{color:#fca5a5;border-color:rgba(239,68,68,0.45);background:rgba(239,68,68,0.15);}\n"
                + ".status-pill.ok{color:#86efac;border-color:rgba(34,197,94,0.4);background:rgba(34,197,94,0.12);}\n"
                + ".pwr-btn{background:rgba(239,68,68,0.16);border:1px solid rgba(239,68,68,0.35);color:#fca5a5;padding:6px 12px;border-radius:999px;font-size:12px;font-weight:700;cursor:pointer;}\n"
                + ".pin-btn{background:rgba(59,130,246,0.16);border:1px solid rgba(59,130,246,0.35);color:#93c5fd;padding:6px 11px;border-radius:999px;font-size:11.5px;font-weight:700;cursor:pointer;}\n"
                + ".card{background:var(--card);border:1px solid var(--border);border-radius:20px;padding:13px;backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);box-shadow:0 10px 26px rgba(0,0,0,0.38);}\n"
                + ".card.parental{border-color:rgba(245,158,11,0.38);background:linear-gradient(180deg,rgba(30,27,38,0.92),rgba(21,24,36,0.92));}\n"
                + ".np-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;gap:8px;}\n"
                + ".np-badge{font-size:11px;font-weight:800;padding:3px 9px;border-radius:999px;background:rgba(34,197,94,0.18);color:#86efac;border:1px solid rgba(34,197,94,0.4);}\n"
                + ".np-badge.paused{background:rgba(245,158,11,0.18);color:#fcd34d;border-color:rgba(245,158,11,0.4);}\n"
                + ".np-badge.locked{background:rgba(239,68,68,0.22);color:#fca5a5;border-color:rgba(239,68,68,0.5);}\n"
                + ".np-title{font-size:14.5px;font-weight:700;line-height:1.38;color:#fff;margin-bottom:4px;user-select:text;-webkit-user-select:text;}\n"
                + ".np-sub{font-size:12px;color:#cbd5e1;line-height:1.35;}\n"
                + ".np-time{font-size:11.5px;color:#38bdf8;font-weight:700;margin-top:6px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;}\n"
                + ".timer-banner{display:none;background:rgba(245,158,11,0.16);border:1px solid rgba(245,158,11,0.45);color:#fde68a;padding:9px 12px;border-radius:13px;font-size:13px;font-weight:700;margin-bottom:10px;text-align:center;}\n"
                + ".timer-banner.locked{background:rgba(239,68,68,0.2);border-color:rgba(239,68,68,0.5);color:#fecaca;}\n"
                + ".chips{display:flex;gap:6px;overflow-x:auto;padding-bottom:6px;margin-bottom:6px;scrollbar-width:none;}\n"
                + ".chips::-webkit-scrollbar{display:none;}\n"
                + ".chip{background:rgba(255,255,255,0.06);border:1px solid var(--border);color:#e2e8f0;padding:6px 10px;border-radius:999px;font-size:11.5px;font-weight:600;white-space:nowrap;cursor:pointer;}\n"
                + ".chip:active{background:rgba(245,158,11,0.25);border-color:var(--warn);}\n"
                + ".t-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:6px;margin-top:8px;}\n"
                + ".t-btn{background:rgba(245,158,11,0.16);border:1px solid rgba(245,158,11,0.38);color:#fde68a;padding:9px 4px;border-radius:11px;font-size:12px;font-weight:700;cursor:pointer;text-align:center;}\n"
                + ".t-btn:active{transform:scale(0.95);background:rgba(245,158,11,0.32);}\n"
                + ".p-actions{display:grid;grid-template-columns:1fr 1fr 1fr;gap:7px;margin-top:8px;}\n"
                + ".p-btn{padding:10px 6px;border-radius:12px;font-size:12px;font-weight:700;border:none;cursor:pointer;color:#fff;}\n"
                + ".p-btn.msg{background:linear-gradient(135deg,#f59e0b,#d97706);grid-column:span 3;}\n"
                + ".p-btn.add{background:rgba(56,189,248,0.2);border:1px solid rgba(56,189,248,0.45);color:#bae6fd;}\n"
                + ".p-btn.lock{background:rgba(239,68,68,0.22);border:1px solid rgba(239,68,68,0.5);color:#fecaca;}\n"
                + ".p-btn.unlock{background:rgba(34,197,94,0.22);border:1px solid rgba(34,197,94,0.5);color:#bbf7d0;}\n"
                + ".yt-box{display:flex;flex-direction:column;gap:8px;}\n"
                + ".yt-input{width:100%;background:rgba(11,13,19,0.78);border:1px solid var(--border);border-radius:13px;padding:11px 13px;color:var(--text);font-size:14px;outline:none;user-select:text;-webkit-user-select:text;}\n"
                + ".yt-input:focus{border-color:var(--accent);}\n"
                + ".yt-actions{display:grid;grid-template-columns:1fr 1fr;gap:8px;}\n"
                + ".yt-send{background:linear-gradient(135deg,#ef4444,#dc2626);border:none;color:#fff;font-weight:700;font-size:13px;padding:10px 10px;border-radius:12px;cursor:pointer;}\n"
                + ".txt-send{background:linear-gradient(135deg,#3b82f6,#2563eb);border:none;color:#fff;font-weight:700;font-size:13px;padding:10px 10px;border-radius:12px;cursor:pointer;}\n"
                + ".yt-send:active,.txt-send:active,.p-btn:active{transform:scale(0.96);}\n"
                + ".mode-tabs{display:flex;background:rgba(11,13,19,0.65);border-radius:13px;padding:3px;margin-bottom:10px;border:1px solid var(--border);}\n"
                + ".tab{flex:1;text-align:center;padding:7px 0;font-size:13px;font-weight:600;color:var(--sub);border-radius:10px;cursor:pointer;}\n"
                + ".tab.active{background:rgba(59,130,246,0.22);color:#fff;border:1px solid rgba(59,130,246,0.45);}\n"
                + ".dpad-wrap{display:flex;justify-content:center;align-items:center;padding:2px 0;}\n"
                + ".dpad{position:relative;width:224px;height:224px;border-radius:50%;background:radial-gradient(circle,#22283a 0%,#151925 100%);border:1.5px solid rgba(255,255,255,0.12);box-shadow:inset 0 2px 8px rgba(255,255,255,0.06),0 10px 28px rgba(0,0,0,0.5);touch-action:manipulation;}\n"
                + ".d-btn{position:absolute;display:flex;align-items:center;justify-content:center;color:var(--text);background:transparent;border:none;cursor:pointer;font-size:20px;font-weight:700;touch-action:none;}\n"
                + ".d-btn:active{color:var(--accent2);transform:scale(0.9);}\n"
                + ".d-up{top:4px;left:62px;width:100px;height:64px;}\n"
                + ".d-down{bottom:4px;left:62px;width:100px;height:64px;}\n"
                + ".d-left{left:4px;top:62px;width:64px;height:100px;}\n"
                + ".d-right{right:4px;top:62px;width:64px;height:100px;}\n"
                + ".d-ok{top:68px;left:68px;width:88px;height:88px;border-radius:50%;background:linear-gradient(145deg,#2b334b,#1c2233);border:1.5px solid rgba(255,255,255,0.16);font-size:17px;font-weight:800;letter-spacing:0.5px;box-shadow:0 4px 14px rgba(0,0,0,0.45);}\n"
                + ".d-ok:active{background:var(--accent);color:#fff;transform:scale(0.94);}\n"
                + ".touchpad{display:none;width:100%;height:210px;border-radius:18px;background:radial-gradient(circle,#1e2436 0%,#131722 100%);border:1.5px dashed rgba(255,255,255,0.16);align-items:center;justify-content:center;flex-direction:column;color:var(--sub);font-size:13px;gap:6px;touch-action:none;}\n"
                + ".row-btns{display:grid;grid-template-columns:repeat(3,1fr);gap:9px;}\n"
                + ".nav-foot{display:grid;grid-template-columns:repeat(3,1fr);gap:9px;margin-top:12px;padding-top:12px;border-top:1px dashed rgba(255,255,255,0.1);}\n"
                + ".vol-card{border-color:rgba(56,189,248,0.34);background:linear-gradient(180deg,rgba(22,31,48,0.92),rgba(17,22,35,0.92));}\n"
                + ".vol-head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:10px;}\n"
                + ".vol-badge{font-size:12px;font-weight:800;padding:4px 11px;border-radius:999px;background:rgba(59,130,246,0.2);color:#93c5fd;border:1px solid rgba(59,130,246,0.45);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;}\n"
                + ".vol-badge.muted{background:rgba(239,68,68,0.22);color:#fca5a5;border-color:rgba(239,68,68,0.5);}\n"
                + ".vol-rocker{display:grid;grid-template-columns:0.85fr 1.3fr 1.15fr 1.3fr 0.85fr;gap:7px;}\n"
                + ".v-btn{background:rgba(30,38,58,0.92);border:1px solid rgba(255,255,255,0.12);color:var(--text);padding:11px 4px;border-radius:15px;font-size:12px;font-weight:800;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px;cursor:pointer;touch-action:none;}\n"
                + ".v-btn span{font-size:17px;line-height:1;}\n"
                + ".v-btn.main{background:linear-gradient(145deg,rgba(37,99,235,0.28),rgba(30,58,138,0.34));border-color:rgba(96,165,250,0.45);color:#eff6ff;font-size:13px;padding:12px 6px;}\n"
                + ".v-btn.step{background:rgba(15,23,42,0.85);color:#cbd5e1;font-size:11.5px;}\n"
                + ".v-btn.mute{background:rgba(239,68,68,0.14);border-color:rgba(239,68,68,0.36);color:#fecaca;}\n"
                + ".v-btn.mute.active{background:linear-gradient(135deg,#ef4444,#dc2626);border-color:#f87171;color:#fff;box-shadow:0 0 14px rgba(239,68,68,0.45);}\n"
                + ".v-btn:active{transform:scale(0.94);border-color:#60a5fa;background:rgba(59,130,246,0.35);}\n"
                + ".vol-slider-row{display:flex;align-items:center;gap:10px;margin-top:12px;padding:2px 2px;}\n"
                + ".vol-ico{font-size:16px;cursor:pointer;padding:4px;border-radius:8px;}\n"
                + ".vol-ico:active{transform:scale(0.9);}\n"
                + ".vol-slider{-webkit-appearance:none;appearance:none;flex:1;height:10px;border-radius:999px;background:linear-gradient(90deg,#3b82f6 0%,#38bdf8 var(--vol-pct,36%),rgba(255,255,255,0.12) var(--vol-pct,36%));outline:none;cursor:pointer;}\n"
                + ".vol-slider::-webkit-slider-thumb{-webkit-appearance:none;appearance:none;width:24px;height:24px;border-radius:50%;background:#fff;border:3px solid #3b82f6;box-shadow:0 2px 10px rgba(0,0,0,0.5);}\n"
                + ".vol-presets{display:grid;grid-template-columns:repeat(5,1fr);gap:6px;margin-top:10px;}\n"
                + ".vp-chip{background:rgba(15,23,42,0.78);border:1px solid var(--border);color:#cbd5e1;padding:7px 4px;border-radius:10px;font-size:11px;font-weight:700;text-align:center;cursor:pointer;white-space:nowrap;}\n"
                + ".vp-chip.active{background:rgba(59,130,246,0.26);border-color:#60a5fa;color:#fff;}\n"
                + ".vp-chip:active{transform:scale(0.95);}\n"
                + ".media-divider{margin-top:11px;padding-top:10px;border-top:1px dashed rgba(255,255,255,0.1);}\n"
                + ".media-btns{display:grid;grid-template-columns:repeat(5,1fr);gap:7px;}\n"
                + ".r-btn{background:rgba(30,36,52,0.9);border:1px solid var(--border);color:var(--text);padding:10px 5px;border-radius:14px;font-size:11.5px;font-weight:700;display:flex;flex-direction:column;align-items:center;gap:3px;cursor:pointer;touch-action:none;}\n"
                + ".r-btn span{font-size:15px;}\n"
                + ".r-btn:active{transform:scale(0.94);background:rgba(59,130,246,0.25);border-color:var(--accent);}\n"
                + ".sec-title{font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:0.8px;color:var(--sub);margin:0 0 9px 2px;}\n"
                + ".apps-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;}\n"
                + ".app-btn{background:rgba(20,24,35,0.85);border:1px solid var(--border);color:var(--text);padding:11px 6px;border-radius:13px;font-size:12px;font-weight:600;text-align:center;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}\n"
                + ".app-btn:active{transform:scale(0.95);border-color:var(--accent);background:rgba(59,130,246,0.2);}\n"
                + ".app-btn.primary{border-color:rgba(239,68,68,0.4);background:rgba(239,68,68,0.14);}\n"
                + ".hdmi-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;}\n"
                + ".yt-cards{display:grid;grid-template-columns:repeat(2,1fr);gap:8px;margin-top:8px;}\n"
                + ".yt-card{background:rgba(15,23,42,0.85);border:1px solid var(--border);border-radius:12px;overflow:hidden;cursor:pointer;display:flex;flex-direction:column;}\n"
                + ".yt-card:active{transform:scale(0.96);border-color:#ef4444;}\n"
                + ".yt-thumb{width:100%;aspect-ratio:16/9;object-fit:cover;background:#0f172a;display:block;}\n"
                + ".yt-meta{padding:7px 8px;}\n"
                + ".yt-vtitle{font-size:11.5px;font-weight:700;color:#f8fafc;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}\n"
                + ".yt-vsub{font-size:10px;color:#94a3b8;margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}\n"
                + ".sched-row{display:flex;align-items:center;justify-content:space-between;gap:6px;margin-top:10px;padding-top:10px;border-top:1px dashed rgba(255,255,255,0.12);flex-wrap:wrap;}\n"
                + ".time-inp{background:rgba(11,13,19,0.85);border:1px solid var(--border);color:#fff;border-radius:9px;padding:6px 8px;font-size:12px;font-weight:700;}\n"
                + ".meter-row{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:9px;}\n"
                + ".meter-box{background:rgba(15,23,42,0.75);border:1px solid var(--border);border-radius:12px;padding:8px 10px;}\n"
                + ".meter-lbl{font-size:11px;color:var(--sub);font-weight:600;display:flex;justify-content:space-between;}\n"
                + ".meter-bar{height:6px;background:rgba(255,255,255,0.08);border-radius:999px;margin-top:6px;overflow:hidden;}\n"
                + ".meter-fill{height:100%;background:linear-gradient(90deg,#22c55e,#3b82f6);border-radius:999px;width:45%;}\n"
                + ".chip.active{background:rgba(239,68,68,0.24);border-color:#f87171;color:#fecaca;}\n"
                + ".app-btn.active{border-color:#38bdf8;background:rgba(56,189,248,0.22);color:#fff;}\n"
                + ".dev-switcher{display:grid;grid-template-columns:1fr 1fr;gap:8px;background:rgba(15,23,42,0.85);padding:5px;border-radius:16px;border:1px solid var(--border);}\n"
                + ".dev-tab{padding:10px 8px;border-radius:12px;font-size:13px;font-weight:800;color:var(--sub);text-align:center;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:6px;border:1px solid transparent;}\n"
                + ".dev-tab.active{background:linear-gradient(135deg,rgba(59,130,246,0.32),rgba(37,99,235,0.24));color:#fff;border-color:rgba(96,165,250,0.55);box-shadow:0 4px 12px rgba(0,0,0,0.3);}\n"
                + ".tab-app-list{display:flex;flex-direction:column;gap:7px;margin-top:8px;max-height:280px;overflow-y:auto;}\n"
                + ".tab-app-row{display:flex;align-items:center;justify-content:space-between;gap:8px;background:rgba(15,23,42,0.78);border:1px solid var(--border);border-radius:12px;padding:8px 10px;}\n"
                + ".tab-app-name{font-size:13px;font-weight:700;color:#f8fafc;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}\n"
                + ".blk-btn{padding:6px 10px;border-radius:9px;font-size:11.5px;font-weight:800;cursor:pointer;border:1px solid rgba(34,197,94,0.45);background:rgba(34,197,94,0.16);color:#86efac;}\n"
                + ".blk-btn.blocked{border-color:rgba(239,68,68,0.55);background:rgba(239,68,68,0.24);color:#fecaca;}\n"
                + ".toast{position:fixed;bottom:18px;left:50%;transform:translateX(-50%);background:rgba(15,23,42,0.95);border:1px solid rgba(59,130,246,0.5);color:#fff;padding:8px 16px;border-radius:999px;font-size:12px;font-weight:600;opacity:0;pointer-events:none;transition:opacity 0.2s;z-index:99;white-space:nowrap;}\n"
                + ".toast.show{opacity:1;}\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"dev-switcher\">\n"
                + "  <div id=\"devTabTv\" class=\"dev-tab active\" onclick=\"switchDevice('50a009620752')\">📺 Tivi Mi TV 4A</div>\n"
                + "  <div id=\"devTabTablet\" class=\"dev-tab\" onclick=\"switchDevice('tablet01')\">📱 Máy Tính Bảng</div>\n"
                + "</div>\n"
                + "<div class=\"header\">\n"
                + "  <div class=\"brand\"><div id=\"dot\" class=\"dot\"></div><div id=\"connTitle\" class=\"title\">Mi TV 4A (Đang kết nối...)</div></div>\n"
                + "  <div style=\"display:flex;gap:6px\">\n"
                + "    <button class=\"pin-btn\" onclick=\"promptPin()\">🔑 Đổi PIN</button>\n"
                + "    <button id=\"topPwrBtn\" class=\"pwr-btn\" onclick=\"confirmPower()\">⏻ Nguồn</button>\n"
                + "  </div>\n"
                + "</div>\n"
                + "<div class=\"sub-bar\">\n"
                + "  <div id=\"volPill\" class=\"status-pill\" onclick=\"toggleMute()\" style=\"cursor:pointer\">🔊 Âm lượng: --</div>\n"
                + "  <div id=\"weatherPill\" class=\"status-pill\">🌤 Hà Nội</div>\n"
                + "  <div id=\"adbPill\" class=\"status-pill ok\" onclick=\"enableAdbNow()\" style=\"cursor:pointer\">🟢 ADB: Luôn bật</div>\n"
                + "</div>\n"
                + "\n"
                + "<div class=\"card\">\n"
                + "  <div class=\"np-head\">\n"
                + "    <div id=\"npSecTitle\" class=\"sec-title\" style=\"margin:0\">👧 Con Đang Xem Gì Trên Tivi</div>\n"
                + "    <div id=\"npBadge\" class=\"np-badge\">ĐANG KIỂM TRA</div>\n"
                + "  </div>\n"
                + "  <div id=\"npTitle\" class=\"np-title\">Đang tải trạng thái phát từ Tivi...</div>\n"
                + "  <div id=\"npSub\" class=\"np-sub\">Ứng dụng: --</div>\n"
                + "  <div id=\"npTime\" class=\"np-time\"></div>\n"
                + "  <div id=\"tabletQuickBar\" style=\"display:none;grid-template-columns:1fr 1fr 1fr;gap:7px;margin-top:10px;padding-top:10px;border-top:1px dashed rgba(255,255,255,0.12)\">\n"
                + "    <button type=\"button\" class=\"p-btn add\" onclick=\"key('home')\">🏠 Đóng App</button>\n"
                + "    <button type=\"button\" class=\"p-btn lock\" onclick=\"blockCurrentFgApp()\">🚫 Chặn App Này</button>\n"
                + "    <button type=\"button\" class=\"p-btn unlock\" onclick=\"parental('unlock',0)\">🔓 Mở Khóa Máy</button>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<div id=\"tabletAppsCard\" class=\"card\" style=\"display:none;border-color:rgba(56,189,248,0.4)\">\n"
                + "  <div class=\"np-head\">\n"
                + "    <div class=\"sec-title\" style=\"margin:0;color:#93c5fd\">🚫 Chặn / Cho Phép Ứng Dụng Trên Máy Tính Bảng</div>\n"
                + "  </div>\n"
                + "  <div style=\"display:grid;grid-template-columns:1fr 1fr;gap:7px;margin-bottom:9px\">\n"
                + "    <button type=\"button\" class=\"p-btn lock\" style=\"font-size:11.5px;padding:8px 6px\" onclick=\"blockGamesBatch(true)\">🚫 Khóa Toàn Bộ Game & TikTok</button>\n"
                + "    <button type=\"button\" class=\"p-btn unlock\" style=\"font-size:11.5px;padding:8px 6px\" onclick=\"blockGamesBatch(false)\">🟢 Mở Khóa Tất Cả Ứng Dụng</button>\n"
                + "  </div>\n"
                + "  <div id=\"tabletAppsList\" class=\"tab-app-list\">\n"
                + "    <div style=\"font-size:12px;color:#94a3b8;text-align:center;padding:10px\">Đang tải danh sách ứng dụng từ Máy tính bảng...</div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<div id=\"tvDpadCard\" class=\"card\">\n"
                + "  <div class=\"mode-tabs\">\n"
                + "    <div id=\"tabDpad\" class=\"tab active\" onclick=\"setMode('dpad')\">Phím Điều Hướng (Chạm / Giữ)</div>\n"
                + "    <div id=\"tabTouch\" class=\"tab\" onclick=\"setMode('touch')\">Bàn Rê Cảm Ứng</div>\n"
                + "  </div>\n"
                + "  <div id=\"dpadWrap\" class=\"dpad-wrap\">\n"
                + "    <div class=\"dpad\">\n"
                + "      <button id=\"btnUp\" class=\"d-btn d-up\" onclick=\"tapKey('up')\">▲</button>\n"
                + "      <button id=\"btnDown\" class=\"d-btn d-down\" onclick=\"tapKey('down')\">▼</button>\n"
                + "      <button id=\"btnLeft\" class=\"d-btn d-left\" onclick=\"tapKey('left')\">◀</button>\n"
                + "      <button id=\"btnRight\" class=\"d-btn d-right\" onclick=\"tapKey('right')\">▶</button>\n"
                + "      <button class=\"d-btn d-ok\" onclick=\"key('enter')\">OK</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "  <div id=\"touchWrap\" class=\"touchpad\">\n"
                + "    <div style=\"font-size:22px\">👆</div>\n"
                + "    <div>Vuốt 1 ngón để di chuyển · Chạm nhẹ để chọn OK</div>\n"
                + "  </div>\n"
                + "  <div class=\"nav-foot\">\n"
                + "    <button class=\"r-btn\" onclick=\"key('back')\"><span>↩</span>Quay lại</button>\n"
                + "    <button class=\"r-btn\" onclick=\"key('home')\"><span>⌂</span>Trang chủ</button>\n"
                + "    <button class=\"r-btn\" onclick=\"key('menu')\"><span>☰</span>Menu</button>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<div id=\"tvVolCard\" class=\"card vol-card\">\n"
                + "  <div class=\"vol-head\">\n"
                + "    <div class=\"sec-title\" style=\"margin:0;color:#93c5fd\">🔊 Âm Lượng & Điều Khiển Phát</div>\n"
                + "    <div id=\"volBadge\" class=\"vol-badge\">🔊 18 / 50 (36%)</div>\n"
                + "  </div>\n"
                + "  <div class=\"vol-rocker\">\n"
                + "    <button class=\"v-btn step\" onclick=\"stepVol(-5)\"><span>🔉</span>-5</button>\n"
                + "    <button id=\"btnVolDown\" class=\"v-btn main\" onclick=\"tapKey('voldown')\"><span>➖</span>Giảm</button>\n"
                + "    <button id=\"btnMute\" class=\"v-btn mute\" onclick=\"toggleMute()\"><span id=\"muteIcon\">🔇</span><b id=\"muteTxt\" style=\"font-weight:800\">Tắt tiếng</b></button>\n"
                + "    <button id=\"btnVolUp\" class=\"v-btn main\" onclick=\"tapKey('volup')\"><span>➕</span>Tăng</button>\n"
                + "    <button class=\"v-btn step\" onclick=\"stepVol(5)\"><span>🔊</span>+5</button>\n"
                + "  </div>\n"
                + "  <div class=\"vol-slider-row\">\n"
                + "    <span class=\"vol-ico\" onclick=\"setVol(0)\" title=\"Về 0\">🔈</span>\n"
                + "    <input id=\"volSlider\" class=\"vol-slider\" type=\"range\" min=\"0\" max=\"50\" value=\"18\" oninput=\"previewVol(this.value)\" onchange=\"commitVol(this.value)\">\n"
                + "    <span class=\"vol-ico\" onclick=\"stepVol(5)\" title=\"Tăng thêm\">🔊</span>\n"
                + "  </div>\n"
                + "  <div id=\"volPresets\" class=\"vol-presets\">\n"
                + "    <button class=\"vp-chip\" data-vol=\"0\" onclick=\"setVol(0)\">🔇 0</button>\n"
                + "    <button class=\"vp-chip\" data-vol=\"8\" onclick=\"setVol(8)\">🔈 8 · Đêm</button>\n"
                + "    <button class=\"vp-chip\" data-vol=\"16\" onclick=\"setVol(16)\">🔉 16 · Vừa</button>\n"
                + "    <button class=\"vp-chip\" data-vol=\"25\" onclick=\"setVol(25)\">🔊 25 · Rõ</button>\n"
                + "    <button class=\"vp-chip\" data-vol=\"35\" onclick=\"setVol(35)\">📢 35 · To</button>\n"
                + "  </div>\n"
                + "  <div class=\"media-divider\">\n"
                + "    <div class=\"media-btns\">\n"
                + "      <button class=\"r-btn\" onclick=\"key('prev')\"><span>⏮</span>Trước</button>\n"
                + "      <button class=\"r-btn\" onclick=\"key('rew')\"><span>⏪</span>Tua lùi</button>\n"
                + "      <button class=\"r-btn\" onclick=\"key('playpause')\"><span>⏯</span>Phát/Dừng</button>\n"
                + "      <button class=\"r-btn\" onclick=\"key('ff')\"><span>⏩</span>Tua tới</button>\n"
                + "      <button class=\"r-btn\" onclick=\"key('next')\"><span>⏭</span>Tiếp</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<div id=\"tvQuickAppsCard\" class=\"card\">\n"
                + "  <div class=\"sec-title\">Mở nhanh trên TV</div>\n"
                + "  <div id=\"appsGrid\" class=\"apps-grid\" style=\"grid-template-columns:repeat(2,1fr)\">\n"
                + "    <button class=\"app-btn primary\" style=\"padding:14px;font-size:14px\" onclick=\"app('org.smarttube.stable')\">▶️ YouTube</button>\n"
                + "    <button class=\"app-btn\" style=\"padding:14px;font-size:14px;background:rgba(59,130,246,0.18);border-color:rgba(59,130,246,0.45);color:#bfdbfe\" onclick=\"app('vn.vtv.vtvgotv')\">📺 Xem Tivi</button>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<div id=\"tvWallpaperCard\" class=\"card\">\n"
                + "  <div class=\"np-head\">\n"
                + "    <div class=\"sec-title\" style=\"margin:0\">🖼️ Hình Nền TV & 9 Giao Diện VGS</div>\n"
                + "    <div id=\"wpCaptionBadge\" class=\"status-pill\" style=\"font-size:10.5px\">🌍 Phong cảnh Thế giới</div>\n"
                + "  </div>\n"
                + "  <input id=\"wpFile\" type=\"file\" accept=\"image/*\" style=\"position:absolute;left:-9999px;width:1px;height:1px;opacity:0\" onchange=\"uploadPhoneWallpaper(this)\">\n"
                + "  <div style=\"display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px\">\n"
                + "    <label for=\"wpFile\" class=\"p-btn\" style=\"grid-column:span 2;background:linear-gradient(135deg,#0ea5e9,#2563eb);font-size:13px;padding:12px;text-align:center;display:block\">📷 Chọn Ảnh Từ Điện Thoại Làm Hình Nền TV</label>\n"
                + "    <button class=\"app-btn\" style=\"border-color:rgba(16,185,129,0.45);background:rgba(16,185,129,0.16);color:#a7f3d0\" onclick=\"setTheme('phong-canh')\">🌍 Cảnh Thế Giới Tự Đổi</button>\n"
                + "    <button class=\"app-btn\" style=\"border-color:rgba(56,189,248,0.45);background:rgba(56,189,248,0.16);color:#bae6fd\" onclick=\"nextWorldWallpaper()\">⏭ Đổi Cảnh Kế Tiếp</button>\n"
                + "  </div>\n"
                + "  <div id=\"wpPreviewWrap\" style=\"display:none;margin-bottom:10px;text-align:center\">\n"
                + "    <img id=\"wpPreviewImg\" style=\"width:100%;max-height:130px;object-fit:cover;border-radius:11px;border:1px solid rgba(56,189,248,0.45)\">\n"
                + "    <div id=\"wpUploadStatus\" style=\"font-size:11.5px;color:#93c5fd;margin-top:5px\"></div>\n"
                + "  </div>\n"
                + "  <div id=\"themeGrid\" class=\"apps-grid\" style=\"margin-bottom:10px\">\n"
                + "    <button class=\"app-btn\" data-theme=\"phong-canh\" onclick=\"setTheme('phong-canh')\">🏔 Phong Cảnh TG</button>\n"
                + "    <button class=\"app-btn\" data-theme=\"aurora\" onclick=\"setTheme('aurora')\">🌌 Cực Quang</button>\n"
                + "    <button class=\"app-btn\" data-theme=\"macos\" onclick=\"setTheme('macos')\">💻 macOS</button>\n"
                + "    <button class=\"app-btn\" data-theme=\"bau-troi\" onclick=\"setTheme('bau-troi')\">🌤 Bầu Trời</button>\n"
                + "    <button class=\"app-btn\" data-theme=\"dien-anh\" onclick=\"setTheme('dien-anh')\">🎬 Điện Ảnh</button>\n"
                + "    <button class=\"app-btn\" data-theme=\"vgs\" onclick=\"setTheme('vgs')\">✨ VGS Hoàng Gia</button>\n"
                + "    <button class=\"app-btn\" data-theme=\"sau-cot\" onclick=\"setTheme('sau-cot')\">🌊 Bắc Âu</button>\n"
                + "    <button class=\"app-btn\" data-theme=\"apple-tv\" onclick=\"setTheme('apple-tv')\">🍎 Apple TV</button>\n"
                + "    <button class=\"app-btn\" data-theme=\"toi-gian\" onclick=\"setTheme('toi-gian')\">⬛ OLED Đen</button>\n"
                + "  </div>\n"
                + "  <div class=\"chips\">\n"
                + "    <div class=\"chip\" onclick=\"setCity('Hà Nội',21.0285,105.8542)\">📍 Hà Nội</div>\n"
                + "    <div class=\"chip\" onclick=\"setCity('TP.HCM',10.8231,106.6297)\">📍 TP.HCM</div>\n"
                + "    <div class=\"chip\" onclick=\"setCity('Đà Nẵng',16.0544,108.2022)\">📍 Đà Nẵng</div>\n"
                + "    <div class=\"chip\" onclick=\"setCity('Hải Phòng',20.8449,106.6881)\">📍 Hải Phòng</div>\n"
                + "    <div class=\"chip\" onclick=\"setCity('Đà Lạt',11.9404,108.4583)\">📍 Đà Lạt</div>\n"
                + "    <div class=\"chip\" onclick=\"setCity('Nha Trang',12.2388,109.1967)\">📍 Nha Trang</div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<div id=\"tvSearchCard\" class=\"card\">\n"
                + "  <div class=\"sec-title\">Nhập liệu Tiếng Việt & Tìm kiếm nhanh</div>\n"
                + "  <div class=\"yt-box\">\n"
                + "    <input id=\"ytInput\" class=\"yt-input\" type=\"text\" placeholder=\"Nhập tiếng Việt hoặc dán link YouTube...\" autocomplete=\"off\">\n"
                + "    <div class=\"yt-actions\">\n"
                + "      <button class=\"yt-send\" type=\"button\" onclick=\"sendYt()\">▶ Phát / Tìm SmartTube</button>\n"
                + "      <button class=\"txt-send\" type=\"button\" onclick=\"sendTextToTv()\">⌨ Gửi chữ vào ô nhập TV</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<div class=\"card parental\">\n"
                + "  <div id=\"parentalSecTitle\" class=\"sec-title\" style=\"color:#fcd34d\">💌 Gửi Cảnh Báo Lên Tivi & Hẹn Giờ Tắt</div>\n"
                + "  <div id=\"timerBanner\" class=\"timer-banner\"></div>\n"
                + "  <div class=\"chips\">\n"
                + "    <div class=\"chip\" onclick=\"setPreset('Ba nhắn: Con xem hết tập này nhé, sẽ nghỉ sau ít phút nữa ❤️')\">💬 Hết tập/ván này nghỉ nhé</div>\n"
                + "    <div class=\"chip\" onclick=\"setPreset('Đến giờ ăn cơm / đi tắm rồi con gái yêu ơi ❤️')\">🍚 Đến giờ ăn cơm / tắm</div>\n"
                + "    <div class=\"chip\" onclick=\"setPreset('Muộn rồi, mình tắt máy đi ngủ sớm cho khỏe con nhé 🌙')\">🌙 Đến giờ đi ngủ rồi</div>\n"
                + "    <div class=\"chip\" onclick=\"setPreset('Con ngồi thẳng lưng & giữ khoảng cách để bảo vệ mắt nhé 👀')\">👀 Giữ khoảng cách mắt</div>\n"
                + "  </div>\n"
                + "  <input id=\"parentalMsg\" class=\"yt-input\" type=\"text\" placeholder=\"Nhập lời nhắn của Ba hiện lên màn hình...\" autocomplete=\"off\">\n"
                + "  <div class=\"t-grid\">\n"
                + "    <button class=\"t-btn\" onclick=\"parental('timer',3)\">⏱ 3p</button>\n"
                + "    <button class=\"t-btn\" onclick=\"parental('timer',5)\">⏱ 5p</button>\n"
                + "    <button class=\"t-btn\" onclick=\"parental('timer',10)\">⏱ 10p</button>\n"
                + "    <button class=\"t-btn\" onclick=\"parental('timer',15)\">⏱ 15p</button>\n"
                + "    <button class=\"t-btn\" onclick=\"parental('timer',30)\">⏱ 30p</button>\n"
                + "  </div>\n"
                + "  <div class=\"p-actions\">\n"
                + "    <button id=\"parentalMsgBtn\" class=\"p-btn msg\" onclick=\"parental('message',0)\">📢 Chỉ Hiện Lời Nhắn Lên Tivi (14 giây)</button>\n"
                + "    <button class=\"p-btn add\" onclick=\"parental('add',5)\">➕ Cộng 5p</button>\n"
                + "    <button class=\"p-btn lock\" onclick=\"parental('lock_now',0)\">🔒 Tắt & Khóa</button>\n"
                + "    <button class=\"p-btn unlock\" onclick=\"parental('unlock',0)\">🔓 Mở Khóa</button>\n"
                + "  </div>\n"
                + "  <div class=\"sched-row\">\n"
                + "    <label style=\"font-size:12px;font-weight:700;color:#fde68a;display:flex;align-items:center;gap:6px\">\n"
                + "      <input id=\"schedEnable\" type=\"checkbox\" onchange=\"saveSchedule()\" style=\"accent-color:#f59e0b;width:16px;height:16px\">\n"
                + "      🌙 Tự khóa giờ ngủ:\n"
                + "    </label>\n"
                + "    <div style=\"display:flex;align-items:center;gap:4px\">\n"
                + "      <input id=\"schedStart\" class=\"time-inp\" type=\"time\" value=\"21:30\" onchange=\"saveSchedule()\">\n"
                + "      <span style=\"font-size:11px;color:var(--sub)\">→</span>\n"
                + "      <input id=\"schedEnd\" class=\"time-inp\" type=\"time\" value=\"06:30\" onchange=\"saveSchedule()\">\n"
                + "    </div>\n"
                + "  </div>\n"
                + "  <div class=\"sched-row\">\n"
                + "    <span style=\"font-size:12px;font-weight:700;color:#93c5fd\">🔑 Mã PIN mở khóa màn hình:</span>\n"
                + "    <div style=\"display:flex;align-items:center;gap:6px\">\n"
                + "      <input id=\"newPinInput\" class=\"time-inp\" type=\"tel\" inputmode=\"numeric\" maxlength=\"6\" placeholder=\"4-6 số\" style=\"width:96px;text-align:center;letter-spacing:1.5px\">\n"
                + "      <button type=\"button\" class=\"pin-btn\" style=\"padding:6px 12px\" onclick=\"saveNewPin()\">Lưu PIN mới</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<div id=\"tvYtShelfCard\" class=\"card\">\n"
                + "  <div class=\"np-head\">\n"
                + "    <div class=\"sec-title\" style=\"margin:0;color:#fca5a5\">▶️ Mở Nhanh Video YouTube Cho Bé Trên TV</div>\n"
                + "    <div id=\"ytTopicBadge\" class=\"status-pill\" style=\"font-size:10.5px\">🧸 Hoạt hình</div>\n"
                + "  </div>\n"
                + "  <div class=\"chips\" id=\"ytTopicChips\">\n"
                + "    <div class=\"chip active\" data-topic=\"kids\" onclick=\"setYtTopic('kids')\">🧸 Hoạt hình</div>\n"
                + "    <div class=\"chip\" data-topic=\"english\" onclick=\"setYtTopic('english')\">🇬🇧 Tiếng Anh</div>\n"
                + "    <div class=\"chip\" data-topic=\"science\" onclick=\"setYtTopic('science')\">🔬 Khám phá</div>\n"
                + "    <div class=\"chip\" data-topic=\"music\" onclick=\"setYtTopic('music')\">🎵 Nhạc bé</div>\n"
                + "    <div class=\"chip\" data-topic=\"family\" onclick=\"setYtTopic('family')\">🔥 Gia đình</div>\n"
                + "  </div>\n"
                + "  <div style=\"display:flex;gap:6px;margin-top:4px\">\n"
                + "    <input id=\"ytCustomTopic\" class=\"yt-input\" style=\"padding:8px 11px;font-size:12.5px\" type=\"text\" placeholder=\"Tìm chủ đề video cho bé (VD: Doraemon, Peppa Pig)...\">\n"
                + "    <button class=\"yt-send\" style=\"white-space:nowrap;padding:8px 12px;font-size:12px\" onclick=\"applyCustomYtTopic()\">Tìm Video</button>\n"
                + "  </div>\n"
                + "  <div id=\"ytShelfCards\" class=\"yt-cards\"></div>\n"
                + "</div>\n"
                + "\n"
                + "<div id=\"tvCleanCard\" class=\"card\">\n"
                + "  <div class=\"sec-title\">🧹 Dọn Cache Tăng Tốc & Bộ Nhớ Tivi</div>\n"
                + "  <div class=\"meter-row\">\n"
                + "    <div class=\"meter-box\">\n"
                + "      <div class=\"meter-lbl\"><span>RAM Trống</span><span id=\"ramTxt\">-- MB</span></div>\n"
                + "      <div class=\"meter-bar\"><div id=\"ramBar\" class=\"meter-fill\"></div></div>\n"
                + "    </div>\n"
                + "    <div class=\"meter-box\">\n"
                + "      <div class=\"meter-lbl\"><span>Bộ Nhớ Trống</span><span id=\"romTxt\">-- MB</span></div>\n"
                + "      <div class=\"meter-bar\"><div id=\"romBar\" class=\"meter-fill\"></div></div>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "  <button class=\"p-btn\" style=\"width:100%;background:linear-gradient(135deg,#10b981,#059669);font-size:13px;padding:11px\" onclick=\"cleanTvCache()\">🧹 Dọn Cache & Giải Phóng RAM Ngay</button>\n"
                + "  <div id=\"cleanRes\" style=\"font-size:11.5px;color:#86efac;margin-top:6px;text-align:center\"></div>\n"
                + "</div>\n"
                + "\n"
                + "<div id=\"tvHdmiCard\" class=\"card\">\n"
                + "  <div class=\"sec-title\">Chuyển nguồn tín hiệu (HDMI / AV)</div>\n"
                + "  <div class=\"hdmi-grid\">\n"
                + "    <button class=\"app-btn\" onclick=\"hdmi(23)\">HDMI 1</button>\n"
                + "    <button class=\"app-btn\" onclick=\"hdmi(24)\">HDMI 2</button>\n"
                + "    <button class=\"app-btn\" onclick=\"hdmi(25)\">HDMI 3</button>\n"
                + "    <button class=\"app-btn\" onclick=\"hdmi(2)\">AV</button>\n"
                + "  </div>\n"
                + "</div>\n"
                + "<div id=\"toast\" class=\"toast\"></div>\n"
                + "\n"
                + "<script>\n"
                + "const urlParams=new URLSearchParams(location.search);\n"
                + "let DEVICE_ID=urlParams.get('device')||localStorage.getItem('tqd_active_device')||'50a009620752';\n"
                + "let TOPIC_CMD='agytv/'+DEVICE_ID+'/cmd';\n"
                + "let TOPIC_STATE='agytv/'+DEVICE_ID+'/state';\n"
                + "const deviceStates={};\n"
                + "const IS_LAN_HOST=(location.protocol==='http:'&&(location.port==='8686'||location.hostname.startsWith('192.168.')||location.hostname.startsWith('10.')));\n"
                + "let lanReachable=IS_LAN_HOST&&DEVICE_ID==='50a009620752';\n"
                + "let cloudConnected=false;\n"
                + "let wsMqtt=null;\n"
                + "let wsConnecting=false;\n"
                + "let wsBrokerIdx=0;\n"
                + "let lastRenderedSig='';\n"
                + "const pendingCloudQueue=[];\n"
                + "const WS_BROKERS=['wss://broker.emqx.io:8084/mqtt'];\n"
                + "const urlPin=urlParams.get('pin');\n"
                + "if(urlPin)localStorage.setItem('mitv_pin',urlPin.trim());\n"
                + "function isTabletMode(){return DEVICE_ID==='tablet01';}\n"
                + "function switchDevice(devId){\n"
                + "  hap(16);\n"
                + "  DEVICE_ID=devId;\n"
                + "  localStorage.setItem('tqd_active_device',devId);\n"
                + "  TOPIC_CMD='agytv/'+DEVICE_ID+'/cmd';\n"
                + "  TOPIC_STATE='agytv/'+DEVICE_ID+'/state';\n"
                + "  lanReachable=IS_LAN_HOST&&DEVICE_ID==='50a009620752';\n"
                + "  const tab=isTabletMode();\n"
                + "  const tabTv=document.getElementById('devTabTv');\n"
                + "  const tabTab=document.getElementById('devTabTablet');\n"
                + "  if(tabTv)tabTv.className='dev-tab'+(!tab?' active':'');\n"
                + "  if(tabTab)tabTab.className='dev-tab'+(tab?' active':'');\n"
                + "  const npSec=document.getElementById('npSecTitle');\n"
                + "  if(npSec)npSec.textContent=tab?'👧 Con Đang Dùng Gì Trên Máy Tính Bảng':'👧 Con Đang Xem Gì Trên Tivi';\n"
                + "  const pSec=document.getElementById('parentalSecTitle');\n"
                + "  if(pSec)pSec.textContent=tab?'💌 Gửi Cảnh Báo, Hẹn Giờ & Khóa Máy Tính Bảng':'💌 Gửi Cảnh Báo Lên Tivi & Hẹn Giờ Tắt';\n"
                + "  const pBtn=document.getElementById('parentalMsgBtn');\n"
                + "  if(pBtn)pBtn.textContent=tab?'📢 Chỉ Hiện Lời Nhắn Lên Máy Tính Bảng (14 giây)':'📢 Chỉ Hiện Lời Nhắn Lên Tivi (14 giây)';\n"
                + "  const topPwr=document.getElementById('topPwrBtn');\n"
                + "  if(topPwr)topPwr.textContent=tab?'🔒 Khóa Máy':'⏻ Nguồn';\n"
                + "  const tabCard=document.getElementById('tabletAppsCard');\n"
                + "  if(tabCard)tabCard.style.display=tab?'block':'none';\n"
                + "  const tabQBar=document.getElementById('tabletQuickBar');\n"
                + "  if(tabQBar)tabQBar.style.display=tab?'grid':'none';\n"
                + "  ['tvDpadCard','tvVolCard','tvQuickAppsCard','tvWallpaperCard','tvSearchCard','tvYtShelfCard','tvCleanCard','tvHdmiCard'].forEach(id=>{\n"
                + "    const el=document.getElementById(id);\n"
                + "    if(el)el.style.display=tab?'none':'block';\n"
                + "  });\n"
                + "  updateConnBadge();\n"
                + "  if(deviceStates[DEVICE_ID])syncStatusUi(deviceStates[DEVICE_ID],true);\n"
                + "  sendCloudCmd({type:'ping'});\n"
                + "}\n"
                + "function getPin(){return localStorage.getItem('mitv_pin')||'1601';}\n"
                + "function applyPinChange(raw){\n"
                + "  const clean=(raw||'').trim();\n"
                + "  if(!/^\\d{4,6}$/.test(clean)){\n"
                + "    showToast('⚠️ Mã PIN phải gồm từ 4 đến 6 chữ số!');\n"
                + "    return;\n"
                + "  }\n"
                + "  hap(20);\n"
                + "  localStorage.setItem('mitv_pin',clean);\n"
                + "  const inp=document.getElementById('newPinInput');\n"
                + "  if(inp)inp.value=clean;\n"
                + "  if(lanReachable){\n"
                + "    apiFetch('/api/parental?action=pin&newPin='+encodeURIComponent(clean)).catch(()=>{});\n"
                + "  }\n"
                + "  const payload=JSON.stringify({type:'set_pin',newPin:clean,pin:clean});\n"
                + "  if(wsMqtt&&wsMqtt.readyState===1&&cloudConnected){\n"
                + "    try{\n"
                + "      wsMqtt.send(mqttPubPkt('agytv/50a009620752/cmd',payload));\n"
                + "      wsMqtt.send(mqttPubPkt('agytv/tablet01/cmd',payload));\n"
                + "    }catch(e){}\n"
                + "  }else{\n"
                + "    sendCloudCmd({type:'set_pin',newPin:clean});\n"
                + "  }\n"
                + "  showToast('🔑 Đã đổi mã PIN mở khóa thành: '+clean+' (trên Tivi & Máy tính bảng)');\n"
                + "}\n"
                + "function saveNewPin(){\n"
                + "  const inp=document.getElementById('newPinInput');\n"
                + "  applyPinChange(inp?inp.value:'');\n"
                + "}\n"
                + "function promptPin(){\n"
                + "  const p=prompt('Nhập mã PIN mở khóa mới (4–6 chữ số, áp dụng cho cả Tivi & Máy tính bảng):',getPin());\n"
                + "  if(p!==null)applyPinChange(p);\n"
                + "}\n"
                + "(function(){const inp=document.getElementById('newPinInput');if(inp)inp.value=getPin();})();\n"
                + "function hap(ms){try{if(navigator.vibrate)navigator.vibrate(ms||14);}catch(e){}}\n"
                + "let toastTimer=null;\n"
                + "function showToast(msg){\n"
                + "  const t=document.getElementById('toast');\n"
                + "  t.textContent=msg;t.className='toast show';\n"
                + "  clearTimeout(toastTimer);\n"
                + "  toastTimer=setTimeout(()=>{t.className='toast';},2400);\n"
                + "}\n"
                + "function fmtSec(s){\n"
                + "  s=Math.max(0,Math.floor(s||0));\n"
                + "  const h=Math.floor(s/3600),m=Math.floor((s%3600)/60),sec=s%60;\n"
                + "  const pad=n=>n<10?'0'+n:''+n;\n"
                + "  return h>0?(h+':'+pad(m)+':'+pad(sec)):(pad(m)+':'+pad(sec));\n"
                + "}\n"
                + "function setPreset(txt){document.getElementById('parentalMsg').value=txt;hap(10);}\n"
                + "function apiFetch(url){return fetch(url,{headers:{'X-TV-Remote':'1'}});}\n"
                + "\n"
                + "// Zero-dependency MQTT 3.1.1 over WebSockets for 4G/5G Cloud Control\n"
                + "function encLen(x){const out=[];do{let b=x%128;x=Math.floor(x/128);if(x>0)b|=128;out.push(b);}while(x>0);return out;}\n"
                + "function encStr(s){const b=new TextEncoder().encode(s);return [(b.length>>8)&255,b.length&255,...b];}\n"
                + "function mqttConnectPkt(cid){const rem=[...encStr('MQTT'),4,2,0,30,...encStr(cid)];return new Uint8Array([0x10,...encLen(rem.length),...rem]);}\n"
                + "function mqttSubPkt(id,top){const rem=[(id>>8)&255,id&255,...encStr(top),0];return new Uint8Array([0x82,...encLen(rem.length),...rem]);}\n"
                + "function mqttPubPkt(top,str){const pl=new TextEncoder().encode(str);const rem=[...encStr(top),...pl];return new Uint8Array([0x30,...encLen(rem.length),...rem]);}\n"
                + "function flushCloudQueue(){\n"
                + "  if(!wsMqtt||wsMqtt.readyState!==1||!cloudConnected)return;\n"
                + "  while(pendingCloudQueue.length>0){\n"
                + "    const item=pendingCloudQueue.shift();\n"
                + "    try{wsMqtt.send(mqttPubPkt(TOPIC_CMD,JSON.stringify(item)));}catch(e){break;}\n"
                + "  }\n"
                + "}\n"
                + "function connectCloudMqtt(){\n"
                + "  if(wsConnecting||(wsMqtt&&(wsMqtt.readyState===0||wsMqtt.readyState===1)))return;\n"
                + "  wsConnecting=true;\n"
                + "  try{\n"
                + "    const url=WS_BROKERS[wsBrokerIdx%WS_BROKERS.length];wsBrokerIdx++;\n"
                + "    const ws=new WebSocket(url,'mqtt');\n"
                + "    ws.binaryType='arraybuffer';\n"
                + "    wsMqtt=ws;\n"
                + "    ws.onopen=()=>{\n"
                + "      wsConnecting=false;\n"
                + "      ws.send(mqttConnectPkt('web_'+Math.floor(Math.random()*999999)));\n"
                + "    };\n"
                + "    ws.onmessage=ev=>{\n"
                + "      const u=new Uint8Array(ev.data);\n"
                + "      if(u.length<2)return;\n"
                + "      const type=(u[0]>>4)&15;\n"
                + "      if(type===2){\n"
                + "        cloudConnected=true;updateConnBadge();\n"
                + "        ws.send(mqttSubPkt(1,'agytv/50a009620752/state'));\n"
                + "        ws.send(mqttSubPkt(2,'agytv/tablet01/state'));\n"
                + "        flushCloudQueue();\n"
                + "        sendCloudCmd({type:'ping'});\n"
                + "      }else if(type===3){\n"
                + "        let idx=1,mul=1,rLen=0;\n"
                + "        while(idx<u.length){const d=u[idx++];rLen+=(d&127)*mul;if((d&128)===0)break;mul*=128;}\n"
                + "        if(idx+2>u.length)return;\n"
                + "        const tLen=(u[idx]<<8)|u[idx+1];\n"
                + "        const topicStr=new TextDecoder().decode(u.slice(idx+2,idx+2+tLen));\n"
                + "        let pos=idx+2+tLen;\n"
                + "        if(((u[0]>>1)&3)>0)pos+=2;\n"
                + "        if(pos<=u.length){\n"
                + "          const txt=new TextDecoder().decode(u.slice(pos));\n"
                + "          try{\n"
                + "            const parsed=JSON.parse(txt);\n"
                + "            const msgDev=parsed.deviceId||(topicStr.indexOf('tablet01')>=0?'tablet01':'50a009620752');\n"
                + "            deviceStates[msgDev]=parsed;\n"
                + "            if(msgDev===DEVICE_ID)syncStatusUi(parsed);\n"
                + "          }catch(e){}\n"
                + "        }\n"
                + "      }\n"
                + "    };\n"
                + "    ws.onclose=()=>{wsConnecting=false;cloudConnected=false;updateConnBadge();setTimeout(connectCloudMqtt,1800);};\n"
                + "    ws.onerror=()=>{wsConnecting=false;try{ws.close();}catch(e){}};\n"
                + "  }catch(e){wsConnecting=false;setTimeout(connectCloudMqtt,2500);}\n"
                + "}\n"
                + "function ensureCloudConnected(){\n"
                + "  if(!wsMqtt||wsMqtt.readyState>1){cloudConnected=false;connectCloudMqtt();}\n"
                + "}\n"
                + "document.addEventListener('visibilitychange',()=>{if(!document.hidden){ensureCloudConnected();poll();}});\n"
                + "window.addEventListener('pageshow',()=>{ensureCloudConnected();poll();});\n"
                + "window.addEventListener('focus',()=>{ensureCloudConnected();poll();});\n"
                + "setInterval(()=>{if(wsMqtt&&wsMqtt.readyState===1){try{wsMqtt.send(new Uint8Array([0xC0,0x00]));}catch(e){}}else{ensureCloudConnected();}},12000);\n"
                + "function sendCloudCmd(obj){\n"
                + "  obj.pin=getPin();\n"
                + "  if(wsMqtt&&wsMqtt.readyState===1&&cloudConnected){\n"
                + "    try{wsMqtt.send(mqttPubPkt(TOPIC_CMD,JSON.stringify(obj)));return true;}catch(e){}\n"
                + "  }\n"
                + "  if(obj.type!=='ping'){\n"
                + "    if(pendingCloudQueue.length>=16)pendingCloudQueue.shift();\n"
                + "    pendingCloudQueue.push(obj);\n"
                + "  }\n"
                + "  ensureCloudConnected();\n"
                + "  return false;\n"
                + "}\n"
                + "function updateConnBadge(){\n"
                + "  const dot=document.getElementById('dot');\n"
                + "  const title=document.getElementById('connTitle');\n"
                + "  const devName=isTabletMode()?((deviceStates['tablet01']&&deviceStates['tablet01'].model)||'Máy Tính Bảng'):'Mi TV 4A';\n"
                + "  if(lanReachable){dot.className='dot';title.textContent=devName+' (Wi-Fi Nhà)';}\n"
                + "  else if(cloudConnected){dot.className='dot cloud';title.textContent=devName+' (Cloud 4G/5G)';}\n"
                + "  else{dot.className='dot off';title.textContent=devName+' (Đang kết nối...)';}\n"
                + "}\n"
                + "\n"
                + "let curVol=18,curMax=50,curMuted=false,lastLocalUnmuted=18,volLockUntil=0,volSlideTimer=null;\n"
                + "function renderVolUi(v,m,muted){\n"
                + "  if(typeof m==='number'&&m>0)curMax=m;\n"
                + "  if(typeof v==='number'&&v>=0)curVol=Math.max(0,Math.min(curMax,v));\n"
                + "  if(typeof muted==='boolean')curMuted=muted;\n"
                + "  if(curVol===0)curMuted=true;\n"
                + "  else if(curVol>0)lastLocalUnmuted=curVol;\n"
                + "  const pct=curMax>0?Math.round((curVol*100)/curMax):0;\n"
                + "  const slider=document.getElementById('volSlider');\n"
                + "  if(slider){\n"
                + "    slider.max=curMax;\n"
                + "    slider.value=curVol;\n"
                + "    slider.style.setProperty('--vol-pct',pct+'%');\n"
                + "  }\n"
                + "  const pill=document.getElementById('volPill');\n"
                + "  const badge=document.getElementById('volBadge');\n"
                + "  const btnMute=document.getElementById('btnMute');\n"
                + "  const muteIcon=document.getElementById('muteIcon');\n"
                + "  const muteTxt=document.getElementById('muteTxt');\n"
                + "  if(curMuted||curVol===0){\n"
                + "    if(pill){pill.textContent='🔇 Đã tắt tiếng (0/'+curMax+')';pill.className='status-pill muted';}\n"
                + "    if(badge){badge.textContent='🔇 TẮT TIẾNG • 0/'+curMax;badge.className='vol-badge muted';}\n"
                + "    if(btnMute)btnMute.className='v-btn mute active';\n"
                + "    if(muteIcon)muteIcon.textContent='🔊';\n"
                + "    if(muteTxt)muteTxt.textContent='Bật tiếng';\n"
                + "  }else{\n"
                + "    if(pill){pill.textContent='🔊 Âm lượng: '+curVol+'/'+curMax;pill.className='status-pill';}\n"
                + "    if(badge){badge.textContent='🔊 '+curVol+' / '+curMax+' ('+pct+'%)';badge.className='vol-badge';}\n"
                + "    if(btnMute)btnMute.className='v-btn mute';\n"
                + "    if(muteIcon)muteIcon.textContent='🔇';\n"
                + "    if(muteTxt)muteTxt.textContent='Tắt tiếng';\n"
                + "  }\n"
                + "  document.querySelectorAll('#volPresets .vp-chip').forEach(ch=>{\n"
                + "    const cv=parseInt(ch.getAttribute('data-vol'),10);\n"
                + "    ch.className='vp-chip'+((!curMuted&&cv===curVol)||(curMuted&&cv===0)?' active':'');\n"
                + "  });\n"
                + "}\n"
                + "function syncStatusUi(j,forceVol){\n"
                + "  if(!j)return;\n"
                + "  if(typeof j.vol==='number'&&j.vol>=0&&(forceVol||Date.now()>volLockUntil)){\n"
                + "    renderVolUi(j.vol,j.max||50,!!j.muted);\n"
                + "  }\n"
                + "  if(j.weather){\n"
                + "    document.getElementById('weatherPill').textContent=(isTabletMode()?'':'🌤 ')+j.weather;\n"
                + "  }\n"
                + "  if(typeof j.adbEnabled==='boolean'){\n"
                + "    const ap=document.getElementById('adbPill');\n"
                + "    if(isTabletMode()&&j.ip){\n"
                + "      ap.textContent=j.adbEnabled?('🟢 ADB: '+j.ip+':5555'):'🟠 ADB: Chạm để bật';\n"
                + "    }else{\n"
                + "      ap.textContent=j.adbEnabled?'🟢 ADB: Luôn bật':'🟠 ADB: Chạm để bật';\n"
                + "    }\n"
                + "    ap.className='status-pill '+(j.adbEnabled?'ok':'muted');\n"
                + "  }\n"
                + "  if(j.activeTheme){\n"
                + "    document.querySelectorAll('#themeGrid .app-btn').forEach(b=>{\n"
                + "      b.className='app-btn'+(b.getAttribute('data-theme')===j.activeTheme?' active':'');\n"
                + "    });\n"
                + "    const cb=document.getElementById('wpCaptionBadge');\n"
                + "    if(cb){\n"
                + "      if(j.activeTheme==='custom-photo')cb.textContent='📷 Ảnh từ Điện thoại';\n"
                + "      else if(j.wallpaperCaption)cb.textContent=j.wallpaperCaption;\n"
                + "      else cb.textContent='🎨 '+j.activeTheme;\n"
                + "    }\n"
                + "  }\n"
                + "  if(j.ramTotalMb>0){\n"
                + "    document.getElementById('ramTxt').textContent=j.ramFreeMb+' / '+j.ramTotalMb+' MB';\n"
                + "    const pct=Math.min(100,Math.max(8,Math.round((j.ramFreeMb*100)/j.ramTotalMb)));\n"
                + "    document.getElementById('ramBar').style.width=pct+'%';\n"
                + "  }\n"
                + "  if(j.storageTotalMb>0){\n"
                + "    document.getElementById('romTxt').textContent=j.storageFreeMb+' / '+j.storageTotalMb+' MB';\n"
                + "    const pct=Math.min(100,Math.max(8,Math.round((j.storageFreeMb*100)/j.storageTotalMb)));\n"
                + "    document.getElementById('romBar').style.width=pct+'%';\n"
                + "  }\n"
                + "  if(j.lastClean){\n"
                + "    document.getElementById('cleanRes').textContent=j.lastClean;\n"
                + "  }\n"
                + "  const p=j.parental||{};\n"
                + "  if(typeof p.scheduleEnabled==='boolean'&&document.activeElement!==document.getElementById('schedStart')&&document.activeElement!==document.getElementById('schedEnd')){\n"
                + "    document.getElementById('schedEnable').checked=p.scheduleEnabled;\n"
                + "    if(p.scheduleStart)document.getElementById('schedStart').value=p.scheduleStart;\n"
                + "    if(p.scheduleEnd)document.getElementById('schedEnd').value=p.scheduleEnd;\n"
                + "  }\n"
                + "  if(Array.isArray(j.tabletApps)){\n"
                + "    renderTabletApps(j.tabletApps);\n"
                + "  }\n"
                + "  if(j.ytShelf){\n"
                + "    if(j.ytShelf.label)document.getElementById('ytTopicBadge').textContent=j.ytShelf.label;\n"
                + "    document.querySelectorAll('#ytTopicChips .chip').forEach(c=>{\n"
                + "      c.className='chip'+(c.getAttribute('data-topic')===j.ytShelf.topic?' active':'');\n"
                + "    });\n"
                + "    if(Array.isArray(j.ytShelf.videos)&&j.ytShelf.videos.length>0){\n"
                + "      const sig=j.ytShelf.videos.map(v=>v.id).join(',');\n"
                + "      if(sig!==lastRenderedSig){\n"
                + "        lastRenderedSig=sig;\n"
                + "        renderYtShelfCards(j.ytShelf.videos);\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  const np=j.nowPlaying||{};\n"
                + "  const badge=document.getElementById('npBadge');\n"
                + "  const titleEl=document.getElementById('npTitle');\n"
                + "  const subEl=document.getElementById('npSub');\n"
                + "  const timeEl=document.getElementById('npTime');\n"
                + "  if(p.locked){\n"
                + "    badge.textContent='🔒 ĐANG KHÓA GIỜ NGHỈ';\n"
                + "    badge.className='np-badge locked';\n"
                + "  }else if(np.playing){\n"
                + "    badge.textContent='🟢 ĐANG PHÁT';\n"
                + "    badge.className='np-badge';\n"
                + "  }else{\n"
                + "    badge.textContent='⏸ ĐANG DỪNG / CHỜ';\n"
                + "    badge.className='np-badge paused';\n"
                + "  }\n"
                + "  if(np.title){\n"
                + "    titleEl.textContent=np.title;\n"
                + "    subEl.textContent='Ứng dụng: '+(np.appLabel||(isTabletMode()?'Máy tính bảng':'SmartTube'))+(np.channel?(' • '+np.channel):'');\n"
                + "    if(np.positionSec>0){\n"
                + "      timeEl.textContent='⏱ Đang xem ở phút '+fmtSec(np.positionSec)+(np.durationSec>0?(' / '+fmtSec(np.durationSec)):'');\n"
                + "    }else{timeEl.textContent='';}\n"
                + "  }else{\n"
                + "    const devLbl=isTabletMode()?'Máy tính bảng':'Tivi';\n"
                + "    titleEl.textContent=p.locked?(devLbl+' đang ở chế độ Khóa giờ nghỉ (Màn hình khóa PIN)'):'Không có video nào đang phát';\n"
                + "    subEl.textContent='Đang mở: '+(np.appLabel||('Màn hình chính '+devLbl));\n"
                + "    timeEl.textContent='';\n"
                + "  }\n"
                + "  const tb=document.getElementById('timerBanner');\n"
                + "  if(p.locked){\n"
                + "    tb.style.display='block';tb.className='timer-banner locked';\n"
                + "    tb.textContent='🔒 Thiết bị đang khóa (Có thể bấm \"Mở Khóa\" tại đây hoặc nhập PIN trên màn hình).';\n"
                + "  }else if(p.remainingSec>0){\n"
                + "    tb.style.display='block';tb.className='timer-banner';\n"
                + "    tb.textContent='⏳ Đang đếm ngược: Tự động khóa sau '+fmtSec(p.remainingSec);\n"
                + "  }else{\n"
                + "    tb.style.display='none';\n"
                + "  }\n"
                + "}\n"
                + "\n"
                + "function renderTabletApps(apps){\n"
                + "  const wrap=document.getElementById('tabletAppsList');\n"
                + "  if(!wrap)return;\n"
                + "  wrap.innerHTML='';\n"
                + "  apps.forEach(a=>{\n"
                + "    const row=document.createElement('div');\n"
                + "    row.className='tab-app-row';\n"
                + "    const nm=document.createElement('div');\n"
                + "    nm.className='tab-app-name';\n"
                + "    nm.textContent=a.label||a.pkg;\n"
                + "    const blk=document.createElement('button');\n"
                + "    blk.className='blk-btn'+(a.blocked?' blocked':'');\n"
                + "    blk.textContent=a.blocked?'🚫 Đang Khóa':'🟢 Cho Phép';\n"
                + "    blk.onclick=()=>toggleBlockTabletApp(a.pkg,!a.blocked,a.label);\n"
                + "    row.appendChild(nm);row.appendChild(blk);\n"
                + "    wrap.appendChild(row);\n"
                + "  });\n"
                + "}\n"
                + "function toggleBlockTabletApp(pkg,blocked,label){\n"
                + "  hap(20);\n"
                + "  showToast(blocked?('🚫 Đã chặn ứng dụng: '+(label||pkg)):('🟢 Đã cho phép: '+(label||pkg)));\n"
                + "  sendCloudCmd({type:'block_app',pkg:pkg,blocked:blocked});\n"
                + "}\n"
                + "function blockCurrentFgApp(){\n"
                + "  const st=deviceStates['tablet01'];\n"
                + "  const np=(st&&st.nowPlaying)||{};\n"
                + "  const pkg=np.pkg||'';\n"
                + "  if(!pkg||pkg.indexOf('launcher')>=0||pkg==='com.android.settings'){\n"
                + "    showToast('Hiện con đang ở Màn hình chính, không có ứng dụng nào đang mở để chặn.');\n"
                + "    return;\n"
                + "  }\n"
                + "  toggleBlockTabletApp(pkg,true,np.appLabel||pkg);\n"
                + "}\n"
                + "function blockGamesBatch(block){\n"
                + "  hap(22);\n"
                + "  if(block){\n"
                + "    const games='com.roblox.client.vnggames,com.roblox.client,com.blockblast.vn,com.maxgames.stickwarlegacy,com.maxgames.stickwar3,com.zhiliaoapp.musically,com.ss.android.ugc.trill';\n"
                + "    sendCloudCmd({type:'block_app',pkg:games,blocked:true});\n"
                + "    showToast('🚫 Đã chặn toàn bộ Game (Roblox, Block Blast, Stick War) & TikTok!');\n"
                + "  }else{\n"
                + "    sendCloudCmd({type:'block_app',pkg:'*',blocked:false});\n"
                + "    showToast('🟢 Đã mở khóa cho phép lại tất cả ứng dụng!');\n"
                + "  }\n"
                + "}\n"
                + "function renderYtShelfCards(videos){\n"
                + "  const wrap=document.getElementById('ytShelfCards');\n"
                + "  wrap.innerHTML='';\n"
                + "  videos.slice(0,6).forEach(v=>{\n"
                + "    const d=document.createElement('div');\n"
                + "    d.className='yt-card';\n"
                + "    d.onclick=()=>playYtShelfVideo(v.id,v.title);\n"
                + "    const img=document.createElement('img');\n"
                + "    img.className='yt-thumb';\n"
                + "    img.loading='lazy';\n"
                + "    img.src='https://i.ytimg.com/vi/'+v.id+'/mqdefault.jpg';\n"
                + "    const meta=document.createElement('div');\n"
                + "    meta.className='yt-meta';\n"
                + "    const t=document.createElement('div');\n"
                + "    t.className='yt-vtitle';\n"
                + "    t.textContent=v.title;\n"
                + "    const s=document.createElement('div');\n"
                + "    s.className='yt-vsub';\n"
                + "    s.textContent='▶ '+(v.channel||'YouTube')+(v.duration?(' • '+v.duration):'');\n"
                + "    meta.appendChild(t);meta.appendChild(s);\n"
                + "    d.appendChild(img);d.appendChild(meta);\n"
                + "    wrap.appendChild(d);\n"
                + "  });\n"
                + "}\n"
                + "async function setYtTopic(topic){\n"
                + "  hap(18);\n"
                + "  showToast('▶️ Đang đổi chủ đề YouTube trên màn hình TV...');\n"
                + "  if(lanReachable){\n"
                + "    try{const r=await apiFetch('/api/yt?action=topic&topic='+encodeURIComponent(topic));syncStatusUi(await r.json());return;}catch(e){}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'yt',action:'topic',topic:topic,q:''});\n"
                + "}\n"
                + "async function applyCustomYtTopic(){\n"
                + "  const q=document.getElementById('ytCustomTopic').value.trim();\n"
                + "  if(!q){showToast('Hãy nhập chủ đề muốn hiển thị trên kệ TV');return;}\n"
                + "  hap(20);\n"
                + "  showToast('▶️ Đang cập nhật kệ YouTube trên TV: '+q);\n"
                + "  if(lanReachable){\n"
                + "    try{const r=await apiFetch('/api/yt?action=topic&topic=custom&q='+encodeURIComponent(q));syncStatusUi(await r.json());return;}catch(e){}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'yt',action:'topic',topic:'custom',q:q});\n"
                + "}\n"
                + "async function playYtShelfVideo(id,title){\n"
                + "  hap(22);\n"
                + "  showToast('▶ Đang mở trên SmartTube: '+(title||id));\n"
                + "  if(lanReachable){\n"
                + "    try{const r=await apiFetch('/api/yt?action=play&id='+encodeURIComponent(id));syncStatusUi(await r.json());return;}catch(e){}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'yt',action:'play',id:id});\n"
                + "}\n"
                + "async function saveSchedule(){\n"
                + "  hap(16);\n"
                + "  const en=document.getElementById('schedEnable').checked;\n"
                + "  const st=document.getElementById('schedStart').value||'21:30';\n"
                + "  const ed=document.getElementById('schedEnd').value||'06:30';\n"
                + "  showToast(en?('🌙 Đã bật tự khóa '+st+' → '+ed):'🌙 Đã tắt lịch tự khóa giờ ngủ');\n"
                + "  if(lanReachable){\n"
                + "    try{const r=await apiFetch('/api/schedule?enabled='+(en?'1':'0')+'&start='+encodeURIComponent(st)+'&end='+encodeURIComponent(ed));syncStatusUi(await r.json());return;}catch(e){}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'schedule',enabled:en,start:st,end:ed});\n"
                + "}\n"
                + "async function setTheme(id){\n"
                + "  hap(20);\n"
                + "  showToast('🎨 Đang áp dụng giao diện: '+id);\n"
                + "  if(lanReachable){\n"
                + "    try{const r=await apiFetch('/api/theme?id='+encodeURIComponent(id));syncStatusUi(await r.json());return;}catch(e){}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'theme',id:id});\n"
                + "}\n"
                + "async function nextWorldWallpaper(){\n"
                + "  hap(18);\n"
                + "  showToast('⏭ Đang chuyển sang cảnh nổi tiếng thế giới tiếp theo...');\n"
                + "  if(lanReachable){\n"
                + "    try{const r=await apiFetch('/api/wallpaper?action=next');syncStatusUi(await r.json());return;}catch(e){}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'wp_next'});\n"
                + "}\n"
                + "function uploadPhoneWallpaper(inp){\n"
                + "  const file=inp&&inp.files&&inp.files[0];\n"
                + "  if(!file)return;\n"
                + "  hap(22);\n"
                + "  const wrap=document.getElementById('wpPreviewWrap');\n"
                + "  const prev=document.getElementById('wpPreviewImg');\n"
                + "  const st=document.getElementById('wpUploadStatus');\n"
                + "  wrap.style.display='block';\n"
                + "  st.textContent='⏳ Đang căn chỉnh ảnh 16:9 HD & gửi lên Tivi...';\n"
                + "  showToast('📷 Đang xử lý ảnh từ điện thoại...');\n"
                + "  const objUrl=URL.createObjectURL(file);\n"
                + "  prev.src=objUrl;\n"
                + "  const img=new Image();\n"
                + "  img.onload=async()=>{\n"
                + "    const cw=lanReachable?1280:720,ch=lanReachable?720:405;\n"
                + "    const cv=document.createElement('canvas');\n"
                + "    cv.width=cw;cv.height=ch;\n"
                + "    const ctx=cv.getContext('2d');\n"
                + "    const scale=Math.max(cw/img.width,ch/img.height);\n"
                + "    const sw=img.width*scale,sh=img.height*scale;\n"
                + "    ctx.drawImage(img,(cw-sw)/2,(ch-sh)/2,sw,sh);\n"
                + "    let b64=cv.toDataURL('image/jpeg',lanReachable?0.84:0.70).split(',')[1];\n"
                + "    if(!lanReachable&&b64.length>44000)b64=cv.toDataURL('image/jpeg',0.56).split(',')[1];\n"
                + "    if(lanReachable){\n"
                + "      try{\n"
                + "        const r=await fetch('/api/wallpaper',{method:'POST',headers:{'X-TV-Remote':'1'},body:b64+'\\r\\n'});\n"
                + "        const j=await r.json();syncStatusUi(j);\n"
                + "        st.textContent='✅ Đã đặt ảnh từ điện thoại làm hình nền Tivi!';\n"
                + "        showToast('✅ Đã đổi hình nền Tivi thành công!');\n"
                + "        inp.value='';return;\n"
                + "      }catch(e){}\n"
                + "    }\n"
                + "    for(let w=0;w<25&&(!wsMqtt||wsMqtt.readyState!==1);w++)await new Promise(r=>setTimeout(r,120));\n"
                + "    const chunkSize=14000;\n"
                + "    const total=Math.ceil(b64.length/chunkSize);\n"
                + "    const uid='wp_'+Date.now();\n"
                + "    for(let i=0;i<total;i++){\n"
                + "      const part=b64.slice(i*chunkSize,(i+1)*chunkSize);\n"
                + "      sendCloudCmd({type:'wp_chunk',id:uid,idx:i,total:total,data:part});\n"
                + "      st.textContent='☁️ Đang tải ảnh lên Tivi: '+Math.round(((i+1)*100)/total)+'%';\n"
                + "      await new Promise(res=>setTimeout(res,320));\n"
                + "    }\n"
                + "    setTimeout(()=>sendCloudCmd({type:'ping'}),500);\n"
                + "    st.textContent='✅ Đã đặt ảnh từ điện thoại làm hình nền Tivi!';\n"
                + "    showToast('✅ Đã đặt ảnh điện thoại làm hình nền Tivi!');\n"
                + "    inp.value='';\n"
                + "  };\n"
                + "  img.src=objUrl;\n"
                + "}\n"
                + "async function setCity(place,lat,lon){\n"
                + "  hap(16);\n"
                + "  showToast('🌤 Đang cập nhật thời tiết: '+place);\n"
                + "  if(lanReachable){\n"
                + "    try{const r=await apiFetch('/api/weather?place='+encodeURIComponent(place)+'&lat='+lat+'&lon='+lon);syncStatusUi(await r.json());return;}catch(e){}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'weather',place:place,lat:lat,lon:lon});\n"
                + "}\n"
                + "async function cleanTvCache(){\n"
                + "  hap(25);\n"
                + "  showToast('🧹 Đang dọn Cache & giải phóng RAM trên TV...');\n"
                + "  if(lanReachable){\n"
                + "    try{const r=await apiFetch('/api/clean');const j=await r.json();syncStatusUi(j);showToast('✅ '+(j.lastClean||'Đã dọn sạch Cache!'));return;}catch(e){}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'clean'});\n"
                + "}\n"
                + "async function parental(action,minutes){\n"
                + "  hap(22);\n"
                + "  const msg=document.getElementById('parentalMsg').value.trim();\n"
                + "  if(lanReachable){\n"
                + "    try{\n"
                + "      const r=await apiFetch('/api/parental?action='+encodeURIComponent(action)+'&minutes='+minutes+'&message='+encodeURIComponent(msg));\n"
                + "      const j=await r.json();syncStatusUi(j);\n"
                + "    }catch(e){sendCloudCmd({type:'parental',action:action,minutes:minutes,message:msg});}\n"
                + "  }else{\n"
                + "    sendCloudCmd({type:'parental',action:action,minutes:minutes,message:msg});\n"
                + "  }\n"
                + "  const devName=isTabletMode()?'Máy tính bảng':'Tivi';\n"
                + "  if(action==='unlock')showToast('🔓 Đã mở khóa '+devName+' & hủy hẹn giờ!');\n"
                + "  else if(action==='lock_now')showToast('🔒 Đã khóa màn hình '+devName+'!');\n"
                + "  else if(action==='timer')showToast('⏳ Đã hiện cảnh báo & hẹn khóa '+devName+' sau '+minutes+' phút!');\n"
                + "  else if(action==='add')showToast('➕ Đã cộng thêm '+minutes+' phút!');\n"
                + "  else showToast('📢 Đã hiện lời nhắn lên màn hình '+devName+'!');\n"
                + "}\n"
                + "async function enableAdbNow(){\n"
                + "  hap(15);\n"
                + "  if(lanReachable){try{await apiFetch('/api/adb');}catch(e){}}\n"
                + "  sendCloudCmd({type:'adb'});\n"
                + "  showToast('🟢 Đã kích hoạt giữ ADB luôn bật!');\n"
                + "}\n"
                + "async function key(c){\n"
                + "  hap(13);\n"
                + "  if(c==='volup'||c==='voldown'){\n"
                + "    volLockUntil=Date.now()+2400;\n"
                + "    const next=Math.max(0,Math.min(curMax,(curMuted?0:curVol)+(c==='volup'?1:-1)));\n"
                + "    renderVolUi(next,curMax,next===0);\n"
                + "  }\n"
                + "  if(lanReachable){\n"
                + "    try{const r=await apiFetch('/api/key?code='+encodeURIComponent(c));const j=await r.json();syncStatusUi(j,true);return;}catch(e){}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'key',code:c});\n"
                + "}\n"
                + "function toggleMute(){\n"
                + "  hap(18);\n"
                + "  volLockUntil=Date.now()+2600;\n"
                + "  if(!curMuted&&curVol>0){\n"
                + "    lastLocalUnmuted=curVol;\n"
                + "    renderVolUi(0,curMax,true);\n"
                + "    showToast('🔇 Đã tắt tiếng Tivi');\n"
                + "  }else{\n"
                + "    const restore=lastLocalUnmuted>0?lastLocalUnmuted:18;\n"
                + "    renderVolUi(restore,curMax,false);\n"
                + "    showToast('🔊 Đã bật tiếng: '+restore+'/'+curMax);\n"
                + "  }\n"
                + "  if(lanReachable){\n"
                + "    apiFetch('/api/key?code=mute').then(r=>r.json()).then(j=>syncStatusUi(j,true)).catch(()=>sendCloudCmd({type:'key',code:'mute'}));\n"
                + "  }else{\n"
                + "    sendCloudCmd({type:'key',code:'mute'});\n"
                + "  }\n"
                + "}\n"
                + "function stepVol(delta){\n"
                + "  const base=curMuted?0:curVol;\n"
                + "  const next=Math.max(0,Math.min(curMax,base+delta));\n"
                + "  setVol(next);\n"
                + "}\n"
                + "function previewVol(v){\n"
                + "  const lv=Math.max(0,Math.min(curMax,parseInt(v,10)||0));\n"
                + "  volLockUntil=Date.now()+2800;\n"
                + "  renderVolUi(lv,curMax,lv===0);\n"
                + "  if(volSlideTimer)clearTimeout(volSlideTimer);\n"
                + "  volSlideTimer=setTimeout(()=>{sendVolNow(lv,false);},200);\n"
                + "}\n"
                + "function commitVol(v){\n"
                + "  if(volSlideTimer){clearTimeout(volSlideTimer);volSlideTimer=null;}\n"
                + "  setVol(v);\n"
                + "}\n"
                + "async function sendVolNow(lv,toast){\n"
                + "  if(toast)showToast(lv===0?'🔇 Đã tắt tiếng (0/'+curMax+')':'🔊 Đã đặt âm lượng: '+lv+'/'+curMax);\n"
                + "  if(lanReachable){\n"
                + "    try{const r=await apiFetch('/api/volume?level='+lv);const j=await r.json();syncStatusUi(j,true);return;}catch(e){}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'volume',level:lv});\n"
                + "}\n"
                + "async function setVol(v){\n"
                + "  hap(14);\n"
                + "  const lv=Math.max(0,Math.min(curMax,parseInt(v,10)||0));\n"
                + "  volLockUntil=Date.now()+2600;\n"
                + "  renderVolUi(lv,curMax,lv===0);\n"
                + "  await sendVolNow(lv,true);\n"
                + "}\n"
                + "function app(pkg){\n"
                + "  hap(20);\n"
                + "  if(lanReachable)apiFetch('/api/app?pkg='+encodeURIComponent(pkg)).then(()=>showToast('Đang mở ứng dụng...')).catch(()=>sendCloudCmd({type:'app',pkg:pkg}));\n"
                + "  else{sendCloudCmd({type:'app',pkg:pkg});showToast('Đang mở ứng dụng qua Cloud...');}\n"
                + "}\n"
                + "function hdmi(s){\n"
                + "  hap(20);\n"
                + "  if(lanReachable)apiFetch('/api/hdmi?source='+s).then(()=>showToast('Đang chuyển nguồn HDMI/AV...')).catch(()=>sendCloudCmd({type:'hdmi',source:s}));\n"
                + "  else{sendCloudCmd({type:'hdmi',source:s});showToast('Đang chuyển nguồn HDMI/AV...');}\n"
                + "}\n"
                + "function confirmPower(){\n"
                + "  hap(25);\n"
                + "  if(isTabletMode()){\n"
                + "    parental('lock_now',0);\n"
                + "    return;\n"
                + "  }\n"
                + "  if(confirm('Tắt màn hình / Nguồn TV?\\n(Mẹo: Nếu muốn vừa tắt màn hình vừa khóa không cho con bật lại xem YouTube, hãy bấm nút \"🔒 Tắt & Khóa\" ở mục Quản lý giờ xem)')){\n"
                + "    key('power');\n"
                + "  }\n"
                + "}\n"
                + "function sendYt(){\n"
                + "  const inp=document.getElementById('ytInput');\n"
                + "  const q=inp.value.trim();\n"
                + "  if(!q){showToast('Hãy nhập từ khoá hoặc link YouTube');return;}\n"
                + "  hap(22);\n"
                + "  if(lanReachable)apiFetch('/api/youtube?q='+encodeURIComponent(q)).then(()=>{inp.blur();showToast('Đã mở trên SmartTube!');}).catch(()=>sendCloudCmd({type:'youtube',q:q}));\n"
                + "  else{sendCloudCmd({type:'youtube',q:q});inp.blur();showToast('Đã gửi lệnh mở SmartTube!');}\n"
                + "}\n"
                + "function sendTextToTv(){\n"
                + "  const inp=document.getElementById('ytInput');\n"
                + "  const q=inp.value.trim();\n"
                + "  if(!q){showToast('Hãy nhập chữ cần gửi vào TV');return;}\n"
                + "  hap(22);\n"
                + "  if(lanReachable){\n"
                + "    apiFetch('/api/text?text='+encodeURIComponent(q)+'&submit=1').then(r=>r.json()).then(j=>{\n"
                + "      inp.blur();\n"
                + "      showToast(j.injected?'Đã điền chữ tiếng Việt vào ô nhập TV!':'Đã lưu vào Clipboard TV & nhấn Enter!');\n"
                + "    }).catch(()=>sendCloudCmd({type:'text',text:q,mode:'replace',submit:'1'}));\n"
                + "  }else{\n"
                + "    sendCloudCmd({type:'text',text:q,mode:'replace',submit:'1'});\n"
                + "    inp.blur();showToast('Đã gửi chữ tiếng Việt về Tivi!');\n"
                + "  }\n"
                + "}\n"
                + "document.getElementById('ytInput').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();sendYt();}});\n"
                + "function setMode(m){\n"
                + "  hap(10);\n"
                + "  const d=m==='dpad';\n"
                + "  document.getElementById('tabDpad').className='tab'+(d?' active':'');\n"
                + "  document.getElementById('tabTouch').className='tab'+(!d?' active':'');\n"
                + "  document.getElementById('dpadWrap').style.display=d?'flex':'none';\n"
                + "  document.getElementById('touchWrap').style.display=d?'none':'flex';\n"
                + "}\n"
                + "const lastHoldTs={};\n"
                + "function tapKey(code){\n"
                + "  if(Date.now()-(lastHoldTs[code]||0)<420)return;\n"
                + "  key(code);\n"
                + "}\n"
                + "function bindHold(id,code){\n"
                + "  const el=document.getElementById(id);\n"
                + "  if(!el)return;\n"
                + "  let t1=null,t2=null,active=false;\n"
                + "  const stop=()=>{\n"
                + "    active=false;\n"
                + "    if(t1){clearTimeout(t1);t1=null;}\n"
                + "    if(t2){clearInterval(t2);t2=null;}\n"
                + "  };\n"
                + "  const start=e=>{\n"
                + "    if(e.cancelable)e.preventDefault();\n"
                + "    stop();\n"
                + "    active=true;\n"
                + "    lastHoldTs[code]=Date.now();\n"
                + "    key(code);\n"
                + "    t1=setTimeout(()=>{if(active)t2=setInterval(()=>{if(active){lastHoldTs[code]=Date.now();key(code);}},140);},320);\n"
                + "  };\n"
                + "  el.addEventListener('touchstart',start,{passive:false});\n"
                + "  el.addEventListener('touchend',stop);\n"
                + "  el.addEventListener('touchcancel',stop);\n"
                + "  el.addEventListener('mousedown',e=>{if('ontouchstart' in window)return;start(e);});\n"
                + "  el.addEventListener('mouseup',stop);\n"
                + "  el.addEventListener('mouseleave',stop);\n"
                + "}\n"
                + "bindHold('btnUp','up');bindHold('btnDown','down');bindHold('btnLeft','left');bindHold('btnRight','right');\n"
                + "bindHold('btnVolUp','volup');bindHold('btnVolDown','voldown');\n"
                + "(function(){\n"
                + "  const pad=document.getElementById('touchWrap');\n"
                + "  let sx=0,sy=0,st=0,multi=false;\n"
                + "  pad.addEventListener('touchstart',e=>{\n"
                + "    if(e.touches.length>1){multi=true;return;}\n"
                + "    multi=false;\n"
                + "    const t=e.touches[0];sx=t.clientX;sy=t.clientY;st=Date.now();\n"
                + "  },{passive:true});\n"
                + "  pad.addEventListener('touchmove',e=>{if(e.cancelable)e.preventDefault();},{passive:false});\n"
                + "  pad.addEventListener('touchcancel',()=>{multi=true;});\n"
                + "  pad.addEventListener('touchend',e=>{\n"
                + "    if(multi||e.changedTouches.length!==1)return;\n"
                + "    const t=e.changedTouches[0];\n"
                + "    const dx=t.clientX-sx,dy=t.clientY-sy,dt=Date.now()-st;\n"
                + "    if(Math.abs(dx)<20&&Math.abs(dy)<20){if(dt<350)key('enter');return;}\n"
                + "    if(Math.abs(dx)>Math.abs(dy))key(dx>0?'right':'left');\n"
                + "    else key(dy>0?'down':'up');\n"
                + "  });\n"
                + "})();\n"
                + "async function poll(){\n"
                + "  if(IS_LAN_HOST&&!isTabletMode()){\n"
                + "    try{\n"
                + "      const r=await fetch('/api/status');\n"
                + "      const j=await r.json();\n"
                + "      deviceStates['50a009620752']=j;\n"
                + "      lanReachable=true;updateConnBadge();\n"
                + "      syncStatusUi(j);\n"
                + "      return;\n"
                + "    }catch(e){lanReachable=false;updateConnBadge();}\n"
                + "  }\n"
                + "  sendCloudCmd({type:'ping'});\n"
                + "}\n"
                + "switchDevice(DEVICE_ID);\n"
                + "connectCloudMqtt();\n"
                + "poll();setInterval(poll,3000);\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>";
    }
}
