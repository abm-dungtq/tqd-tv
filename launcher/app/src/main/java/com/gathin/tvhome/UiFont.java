package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Static Lexend Vietnamese font loader compatible with Android 6.0 (SDK 23).
 * Avoids Resources.getFont(int) (API 26+) and XML @font/ inflation (API 26+) by loading
 * static Lexend weights directly from assets/fonts/ with in-memory caching.
 */
public final class UiFont {
    private static volatile Typeface tfRegular;
    private static volatile Typeface tfMedium;
    private static volatile Typeface tfLight;
    private static volatile Typeface tfExtraLight;

    private UiFont() {}

    private static synchronized void ensureLoaded(Context context) {
        if (tfRegular != null) return;
        Context app = context.getApplicationContext();
        try {
            tfRegular = Typeface.createFromAsset(app.getAssets(), "fonts/lexend.ttf");
            tfMedium = Typeface.createFromAsset(app.getAssets(), "fonts/lexend_medium.ttf");
            tfLight = Typeface.createFromAsset(app.getAssets(), "fonts/lexend_light.ttf");
            tfExtraLight = Typeface.createFromAsset(app.getAssets(), "fonts/lexend_extralight.ttf");
        } catch (Throwable ignored) {
            tfRegular = Typeface.SANS_SERIF;
            tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
            tfExtraLight = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        }
    }

    public static Typeface regular(Context context) {
        ensureLoaded(context);
        return tfRegular != null ? tfRegular : Typeface.SANS_SERIF;
    }

    public static Typeface medium(Context context) {
        ensureLoaded(context);
        return tfMedium != null ? tfMedium : regular(context);
    }

    public static Typeface light(Context context) {
        ensureLoaded(context);
        return tfLight != null ? tfLight : regular(context);
    }

    public static Typeface extraLight(Context context) {
        ensureLoaded(context);
        return tfExtraLight != null ? tfExtraLight : light(context);
    }

    static Typeface get(Context context, int fontString) {
        ensureLoaded(context);
        if (fontString == R.string.font_medium) return medium(context);
        if (fontString == R.string.font_light) return light(context);
        if (fontString == R.string.font_extralight) return extraLight(context);
        return regular(context);
    }

    /** Recursively applies static Lexend Vietnamese typography to a view hierarchy on Android 6.0+. */
    public static void applyLexendTree(View view) {
        if (view == null) return;
        Context ctx = view.getContext();
        ensureLoaded(ctx);
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            int id = tv.getId();
            if (id == R.id.clock) {
                tv.setTypeface(extraLight(ctx));
            } else if (id == R.id.date || id == R.id.lunarDate || id == R.id.weather || id == R.id.stats || id == R.id.heroName || id == R.id.heroMeta) {
                tv.setTypeface(light(ctx));
            } else if (id == R.id.greeting || id == R.id.raceKicker || id == R.id.raceSessions) {
                tv.setTypeface(medium(ctx));
            } else {
                Typeface cur = tv.getTypeface();
                if (cur != null && cur.getStyle() == Typeface.MONOSPACE.getStyle() && "corner_qr_url".equals(tv.getTag())) {
                    // keep monospace for IP/URL badge
                } else if (cur != null && cur.isBold()) {
                    tv.setTypeface(medium(ctx), Typeface.BOLD);
                } else {
                    tv.setTypeface(regular(ctx));
                }
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                applyLexendTree(vg.getChildAt(i));
            }
        }
    }
}
