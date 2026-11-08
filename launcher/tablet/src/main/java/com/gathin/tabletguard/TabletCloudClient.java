package com.gathin.tabletguard;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public final class TabletCloudClient {

    public static final String LEGACY_DEVICE_ID = "tablet01";

    private static final String[] BROKERS = new String[]{"broker.emqx.io"};
    private static final int MQTT_PORT = 1883;
    private static final int KEEP_ALIVE_SEC = 25;

    private static volatile boolean started = false;
    private static volatile boolean connected = false;
    private static volatile OutputStream activeOut = null;
    private static final Object WRITE_LOCK = new Object();
    private static volatile Context appContext;
    private static volatile long lastStatePublishMs = 0L;

    private TabletCloudClient() {}

    public static String getActiveDeviceId() {
        return DeviceIdentityManager.getDeviceId(appContext);
    }

    public static String getActivePairingCode() {
        return DeviceIdentityManager.getPairingCode(appContext);
    }

    private static boolean shouldMirrorLegacyTopic() {
        return "KFTRWI".equalsIgnoreCase(Build.MODEL);
    }

    public static void ensureRunning(Context ctx) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext();
            DeviceIdentityManager.init(appContext);
        }
        if (started) return;
        synchronized (TabletCloudClient.class) {
            if (started) return;
            started = true;
            Thread worker = new Thread(TabletCloudClient::connectionLoop, "tablet-cloud-mqtt");
            worker.setDaemon(true);
            worker.start();

            Thread ticker = new Thread(TabletCloudClient::heartbeatLoop, "tablet-cloud-heartbeat");
            ticker.setDaemon(true);
            ticker.start();
        }
    }

    public static boolean isConnected() {
        return connected;
    }

    public static void publishStateSoon() {
        new Thread(() -> {
            try {
                Thread.sleep(100L);
                publishCurrentStateNow();
            } catch (Throwable ignored) {}
        }).start();
    }

    public static void publishCurrentStateNow() {
        if (!connected || activeOut == null) return;
        try {
            String devId = getActiveDeviceId();
            String code = getActivePairingCode();
            String json = TabletWebServer.buildStatusJson();
            byte[] pktPrimary = buildPublishPacket("agytv/" + devId + "/state", json, true);
            byte[] pktPairBeacon = buildPublishPacket("agytv/pair/" + code, json, true);
            byte[] pktLegacy = shouldMirrorLegacyTopic()
                    ? buildPublishPacket("agytv/" + LEGACY_DEVICE_ID + "/state", json, true)
                    : null;
            synchronized (WRITE_LOCK) {
                if (activeOut != null) {
                    activeOut.write(pktPrimary);
                    activeOut.write(pktPairBeacon);
                    if (pktLegacy != null) {
                        activeOut.write(pktLegacy);
                    }
                    activeOut.flush();
                    lastStatePublishMs = SystemClock.elapsedRealtime();
                }
            }
        } catch (Throwable e) {
            connected = false;
        }
    }

    private static void heartbeatLoop() {
        while (true) {
            try {
                Thread.sleep(4000L);
                if (!connected || activeOut == null) continue;
                synchronized (WRITE_LOCK) {
                    if (activeOut != null) {
                        activeOut.write(new byte[]{(byte) 0xC0, 0x00});
                        activeOut.flush();
                    }
                }
                if (SystemClock.elapsedRealtime() - lastStatePublishMs >= 4500L) {
                    publishCurrentStateNow();
                }
            } catch (Throwable e) {
                connected = false;
            }
        }
    }

    private static void connectionLoop() {
        int brokerIdx = 0;
        while (true) {
            Socket sock = null;
            String host = BROKERS[brokerIdx % BROKERS.length];
            brokerIdx++;
            try {
                sock = new Socket();
                sock.connect(new InetSocketAddress(host, MQTT_PORT), 6000);
                sock.setSoTimeout(KEEP_ALIVE_SEC * 1000);
                sock.setTcpNoDelay(true);
                sock.setKeepAlive(true);

                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();

                String devId = getActiveDeviceId();
                String code = getActivePairingCode();
                String clientId = "tqd_" + devId + "_" + (System.currentTimeMillis() % 10000);
                synchronized (WRITE_LOCK) {
                    out.write(buildConnectPacket(clientId, KEEP_ALIVE_SEC));
                    out.flush();
                }

                int h1 = in.read();
                int len1 = readRemainingLength(in);
                byte[] connAck = readExact(in, len1);
                if ((h1 & 0xF0) != 0x20 || connAck.length < 2 || connAck[1] != 0) {
                    throw new IllegalStateException("MQTT CONNACK rejected");
                }

                synchronized (WRITE_LOCK) {
                    activeOut = out;
                    connected = true;
                    out.write(buildSubscribePacket(1, "agytv/" + devId + "/cmd"));
                    out.write(buildSubscribePacket(2, "agytv/pair/" + code + "/cmd"));
                    if (shouldMirrorLegacyTopic()) {
                        out.write(buildSubscribePacket(3, "agytv/" + LEGACY_DEVICE_ID + "/cmd"));
                    }
                    out.flush();
                }

                publishCurrentStateNow();

                while (connected && !sock.isClosed()) {
                    int header;
                    try {
                        header = in.read();
                    } catch (SocketTimeoutException ste) {
                        synchronized (WRITE_LOCK) {
                            out.write(new byte[]{(byte) 0xC0, 0x00});
                            out.flush();
                        }
                        continue;
                    }
                    if (header < 0) break;
                    int remLen = readRemainingLength(in);
                    byte[] body = readExact(in, remLen);
                    int pktType = (header >> 4) & 0x0F;
                    if (pktType == 3 && body.length >= 2) {
                        int topicLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                        int pos = 2 + topicLen;
                        int qos = (header >> 1) & 0x03;
                        if (qos > 0) pos += 2;
                        if (pos <= body.length) {
                            String payload = new String(body, pos, body.length - pos, StandardCharsets.UTF_8);
                            handleCloudCommandJson(payload);
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
                Thread.sleep(2500L);
            } catch (InterruptedException ignored) {}
        }
    }

    private static void handleCloudCommandJson(String rawJson) {
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
                String deviceName = obj.optString("deviceName", DeviceIdentityManager.getFriendlyModel(appContext));
                String newPin = obj.optString("newPin", "").trim();
                DeviceIdentityManager.onPairConfirmed(appContext, ownerName, deviceName, newPin);
                TabletControlManager.handleParentalCommand("message", 0,
                        "🎉 Đã đồng bộ thiết bị với tài khoản của " + ownerName + " (" + deviceName + ")!");
                return;
            }
            if ("unpair".equals(type)) {
                DeviceIdentityManager.onUnpaired(appContext);
                return;
            }
            if ("key".equals(type)) {
                TabletWebServer.handleKey(obj.optString("code", ""));
            } else if ("volume".equals(type)) {
                TabletWebServer.handleVolume(String.valueOf(obj.optInt("level", 8)));
            } else if ("app".equals(type)) {
                TabletWebServer.handleApp(obj.optString("pkg", ""));
            } else if ("block_app".equals(type)) {
                TabletControlManager.setAppBlocked(obj.optString("pkg", ""), obj.optBoolean("blocked", true));
            } else if ("youtube".equals(type)) {
                TabletWebServer.handleYoutube(obj.optString("q", ""));
            } else if ("yt".equals(type)) {
                String action = obj.optString("action", "play");
                String id = obj.optString("id", "");
                if ("play".equals(action) && !id.isEmpty()) {
                    TabletWebServer.handleYoutube("https://www.youtube.com/watch?v=" + id);
                }
            } else if ("parental".equals(type)) {
                String newPin = obj.optString("newPin", "").trim();
                if (newPin.length() >= 4) {
                    TabletControlManager.setPin(appContext, newPin);
                }
                String action = obj.optString("action", "message");
                int minutes = obj.optInt("minutes", 0);
                String msg = obj.optString("message", "");
                TabletControlManager.handleParentalCommand(action, minutes, msg);
            } else if ("set_pin".equals(type)) {
                String newPin = obj.optString("newPin", "").trim();
                if (newPin.length() >= 4) {
                    TabletControlManager.setPin(appContext, newPin);
                }
            } else if ("adb".equals(type)) {
                TabletControlManager.ensureAdbAlwaysOn(appContext);
            } else if ("schedule".equals(type)) {
                TabletControlManager.setSchedule(
                        obj.optBoolean("enabled", false),
                        obj.optString("start", "21:30"),
                        obj.optString("end", "06:30"));
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
        body.write(4);
        body.write(2);
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
        body.write(0);
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
