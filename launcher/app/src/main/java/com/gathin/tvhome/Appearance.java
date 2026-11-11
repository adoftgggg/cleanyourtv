package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Shared light/dark palette for every launcher-owned screen. */
final class Appearance {
    static final String KEY = "appearance_mode";
    static final String LIGHT = "light";
    static final String DARK = "dark";

    static final class Palette {
        final int background, surface, surfaceRaised, text, muted, border, focus, selected;
        Palette(boolean light) {
            background = Color.parseColor(light ? "#F3F5F8" : "#101419");
            surface = Color.parseColor(light ? "#FFFFFF" : "#1B222C");
            surfaceRaised = Color.parseColor(light ? "#E8EDF3" : "#252E3A");
            text = Color.parseColor(light ? "#17202B" : "#F4F7FB");
            muted = Color.parseColor(light ? "#596675" : "#AAB6C4");
            border = Color.parseColor(light ? "#D5DDE7" : "#354252");
            focus = Color.parseColor(light ? "#D7E9FF" : "#34506A");
            selected = Color.parseColor(light ? "#DCEBFA" : "#293E52");
        }
    }

    private Appearance() {}

    static boolean isLight(Context context) {
        return LIGHT.equals(context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(KEY, DARK));
    }

    static Palette palette(Context context) { return new Palette(isLight(context)); }

    static void apply(Activity activity) {
        Palette p = palette(activity);
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(p.background));
        View decor = activity.getWindow().getDecorView();
        styleTree(decor, p);
    }

    static void styleTree(View view, Palette p) {
        Drawable bg = view.getBackground();
        if (bg instanceof ColorDrawable) {
            int color = ((ColorDrawable) bg).getColor();
            if (near(color, 0xFF101419) || near(color, 0xFF000000)) {
                view.setBackgroundColor(p.background);
            } else if (near(color, 0xFF242B34) || near(color, 0xFF1B222C)) {
                view.setBackgroundColor(p.surfaceRaised);
            }
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            int color = text.getCurrentTextColor();
            if (near(color, Color.WHITE) || near(color, 0xFFF4F7FB)) text.setTextColor(p.text);
            else if (near(color, 0xFFACB8C1) || near(color, 0xFFAAB6C4)) text.setTextColor(p.muted);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) styleTree(group.getChildAt(i), p);
        }
    }

    private static boolean near(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) < 8
                && Math.abs(Color.green(a) - Color.green(b)) < 8
                && Math.abs(Color.blue(a) - Color.blue(b)) < 8;
    }
}
