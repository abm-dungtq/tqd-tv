package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * CleanTV/VGS Weather Line ported and hardened for Android 6.0 (SDK 23).
 * Displays live temperature, Vietnamese condition, and city under the Lunar Date
 * (e.g. "24°C Trời quang · Hà Nội") and syncs to Web Remote / Cloud Tunnel.
 */
public final class Weather {
    private static final long FRESH_MS = 25L * 60L * 1000L;
    private static final String PREFS = "weather_cache";
    private static volatile WeakReference<TextView> activeViewRef = new WeakReference<>(null);

    private Weather() {}

    static void show(TextView view) {
        if (view == null) return;
        activeViewRef = new WeakReference<>(view);
        Context app = view.getContext().getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = prefs.getString("text", null);
        long at = prefs.getLong("at", 0);
        if (cached != null && !cached.isEmpty()) {
            view.setText(cached);
            view.setVisibility(View.VISIBLE);
        } else {
            String place = prefs.getString("place", "Hà Nội");
            view.setText("🌤 Thời tiết · " + place);
            view.setVisibility(View.VISIBLE);
        }
        if (cached != null && System.currentTimeMillis() - at < FRESH_MS) return;
        refreshAsync(app);
    }

    public static void refreshAsync(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String text = fetch(app);
            if (text == null || text.isEmpty()) return;
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString("text", text).putLong("at", System.currentTimeMillis()).apply();
            TextView tv = activeViewRef.get();
            if (tv != null) {
                tv.post(() -> {
                    tv.setText(text);
                    tv.setVisibility(View.VISIBLE);
                });
            }
            CloudTunnelClient.requestImmediatePublish();
        }, "weather-fetch").start();
    }

    public static String getCachedSummary(Context context) {
        if (context == null) return "Hà Nội";
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String text = prefs.getString("text", "");
        if (text == null || text.isEmpty()) {
            refreshAsync(context);
            return prefs.getString("place", "Hà Nội");
        }
        return text;
    }

    public static void setCity(Context context, String place, double lat, double lon) {
        if (context == null) return;
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("place", place != null && !place.trim().isEmpty() ? place.trim() : "Hà Nội")
                .putString("lat", String.valueOf(lat))
                .putString("lon", String.valueOf(lon))
                .remove("at")
                .apply();
        refreshAsync(context);
    }

    private static String fetch(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        double[] where = location(app, prefs);
        String place = prefs.getString("place", "Hà Nội");
        HttpURLConnection conn = null;
        try {
            String spec = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&timezone=auto",
                    where[0], where[1]);
            conn = (HttpURLConnection) new URL(spec).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn instanceof HttpsURLConnection) {
                try {
                    ((HttpsURLConnection) conn).setSSLSocketFactory(sockets(app));
                } catch (Exception ignored) {
                }
            }
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (status != 200) {
                Log.w("Weather", "http " + status);
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) body.append(line);
            }
            JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
            int degrees = (int) Math.round(current.getDouble("temperature_2m"));
            String cond = conditionVi(current.optInt("weather_code", -1));
            StringBuilder out = new StringBuilder();
            out.append(degrees).append("°C");
            if (!cond.isEmpty()) out.append(' ').append(cond);
            if (place != null && !place.isEmpty()) out.append(" · ").append(place);
            return out.toString();
        } catch (Exception e) {
            Log.w("Weather", e.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static double[] location(Context app, SharedPreferences prefs) {
        try {
            String sLat = prefs.getString("lat", null);
            String sLon = prefs.getString("lon", null);
            if (sLat != null && sLon != null) {
                double lat = Double.parseDouble(sLat);
                double lon = Double.parseDouble(sLon);
                if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
                    return new double[]{lat, lon};
                }
            }
        } catch (Exception ignored) {}

        for (String key : new String[]{"mitv_user_location", "mitv_location"}) {
            try {
                String raw = Settings.System.getString(app.getContentResolver(), key);
                if (raw == null || raw.isEmpty() || "null".equals(raw)) continue;
                JSONObject o = new JSONObject(raw);
                double lat = Double.parseDouble(o.getString("latitude"));
                double lon = Double.parseDouble(o.getString("longitude"));
                if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
                    return new double[]{lat, lon};
                }
            } catch (Exception ignored) {}
        }
        // Default: Hà Nội (21.0285, 105.8542)
        return new double[]{21.0285, 105.8542};
    }

    private static SSLSocketFactory sockets(Context app) throws Exception {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            for (String name : new String[]{"isrgrootx1.pem", "isrg-root-x2.pem"}) {
                try (InputStream in = app.getAssets().open(name)) {
                    ks.setCertificateEntry(name, cf.generateCertificate(in));
                }
            }
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, tmf.getTrustManagers(), null);
            return new Tls12(sc.getSocketFactory());
        } catch (Exception fallback) {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, trustAll, new SecureRandom());
            return new Tls12(sc.getSocketFactory());
        }
    }

    static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket enable(Socket socket) {
            if (socket instanceof SSLSocket) {
                ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.2"});
            }
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return enable(delegate.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException {
            return enable(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return enable(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return enable(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return enable(delegate.createSocket(host, port, local, localPort));
        }
    }

    /** Exact WMO weather code mapping from CleanTV/VGS (patches/launcher-vgs.patch Weather.java). */
    private static String conditionVi(int code) {
        switch (code) {
            case 0: return "Trời quang";
            case 1: return "Ít mây";
            case 2: return "Có mây";
            case 3: return "Nhiều mây";
            case 45:
            case 48: return "Sương mù";
            case 51:
            case 53:
            case 55:
            case 56:
            case 57: return "Mưa phùn";
            case 61:
            case 63:
            case 66:
            case 80:
            case 81: return "Mưa rào";
            case 65:
            case 67:
            case 82: return "Mưa to";
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86: return "Tuyết rơi";
            case 95:
            case 96:
            case 99: return "Giông bão";
            default: return "";
        }
    }
}
