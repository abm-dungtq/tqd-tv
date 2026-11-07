package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * U1 Upgrade: YouTube Recommended Videos Shelf directly on the TV Home Screen + controllable from tv.tqd.me.
 * Displays 16:9 video cards with thumbnails, duration badges, and topic filter pills; clicking any video opens
 * it directly in SmartTube (org.smarttube.stable).
 */
public final class YoutubeShelf {

    public static final class VideoItem {
        public final String id;
        public final String title;
        public final String channel;
        public final String duration;

        public VideoItem(String id, String title, String channel, String duration) {
            this.id = id != null ? id.trim() : "";
            this.title = title != null ? title.trim() : "";
            this.channel = channel != null ? channel.trim() : "YouTube";
            this.duration = duration != null ? duration.trim() : "";
        }
    }

    public interface NavigationHost {
        void onNavigateUpFromShelf();
        void onNavigateDownFromShelf();
        void onShelfItemFocused();
    }

    private static final String PREFS = "yt_shelf_prefs";
    private static final String KEY_TOPIC = "topic_id";
    private static final String KEY_CUSTOM_QUERY = "custom_query";
    private static final String KEY_CACHE_JSON = "cached_videos_json";

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService ioPool = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(24) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return 1;
        }
    };

    private static volatile List<VideoItem> currentVideos = Collections.emptyList();
    private static volatile YoutubeShelf activeInstance;

    private final Context context;
    private final NavigationHost navHost;
    private final float density;
    private final LinearLayout rootContainer;
    private final TextView titleHeaderView;
    private final LinearLayout pillsRow;
    private final HorizontalScrollView videoScroller;
    private final LinearLayout cardsRow;
    private final List<View> cardViews = new ArrayList<>();
    private final List<TextView> pillViews = new ArrayList<>();

    public static final String[][] TOPICS = new String[][]{
            {"kids", "Hoạt hình", "hoạt hình thiếu nhi tiếng việt vui nhộn"},
            {"english", "Tiếng Anh", "super simple songs english for kids"},
            {"science", "Khám phá", "khám phá khoa học thế giới động vật cho bé"},
            {"music", "Nhạc bé", "nhạc thiếu nhi vui nhộn tiếng việt"},
            {"family", "Gia đình", "giải trí gia đình việt nam hay nhất"}
    };

    public YoutubeShelf(Context context, NavigationHost navHost) {
        this.context = context;
        this.navHost = navHost;
        this.density = context.getResources().getDisplayMetrics().density;
        activeInstance = this;

        rootContainer = new LinearLayout(context);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        rootContainer.setClipChildren(false);
        rootContainer.setClipToPadding(false);

        // Top row: Title on left + Topic pills on right
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setClipChildren(false);

        titleHeaderView = new TextView(context);
        titleHeaderView.setTextColor(0xFFE2E8F0);
        titleHeaderView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        titleHeaderView.setTypeface(UiFont.medium(context), Typeface.BOLD);
        titleHeaderView.setSingleLine(true);
        titleHeaderView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.rightMargin = Math.round(12 * density);
        headerRow.addView(titleHeaderView, titleLp);

        pillsRow = new LinearLayout(context);
        pillsRow.setOrientation(LinearLayout.HORIZONTAL);
        pillsRow.setGravity(Gravity.CENTER_VERTICAL);
        buildTopicPills();
        headerRow.addView(pillsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rootContainer.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Horizontal video scroller
        videoScroller = new HorizontalScrollView(context);
        videoScroller.setHorizontalScrollBarEnabled(false);
        videoScroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        videoScroller.setClipChildren(false);
        videoScroller.setClipToPadding(false);
        videoScroller.setFocusable(false);

        cardsRow = new LinearLayout(context);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        cardsRow.setClipChildren(false);
        cardsRow.setClipToPadding(false);
        int padV = Math.round(8 * density);
        int padH = Math.round(4 * density);
        cardsRow.setPadding(padH, padV, padH, padV);

        videoScroller.addView(cardsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams scrollerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollerLp.topMargin = Math.round(2 * density);
        rootContainer.addView(videoScroller, scrollerLp);

        // Load initial seed/cached videos immediately and trigger background refresh
        List<VideoItem> initial = loadCachedOrSeedVideos(context);
        currentVideos = initial;
        renderVideoCards(initial);
        refreshAsync(context, false);
    }

    public View getView() {
        return rootContainer;
    }

    public boolean requestFocusOnShelf() {
        if (!cardViews.isEmpty()) {
            cardViews.get(0).requestFocus();
            return true;
        }
        if (!pillViews.isEmpty()) {
            pillViews.get(0).requestFocus();
            return true;
        }
        return false;
    }

    public boolean hasFocusInShelf() {
        return rootContainer.hasFocus();
    }

    private void buildTopicPills() {
        pillsRow.removeAllViews();
        pillViews.clear();
        String activeTopic = getActiveTopicId(context);

        for (int i = 0; i < TOPICS.length; i++) {
            final String topicId = TOPICS[i][0];
            final String label = TOPICS[i][1];
            final int index = i;
            TextView pill = createPillButton(label, topicId.equals(activeTopic));
            pill.setOnClickListener(v -> setTopicAndRefresh(context, topicId, ""));
            pill.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (navHost != null) navHost.onNavigateUpFromShelf();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!cardViews.isEmpty()) {
                        cardViews.get(0).requestFocus();
                        return true;
                    }
                    if (navHost != null) navHost.onNavigateDownFromShelf();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (index > 0) pillViews.get(index - 1).requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (index + 1 < pillViews.size()) pillViews.get(index + 1).requestFocus();
                    return true;
                }
                return false;
            });
            pillViews.add(pill);
            pillsRow.addView(pill);
        }
    }

    private TextView createPillButton(String text, boolean selected) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTypeface(UiFont.medium(context));
        int padH = Math.round(10 * density);
        int padV = Math.round(4 * density);
        tv.setPadding(padH, padV, padH, padV);
        tv.setFocusable(true);
        tv.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(6 * density);
        tv.setLayoutParams(lp);
        stylePill(tv, selected, false);
        tv.setOnFocusChangeListener((v, hasFocus) -> {
            boolean isSel = isPillSelected(tv);
            stylePill(tv, isSel, hasFocus);
            if (hasFocus && navHost != null) {
                navHost.onShelfItemFocused();
            }
        });
        return tv;
    }

    private boolean isPillSelected(TextView tv) {
        Object tag = tv.getTag();
        return tag instanceof Boolean && (Boolean) tag;
    }

    private int themeAccentColor() {
        String id = WebRemoteServer.getActiveThemeId(context);
        if ("macos".equals(id)) return 0xFFA855F7;
        if ("aurora".equals(id)) return 0xFF10B981;
        if ("phong-canh".equals(id)) return 0xFF0EA5E9;
        if ("bau-troi".equals(id)) return 0xFFF97316;
        if ("dien-anh".equals(id)) return 0xFFEAB308;
        if ("vgs".equals(id)) return 0xFFF59E0B;
        if ("toi-gian".equals(id)) return 0xFF64748B;
        if ("sau-cot".equals(id)) return 0xFF38BDF8;
        return 0xFFEF4444;
    }

    private void stylePill(TextView tv, boolean selected, boolean focused) {
        tv.setTag(selected);
        int accent = themeAccentColor();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999 * density);
        if (focused) {
            bg.setColor(accent);
            bg.setStroke(Math.max(1, Math.round(1.5f * density)), Color.WHITE);
            tv.setTextColor(Color.WHITE);
        } else if (selected) {
            bg.setColor((accent & 0x00FFFFFF) | 0x4D000000);
            bg.setStroke(Math.max(1, Math.round(1.2f * density)), accent);
            tv.setTextColor(Color.WHITE);
        } else {
            bg.setColor(0x24FFFFFF);
            bg.setStroke(Math.max(1, Math.round(1f * density)), 0x30FFFFFF);
            tv.setTextColor(0xB3FFFFFF);
        }
        tv.setBackground(bg);
    }

    private String focusedDockAppLabel = "";

    public void setFocusedDockLabel(String appLabel) {
        this.focusedDockAppLabel = appLabel != null ? appLabel.trim() : "";
        if (!rootContainer.hasFocus()) {
            updateHeaderLabel(null);
        }
    }

    private void updateHeaderLabel(VideoItem focusedVideo) {
        String topicLabel = getActiveTopicLabel(context);
        if (focusedVideo != null && !focusedVideo.title.isEmpty()) {
            String meta = focusedVideo.channel.isEmpty() ? "" : (" · " + focusedVideo.channel);
            titleHeaderView.setText("YOUTUBE: " + focusedVideo.title + meta);
            titleHeaderView.setTextColor(0xFFFFFFFF);
        } else if (!focusedDockAppLabel.isEmpty()) {
            titleHeaderView.setText(focusedDockAppLabel.toUpperCase(Locale.ROOT) + "  ·  ĐỀ XUẤT YOUTUBE (" + topicLabel + ")");
            titleHeaderView.setTextColor(0xFFE2E8F0);
        } else {
            titleHeaderView.setText("ĐỀ XUẤT YOUTUBE  ·  " + topicLabel);
            titleHeaderView.setTextColor(0xCCD8E2F0);
        }
        String activeId = getActiveTopicId(context);
        for (int i = 0; i < TOPICS.length && i < pillViews.size(); i++) {
            TextView pill = pillViews.get(i);
            stylePill(pill, TOPICS[i][0].equals(activeId), pill.hasFocus());
        }
    }

    private void renderVideoCards(List<VideoItem> videos) {
        cardsRow.removeAllViews();
        cardViews.clear();
        updateHeaderLabel(null);

        int cardW = Math.round(156 * density);
        int thumbH = Math.round(88 * density);
        int gap = Math.round(15 * density);
        final int accent = themeAccentColor();
        int maxCards = Math.min(5, videos.size());

        for (int i = 0; i < maxCards; i++) {
            final VideoItem item = videos.get(i);
            final int idx = i;

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) cardLp.leftMargin = gap;
            card.setLayoutParams(cardLp);

            // Thumbnail box (16:9)
            final FrameLayout thumbBox = new FrameLayout(context);
            final GradientDrawable thumbBorder = new GradientDrawable();
            thumbBorder.setColor(0xFF1E293B);
            thumbBorder.setCornerRadius(12 * density);
            thumbBorder.setStroke(Math.max(1, Math.round(1f * density)), 0x33FFFFFF);
            thumbBox.setBackground(thumbBorder);
            thumbBox.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * density);
                }
            });
            thumbBox.setClipToOutline(true);

            final ImageView img = new ImageView(context);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(0xFF0F172A);
            thumbBox.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Play badge in top-left
            TextView ytBadge = new TextView(context);
            ytBadge.setText("YOUTUBE");
            ytBadge.setTextColor(Color.WHITE);
            ytBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
            ytBadge.setTypeface(UiFont.medium(context), Typeface.BOLD);
            int bPadH = Math.round(5 * density);
            int bPadV = Math.round(2 * density);
            ytBadge.setPadding(bPadH, bPadV, bPadH, bPadV);
            GradientDrawable ytBadgeBg = new GradientDrawable();
            ytBadgeBg.setColor((accent & 0x00FFFFFF) | 0xD9000000);
            ytBadgeBg.setCornerRadius(6 * density);
            ytBadge.setBackground(ytBadgeBg);
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            badgeLp.leftMargin = Math.round(6 * density);
            badgeLp.topMargin = Math.round(6 * density);
            thumbBox.addView(ytBadge, badgeLp);

            // Duration badge in bottom-right
            if (!item.duration.isEmpty()) {
                TextView durTv = new TextView(context);
                durTv.setText(item.duration);
                durTv.setTextColor(Color.WHITE);
                durTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
                durTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                durTv.setPadding(bPadH, bPadV, bPadH, bPadV);
                GradientDrawable durBg = new GradientDrawable();
                durBg.setColor(0xCC090D16);
                durBg.setCornerRadius(5 * density);
                durTv.setBackground(durBg);
                FrameLayout.LayoutParams durLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.END);
                durLp.rightMargin = Math.round(6 * density);
                durLp.bottomMargin = Math.round(6 * density);
                thumbBox.addView(durTv, durLp);
            }

            card.addView(thumbBox, new LinearLayout.LayoutParams(cardW, thumbH));

            // Video title under thumbnail
            final TextView tvTitle = new TextView(context);
            tvTitle.setText(item.title);
            tvTitle.setTextColor(0xD9FFFFFF);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvTitle.setTypeface(UiFont.regular(context));
            tvTitle.setSingleLine(true);
            tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = Math.round(6 * density);
            card.addView(tvTitle, tLp);

            loadThumbnailAsync(item.id, img);

            card.setOnClickListener(v -> playVideo(context, item.id, item.title));
            card.setOnFocusChangeListener((v, focused) -> {
                v.animate().cancel();
                v.animate()
                        .scaleX(focused ? 1.05f : 1f)
                        .scaleY(focused ? 1.05f : 1f)
                        .setDuration(150)
                        .start();
                if (focused) {
                    thumbBorder.setStroke(Math.max(2, Math.round(2.5f * density)), accent);
                    tvTitle.setTextColor(Color.WHITE);
                    updateHeaderLabel(item);
                    if (navHost != null) navHost.onShelfItemFocused();
                } else {
                    thumbBorder.setStroke(Math.max(1, Math.round(1f * density)), 0x33FFFFFF);
                    tvTitle.setTextColor(0xD9FFFFFF);
                    if (!rootContainer.hasFocus()) {
                        updateHeaderLabel(null);
                    }
                }
            });

            card.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (idx > 0) cardViews.get(idx - 1).requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (idx + 1 < cardViews.size()) cardViews.get(idx + 1).requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (!pillViews.isEmpty()) {
                        String activeId = getActiveTopicId(context);
                        int pIdx = 0;
                        for (int p = 0; p < TOPICS.length; p++) {
                            if (TOPICS[p][0].equals(activeId)) { pIdx = p; break; }
                        }
                        pillViews.get(Math.min(pIdx, pillViews.size() - 1)).requestFocus();
                        return true;
                    }
                    if (navHost != null) navHost.onNavigateUpFromShelf();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (navHost != null) navHost.onNavigateDownFromShelf();
                    return true;
                }
                return false;
            });

            cardViews.add(card);
            cardsRow.addView(card);
        }
    }

    private void loadThumbnailAsync(final String videoId, final ImageView target) {
        if (videoId == null || videoId.isEmpty()) return;
        target.setTag(videoId);
        Bitmap cached = thumbCache.get(videoId);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        ioPool.execute(() -> {
            Bitmap bmp = downloadThumbnail(videoId);
            if (bmp != null) {
                thumbCache.put(videoId, bmp);
                mainHandler.post(() -> {
                    if (videoId.equals(target.getTag())) {
                        target.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    private static Bitmap downloadThumbnail(String videoId) {
        String[] urls = new String[]{
                "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg",
                "http://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg"
        };
        for (String u : urls) {
            HttpURLConnection conn = null;
            try {
                conn = openConn(u, 4500);
                if (conn.getResponseCode() == 200) {
                    byte[] bytes = readBytes(conn.getInputStream(), 256 * 1024);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                    if (b != null) return b;
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    public static void playVideo(Context ctx, String videoId, String title) {
        if (ctx == null || videoId == null || videoId.trim().isEmpty()) return;
        if (ParentalControlManager.isLocked()) {
            ParentalControlManager.showFullScreenLockOverlay();
            return;
        }
        String cleanId = videoId.trim();
        Uri watchUri = Uri.parse("https://www.youtube.com/watch?v=" + cleanId);
        try {
            Intent st = new Intent(Intent.ACTION_VIEW, watchUri)
                    .setComponent(new ComponentName("org.smarttube.stable",
                            "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(st);
            return;
        } catch (Throwable ignored) {}
        try {
            Intent stPkg = new Intent(Intent.ACTION_VIEW, watchUri)
                    .setPackage("org.smarttube.stable")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(stPkg);
            return;
        } catch (Throwable ignored) {}
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, watchUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) {
            Toast.makeText(ctx, "Không mở được SmartTube", Toast.LENGTH_SHORT).show();
        }
    }

    public static String getActiveTopicId(Context ctx) {
        if (ctx == null) return "kids";
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOPIC, "kids");
    }

    public static String getCustomQuery(Context ctx) {
        if (ctx == null) return "";
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_QUERY, "");
    }

    public static String getActiveTopicLabel(Context ctx) {
        String topicId = getActiveTopicId(ctx);
        String custom = getCustomQuery(ctx);
        if ("custom".equals(topicId) && !custom.isEmpty()) {
            return custom;
        }
        for (String[] t : TOPICS) {
            if (t[0].equals(topicId)) return t[1];
        }
        return TOPICS[0][1];
    }

    public static String getActiveSearchQuery(Context ctx) {
        String topicId = getActiveTopicId(ctx);
        String custom = getCustomQuery(ctx);
        if ("custom".equals(topicId) && !custom.isEmpty()) {
            return custom;
        }
        for (String[] t : TOPICS) {
            if (t[0].equals(topicId)) return t[2];
        }
        return TOPICS[0][2];
    }

    public static List<VideoItem> getCurrentVideos(Context ctx) {
        List<VideoItem> list = currentVideos;
        if (list == null || list.isEmpty()) {
            list = loadCachedOrSeedVideos(ctx);
            currentVideos = list;
        }
        return list;
    }

    public static void setTopicAndRefresh(Context ctx, String topicId, String customQuery) {
        if (ctx == null) return;
        String cleanTopic = (topicId == null || topicId.trim().isEmpty()) ? "kids" : topicId.trim();
        String cleanCustom = customQuery != null ? customQuery.trim() : "";
        if (!cleanCustom.isEmpty()) {
            cleanTopic = "custom";
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TOPIC, cleanTopic)
                .putString(KEY_CUSTOM_QUERY, cleanCustom)
                .apply();
        // Immediately show seed for that topic while live search runs
        if (!"custom".equals(cleanTopic)) {
            List<VideoItem> seed = seedVideosForTopic(cleanTopic);
            currentVideos = seed;
            YoutubeShelf inst = activeInstance;
            if (inst != null) {
                mainHandler.post(() -> inst.renderVideoCards(seed));
            }
        }
        refreshAsync(ctx, true);
    }

    public static void refreshAsync(final Context ctx, final boolean notifyCloud) {
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        final String query = getActiveSearchQuery(appCtx);
        final String topicId = getActiveTopicId(appCtx);
        ioPool.execute(() -> {
            List<VideoItem> fetched = fetchLiveVideos(query);
            if (fetched.isEmpty()) {
                fetched = seedVideosForTopic(topicId);
            } else {
                saveCacheJson(appCtx, fetched);
            }
            final List<VideoItem> finalVideos = fetched;
            currentVideos = finalVideos;
            mainHandler.post(() -> {
                YoutubeShelf inst = activeInstance;
                if (inst != null) {
                    inst.renderVideoCards(finalVideos);
                }
                if (notifyCloud) {
                    CloudTunnelClient.publishStateSoon();
                }
            });
        });
    }

    private static List<VideoItem> fetchLiveVideos(String query) {
        List<VideoItem> list = new ArrayList<>();
        // 1. Try Edge JSON feed from https://tv.tqd.me/yt-feed?q=...
        HttpURLConnection conn = null;
        try {
            String u = "https://tv.tqd.me/yt-feed?q=" + URLEncoder.encode(query, "UTF-8");
            conn = openConn(u, 5000);
            if (conn.getResponseCode() == 200) {
                String body = new String(readBytes(conn.getInputStream(), 128 * 1024), StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(body);
                JSONArray arr = obj.optJSONArray("videos");
                if (arr != null) {
                    for (int i = 0; i < arr.length() && list.size() < 10; i++) {
                        JSONObject v = arr.optJSONObject(i);
                        if (v == null) continue;
                        String id = v.optString("id", "");
                        String title = v.optString("title", "");
                        if (id.length() == 11 && !title.isEmpty()) {
                            list.add(new VideoItem(id, title, v.optString("channel", "YouTube"), v.optString("duration", "")));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        if (!list.isEmpty()) return list;

        // 2. Fallback: scrape YouTube results page directly on TV
        HttpURLConnection ytConn = null;
        try {
            String u = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8") + "&hl=vi&gl=VN";
            ytConn = openConn(u, 6000);
            ytConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
            if (ytConn.getResponseCode() == 200) {
                String html = new String(readBytes(ytConn.getInputStream(), 512 * 1024), StandardCharsets.UTF_8);
                Pattern p = Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([a-zA-Z0-9_-]{11})\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"\\}");
                Matcher m = p.matcher(html);
                List<String> seen = new ArrayList<>();
                while (m.find() && list.size() < 10) {
                    String id = m.group(1);
                    String title = unescapeJsonText(m.group(2));
                    if (!seen.contains(id) && !title.isEmpty()) {
                        seen.add(id);
                        list.add(new VideoItem(id, title, "YouTube", ""));
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (ytConn != null) ytConn.disconnect();
        }
        return list;
    }

    private static String unescapeJsonText(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\u0026", "&").replace("\\\\", "\\");
    }

    private static void saveCacheJson(Context ctx, List<VideoItem> videos) {
        try {
            JSONArray arr = new JSONArray();
            for (VideoItem v : videos) {
                JSONObject o = new JSONObject();
                o.put("id", v.id);
                o.put("title", v.title);
                o.put("channel", v.channel);
                o.put("duration", v.duration);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_CACHE_JSON, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private static List<VideoItem> loadCachedOrSeedVideos(Context ctx) {
        if (ctx != null) {
            try {
                String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CACHE_JSON, "");
                if (raw != null && !raw.isEmpty()) {
                    JSONArray arr = new JSONArray(raw);
                    List<VideoItem> cached = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) {
                            cached.add(new VideoItem(
                                    o.optString("id"),
                                    o.optString("title"),
                                    o.optString("channel"),
                                    o.optString("duration")));
                        }
                    }
                    if (!cached.isEmpty()) return cached;
                }
            } catch (Throwable ignored) {}
        }
        return seedVideosForTopic(getActiveTopicId(ctx));
    }

    public static List<VideoItem> seedVideosForTopic(String topicId) {
        List<VideoItem> list = new ArrayList<>();
        String t = topicId != null ? topicId.toLowerCase(Locale.ROOT) : "kids";
        if ("english".equals(t)) {
            list.add(new VideoItem("e_04ZrNroTo", "Wheels On The Bus | Rhymes for Kids", "Super Simple Songs", "03:50"));
            list.add(new VideoItem("XqZsoesa55w", "Baby Shark Dance | Sing and Dance!", "Pinkfong Kids", "02:17"));
            list.add(new VideoItem("71hqRT9U0wg", "If You're Happy | Kids Songs", "Super Simple Songs", "02:03"));
            list.add(new VideoItem("hq3yfQnllfQ", "Phonics Song with TWO Words - A For Apple", "ChuChu TV", "04:05"));
            list.add(new VideoItem("WRVsOCh907o", "Bath Song | CoComelon Nursery Rhymes", "CoComelon", "02:42"));
            list.add(new VideoItem("yCjJyiqpAuU", "Twinkle Twinkle Little Star", "Super Simple Songs", "02:34"));
        } else if ("science".equals(t)) {
            list.add(new VideoItem("libKVRa01L8", "Solar System 101 | Khám Phá Hệ Mặt Trời", "National Geographic", "04:10"));
            list.add(new VideoItem("R9tQXY2GNxI", "Khám Phá Thế Giới Động Vật Hoang Dã", "Animal Planet", "15:20"));
            list.add(new VideoItem("G5rN2N_yK3U", "Vì Sao Khủng Long Tuyệt Chủng? | Khoa Học Cho Bé", "VTV7 Kids", "10:15"));
            list.add(new VideoItem("k5hWWe-ts2s", "Đại Dương Sâu Thẳm Có Gì? | Khám Phá Tự Nhiên", "BBC Earth", "12:08"));
            list.add(new VideoItem("1fU5mK8kI8E", "Vòng Tuần Hoàn Của Nước Trong Tự Nhiên", "Khoa Học Vui", "05:45"));
            list.add(new VideoItem("F3c5_5bB2p8", "Khám Phá Các Hành Tinh Trong Vũ Trụ", "SciShow Kids", "06:30"));
        } else if ("music".equals(t)) {
            list.add(new VideoItem("j5-yKhDd64s", "Liên Khúc Nhạc Thiếu Nhi Vui Nhộn Nhất", "Nhạc Thiếu Nhi", "25:10"));
            list.add(new VideoItem("e_04ZrNroTo", "Bánh Xe Quay Nhanh | Nhạc Vận Động Cho Bé", "Kids Music VN", "03:50"));
            list.add(new VideoItem("XqZsoesa55w", "Cá Mập Con Vui Nhộn | Baby Shark", "Pinkfong", "02:17"));
            list.add(new VideoItem("71hqRT9U0wg", "Nào Mình Cùng Vỗ Tay | Bài Hát Cho Bé", "Super Simple", "02:03"));
            list.add(new VideoItem("yCjJyiqpAuU", "Ngôi Sao Nhỏ Lấp Lánh | Nhạc Ngủ Ngon", "Lullaby Kids", "02:34"));
            list.add(new VideoItem("hq3yfQnllfQ", "Bài Hát Bảng Chữ Cái Vui Nhộn", "ABC Kids", "04:05"));
        } else if ("family".equals(t)) {
            list.add(new VideoItem("LXb3EKWsInQ", "Khám Phá Thiên Nhiên 4K Ultra HD Tuyệt Đẹp", "Scenic Relaxation", "05:14"));
            list.add(new VideoItem("1La4QzGeaaQ", "Việt Nam Nhìn Từ Trên Cao 4K | Cảnh Đẹp Quê Hương", "Vietnam 4K", "18:40"));
            list.add(new VideoItem("libKVRa01L8", "Hành Trình Khám Phá Vũ Trụ Kỳ Thú", "NatGeo", "04:10"));
            list.add(new VideoItem("yCjJyiqpAuU", "Nhạc Piano Thư Giãn Buổi Tối Cho Gia Đình", "Relaxing Piano", "14:20"));
            list.add(new VideoItem("e_04ZrNroTo", "Giai Điệu Vui Tươi Cuối Tuần Cùng Bé", "Family TV", "03:50"));
            list.add(new VideoItem("XqZsoesa55w", "Sân Khấu Thiếu Nhi Gia Đình Vui Nhộn", "Kids Stage", "02:17"));
        } else {
            // Default: kids (Hoạt hình & Thiếu nhi)
            list.add(new VideoItem("e_04ZrNroTo", "Chiếc Xe Buýt Vui Nhộn | Hoạt Hình Thiếu Nhi", "Super Simple", "03:50"));
            list.add(new VideoItem("XqZsoesa55w", "Gia Đình Cá Mập Con | Hoạt Hình Vui Nhộn", "Pinkfong", "02:17"));
            list.add(new VideoItem("WRVsOCh907o", "Bé Tập Tự Giác Mỗi Ngày | CoComelon", "CoComelon", "02:42"));
            list.add(new VideoItem("71hqRT9U0wg", "Niềm Vui Mỗi Ngày Của Bé | Hoạt Hình Âm Nhạc", "Kids TV", "02:03"));
            list.add(new VideoItem("hq3yfQnllfQ", "Bé Học Chữ Cái & Từ Vựng Qua Hoạt Hình", "ChuChu TV", "04:05"));
            list.add(new VideoItem("yCjJyiqpAuU", "Câu Chuyện Ngôi Sao Nhỏ Trước Giờ Đi Ngủ", "Bedtime Kids", "02:34"));
        }
        return list;
    }

    private static HttpURLConnection openConn(String urlStr, int timeoutMs) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        if (conn instanceof HttpsURLConnection) {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, trustAll, new SecureRandom());
            ((HttpsURLConnection) conn).setSSLSocketFactory(new Weather.Tls12(sc.getSocketFactory()));
            ((HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
        }
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        return conn;
    }

    private static byte[] readBytes(InputStream in, int maxBytes) throws Exception {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() >= maxBytes) break;
            }
            return out.toByteArray();
        }
    }
}
