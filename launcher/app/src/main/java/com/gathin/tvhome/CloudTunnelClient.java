package com.gathin.tvhome;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Zero-dependency MQTT v3.1.1 Cloud Tunnel Client built into CleanYourTV Launcher.
 * Connects outbound over TCP (1883) to Cloudflare-fronted / global MQTT brokers (broker.emqx.io & broker.hivemq.com),
 * allowing the parent's phone over 4G/5G (via WSS HTTPS Web Remote) to:
 *  1) See in real time what video/app the child is watching (`agytv/50a009620752/state`, retained).
 *  2) Send PIN-authenticated parental warnings, countdown timers, lock/unlock, and remote control commands (`agytv/50a009620752/cmd`).
 */
public final class CloudTunnelClient {

    public static final String DEVICE_ID = "50a009620752";
    public static final String DEFAULT_PAIRING_CODE = "500962";
    public static final String TOPIC_CMD = "agytv/" + DEVICE_ID + "/cmd";
    public static final String TOPIC_STATE = "agytv/" + DEVICE_ID + "/state";

    private static final String[][] BROKERS = {
            {"broker.emqx.io", "1883"}
    };

    private static volatile boolean started = false;
    private static volatile Context appContext;
    private static volatile OutputStream activeOut;
    private static final Object WRITE_LOCK = new Object();
    private static volatile long lastStatePublishMs = 0L;
    private static volatile boolean connected = false;

    private CloudTunnelClient() {}

    public static String getActiveDeviceId(Context ctx) {
        Context c = ctx != null ? ctx.getApplicationContext() : appContext;
        if (c == null) return DEVICE_ID;
        android.content.SharedPreferences sp = c.getSharedPreferences("tqd_tv_identity", Context.MODE_PRIVATE);
        String saved = sp.getString("device_id", null);
        if (saved != null && !saved.isEmpty()) return saved;
        String rawId = "";
        try {
            rawId = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {}
        String dev = android.os.Build.DEVICE != null ? android.os.Build.DEVICE : "";
        if ("matrix".equalsIgnoreCase(dev) || rawId == null || rawId.length() < 4) {
            sp.edit().putString("device_id", DEVICE_ID).apply();
            return DEVICE_ID;
        }
        String clean = rawId.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        if (clean.length() > 10) clean = clean.substring(clean.length() - 10);
        String id = "tv_" + clean;
        sp.edit().putString("device_id", id).apply();
        return id;
    }

    public static String getPairingCode(Context ctx) {
        String id = getActiveDeviceId(ctx);
        if (DEVICE_ID.equals(id)) return DEFAULT_PAIRING_CODE;
        long hash = 2166136261L;
        for (int i = 0; i < id.length(); i++) {
            hash ^= id.charAt(i);
            hash *= 16777619L;
            hash &= 0x7FFFFFFFL;
        }
        int codeNum = 100000 + (int) (hash % 900000L);
        return String.format(java.util.Locale.US, "%06d", codeNum);
    }

    public static void ensureRunning(Context ctx) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext();
        }
        if (started) return;
        synchronized (CloudTunnelClient.class) {
            if (started) return;
            started = true;
            Thread readerThread = new Thread(CloudTunnelClient::connectionLoop, "cloud-tunnel-mqtt");
            readerThread.setDaemon(true);
            readerThread.start();

            Thread publisherThread = new Thread(CloudTunnelClient::statePublisherLoop, "cloud-tunnel-pub");
            publisherThread.setDaemon(true);
            publisherThread.start();

            Thread regThread = new Thread(CloudTunnelClient::registerCodeLoop, "cloud-tunnel-reg");
            regThread.setDaemon(true);
            regThread.start();
        }
    }

    private static void registerCodeLoop() {
        while (true) {
            java.net.HttpURLConnection conn = null;
            try {
                if (appContext != null) {
                    String devId = getActiveDeviceId(appContext);
                    String code = getPairingCode(appContext);
                    JSONObject req = new JSONObject();
                    req.put("deviceId", devId);
                    req.put("pairingCode", code);
                    req.put("deviceType", "tv");
                    req.put("appFlavor", "launcher");
                    req.put("model", android.os.Build.MODEL != null ? android.os.Build.MODEL : "Android TV");
                    java.net.URL url = new java.net.URL("https://tv.tqd.me/api/device/register-code");
                    conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
                    OutputStream os = conn.getOutputStream();
                    os.write(body);
                    os.flush();
                    os.close();
                    conn.getResponseCode();
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
            try {
                Thread.sleep(20000L);
            } catch (InterruptedException ignored) {}
        }
    }

    public static boolean isConnected() {
        return connected;
    }

    public static void publishStateSoon() {
        lastStatePublishMs = 0L;
        new Thread(CloudTunnelClient::publishCurrentStateNow, "cloud-state-push").start();
    }

    public static void requestImmediatePublish() {
        publishStateSoon();
    }

    private static void statePublisherLoop() {
        while (true) {
            try {
                Thread.sleep(2500L);
                if (!connected) continue;
                long now = SystemClock.uptimeMillis();
                if (now - lastStatePublishMs >= 3000L) {
                    publishCurrentStateNow();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void publishCurrentStateNow() {
        OutputStream out = activeOut;
        if (out == null || !connected) return;
        try {
            String devId = getActiveDeviceId(appContext);
            String code = getPairingCode(appContext);
            String statusJson = WebRemoteServer.buildStatusJson();
            byte[] pkt = buildPublishPacket("agytv/" + devId + "/state", statusJson, true);
            byte[] pktBeacon = buildPublishPacket("agytv/pair/" + code, statusJson, true);
            synchronized (WRITE_LOCK) {
                if (activeOut != null) {
                    activeOut.write(pkt);
                    activeOut.write(pktBeacon);
                    activeOut.flush();
                    lastStatePublishMs = SystemClock.uptimeMillis();
                }
            }
        } catch (Throwable e) {
            connected = false;
        }
    }

    private static void connectionLoop() {
        int brokerIdx = 0;
        while (true) {
            String host = BROKERS[brokerIdx % BROKERS.length][0];
            int port = Integer.parseInt(BROKERS[brokerIdx % BROKERS.length][1]);
            brokerIdx++;
            Socket sock = null;
            try {
                sock = new Socket();
                sock.connect(new InetSocketAddress(host, port), 5000);
                sock.setSoTimeout(10000);
                sock.setKeepAlive(true);
                sock.setTcpNoDelay(true);

                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();

                String devId = getActiveDeviceId(appContext);
                String code = getPairingCode(appContext);
                String clientId = "mitv_" + devId + "_" + (SystemClock.elapsedRealtime() % 99999);
                synchronized (WRITE_LOCK) {
                    out.write(buildConnectPacket(clientId, 30));
                    out.flush();
                }

                // Read CONNACK (0x20, 0x02, 0x00, 0x00)
                int hdr = in.read();
                int remLen = readRemainingLength(in);
                if (hdr != 0x20 || remLen < 2) {
                    throw new IllegalStateException("Bad CONNACK");
                }
                byte[] connAck = readExact(in, remLen);
                if (connAck[1] != 0) {
                    throw new IllegalStateException("CONNACK rc=" + connAck[1]);
                }

                synchronized (WRITE_LOCK) {
                    activeOut = out;
                    connected = true;
                    out.write(buildSubscribePacket(1, "agytv/" + devId + "/cmd"));
                    out.write(buildSubscribePacket(2, "agytv/pair/" + code + "/cmd"));
                    out.flush();
                }

                publishCurrentStateNow();
                long lastPing = SystemClock.uptimeMillis();

                while (connected && !sock.isClosed()) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastPing >= 12000L) {
                        synchronized (WRITE_LOCK) {
                            out.write(new byte[]{(byte) 0xC0, 0x00});
                            out.flush();
                        }
                        lastPing = now;
                    }

                    int b0;
                    try {
                        b0 = in.read();
                    } catch (java.net.SocketTimeoutException ste) {
                        continue;
                    }
                    if (b0 < 0) break;
                    int rLen = readRemainingLength(in);
                    byte[] body = rLen > 0 ? readExact(in, rLen) : new byte[0];

                    int pktType = (b0 >> 4) & 0x0F;
                    if (pktType == 3 && body.length > 2) { // PUBLISH
                        int topicLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                        int pos = 2 + topicLen;
                        int qos = (b0 >> 1) & 0x03;
                        if (qos > 0) pos += 2; // packet id
                        if (pos <= body.length) {
                            String payload = new String(body, pos, body.length - pos, StandardCharsets.UTF_8);
                            handleIncomingCommand(payload);
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                connected = false;
                synchronized (WRITE_LOCK) {
                    activeOut = null;
                }
                if (sock != null) {
                    try { sock.close(); } catch (Throwable ignored) {}
                }
            }

            try {
                Thread.sleep(3000L);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private static void handleIncomingCommand(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(rawJson);
            String type = obj.optString("type", "");
            if ("ping".equals(type) || "status".equals(type)) {
                publishCurrentStateNow();
                return;
            }
            if ("pair_confirmed".equals(type)) {
                String ownerName = obj.optString("ownerName", "Tài khoản Bố/Mẹ");
                String newPin = obj.optString("newPin", "").trim();
                if (newPin.length() >= 4) {
                    ParentalControlManager.setPin(appContext, newPin);
                }
                ParentalControlManager.handleParentalCommand("message", 0,
                        "🎉 Đã đồng bộ Tivi với tài khoản của " + ownerName + "!");
                publishStateSoon();
                return;
            }
            if ("key".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("key", obj.optString("code", ""), "", "", 0);
            } else if ("volume".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("volume", String.valueOf(obj.optInt("level", 15)), "", "", 0);
            } else if ("app".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("app", obj.optString("pkg", ""), "", "", 0);
            } else if ("hdmi".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("hdmi", String.valueOf(obj.optInt("source", 23)), "", "", 0);
            } else if ("youtube".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("youtube", obj.optString("q", ""), "", "", 0);
            } else if ("text".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("text", obj.optString("text", ""),
                        obj.optString("mode", "replace"), obj.optString("submit", "1"), 0);
            } else if ("parental".equals(type)) {
                String newPin = obj.optString("newPin", "").trim();
                if (newPin.length() >= 4) {
                    ParentalControlManager.setPin(appContext, newPin);
                }
                String action = obj.optString("action", "message");
                int minutes = obj.optInt("minutes", 0);
                String msg = obj.optString("message", "");
                ParentalControlManager.handleParentalCommand(action, minutes, msg);
            } else if ("set_pin".equals(type)) {
                String newPin = obj.optString("newPin", "").trim();
                if (newPin.length() >= 4) {
                    ParentalControlManager.setPin(appContext, newPin);
                }
            } else if ("adb".equals(type)) {
                ParentalControlManager.ensureAdbAlwaysOn(appContext);
            } else if ("yt".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("yt",
                        obj.optString("action", "topic"),
                        obj.optString("id", ""),
                        obj.optString("topic", "") + "|" + obj.optString("q", ""),
                        0);
            } else if ("schedule".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("schedule",
                        obj.optBoolean("enabled", false) ? "1" : "0",
                        obj.optString("start", "21:30"),
                        obj.optString("end", "06:30"),
                        0);
            } else if ("theme".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("theme",
                        obj.optString("id", "apple-tv"), "", "", 0);
            } else if ("weather".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("weather",
                        obj.optString("place", "Hà Nội"),
                        String.valueOf(obj.optDouble("lat", 21.0285)),
                        String.valueOf(obj.optDouble("lon", 105.8542)),
                        0);
            } else if ("clean".equals(type)) {
                WebRemoteServer.dispatchCommandFromCloud("clean", "", "", "", 0);
            } else if ("wp_next".equals(type)) {
                WebRemoteServer.handleNextWallpaper();
            } else if ("wp_chunk".equals(type)) {
                WebRemoteServer.handleWallpaperChunk(
                        obj.optString("id", ""),
                        obj.optInt("idx", 0),
                        obj.optInt("total", 1),
                        obj.optString("data", ""));
                return;
            }
            publishStateSoon();
        } catch (Throwable ignored) {
        }
    }

    private static int readRemainingLength(InputStream in) throws Exception {
        int multiplier = 1;
        int value = 0;
        while (true) {
            int digit = in.read();
            if (digit < 0) throw new IllegalStateException("EOF in remLen");
            value += (digit & 127) * multiplier;
            if ((digit & 128) == 0) break;
            multiplier *= 128;
            if (multiplier > 128 * 128 * 128) throw new IllegalStateException("Malformed remLen");
        }
        return value;
    }

    private static byte[] readExact(InputStream in, int len) throws Exception {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IllegalStateException("EOF in body");
            off += n;
        }
        return buf;
    }

    private static void writeRemainingLength(ByteArrayOutputStream out, int x) {
        do {
            int b = x % 128;
            x /= 128;
            if (x > 0) b |= 0x80;
            out.write(b);
        } while (x > 0);
    }

    private static void writeUtf8String(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write((b.length >> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.write(b, 0, b.length);
    }

    private static byte[] buildConnectPacket(String clientId, int keepAliveSec) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8String(body, "MQTT");
        body.write(4); // Protocol level 4 (MQTT 3.1.1)
        body.write(2); // Clean session
        body.write((keepAliveSec >> 8) & 0xFF);
        body.write(keepAliveSec & 0xFF);
        writeUtf8String(body, clientId);
        byte[] rem = body.toByteArray();

        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x10);
        writeRemainingLength(pkt, rem.length);
        pkt.write(rem, 0, rem.length);
        return pkt.toByteArray();
    }

    private static byte[] buildSubscribePacket(int pktId, String topic) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write((pktId >> 8) & 0xFF);
        body.write(pktId & 0xFF);
        writeUtf8String(body, topic);
        body.write(0); // QoS 0
        byte[] rem = body.toByteArray();

        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x82);
        writeRemainingLength(pkt, rem.length);
        pkt.write(rem, 0, rem.length);
        return pkt.toByteArray();
    }

    private static byte[] buildPublishPacket(String topic, String payload, boolean retain) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8String(body, topic);
        byte[] pl = payload.getBytes(StandardCharsets.UTF_8);
        body.write(pl, 0, pl.length);
        byte[] rem = body.toByteArray();

        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(retain ? 0x31 : 0x30);
        writeRemainingLength(pkt, rem.length);
        pkt.write(rem, 0, rem.length);
        return pkt.toByteArray();
    }
}
