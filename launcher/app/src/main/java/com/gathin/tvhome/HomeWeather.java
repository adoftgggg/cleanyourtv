package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The weather now above the date ("9°  ·  Cloudy"), for the city picked in launcher settings. Data from Open-Meteo,
 * which needs no account or key: the only things sent are the name typed in the city search, then that city's
 * latitude and longitude. Fetched only while Home is on screen, at most every 30 minutes (5 after a failure); no
 * service, no alarm. Without a city nothing is fetched and the line stays hidden.
 */
final class HomeWeather {
    static final String CITY_KEY = "weather_city";
    private static final String LAT_KEY = "weather_lat", LON_KEY = "weather_lon";
    private static final String CACHE = "home_weather";
    private static final long REFRESH_MS = 30 * 60_000L, RETRY_MS = 5 * 60_000L;
    /** A reading older than this is not shown. */
    private static final long STALE_MS = 3 * 60 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;
    private final SharedPreferences settings;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean running;
    private boolean fetching;
    private final Runnable tick = this::refresh;

    HomeWeather(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx.getApplicationContext();
        this.main = main;
        this.onChange = onChange;
        settings = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        cache = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
    }

    /** A city from the search: its name, a detail line (region, country) and where it is. */
    static final class Place {
        final String name, detail;
        final double lat, lon;

        Place(String name, String detail, double lat, double lon) {
            this.name = name;
            this.detail = detail;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Home is on screen: show what is cached, fetch if it is old, and keep it fresh until {@link #stop}. */
    void start() {
        running = true;
        onChange.run();
        refresh();
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    static String city(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(CITY_KEY, "");
    }

    /** Saves the picked city; the next Home refresh fetches its weather. */
    static void setCity(Context ctx, Place p) {
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putString(CITY_KEY, p.name)
                .putLong(LAT_KEY, Double.doubleToLongBits(p.lat)).putLong(LON_KEY, Double.doubleToLongBits(p.lon)).apply();
        ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    static void clearCity(Context ctx) {
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().remove(CITY_KEY).remove(LAT_KEY)
                .remove(LON_KEY).apply();
        ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** "9°  ·  Nuageux", or empty when no city is set or the last reading is too old. */
    String line() {
        if (city(ctx).isEmpty()) return "";
        long at = cache.getLong("at_ms", 0);
        if (at <= 0 || System.currentTimeMillis() - at > STALE_MS) return "";
        int temp = (int) Math.round(Double.longBitsToDouble(cache.getLong("temp_c", 0)));
        return temp + "°  ·  " + ctx.getString(sky(cache.getInt("code", 3), cache.getBoolean("day", true)));
    }

    private void refresh() {
        main.removeCallbacks(tick);
        if (!running || city(ctx).isEmpty()) return;
        long now = System.currentTimeMillis();
        long next = cache.getLong("at_ms", 0) + REFRESH_MS - now;
        long retry = cache.getLong("failed_ms", 0) + RETRY_MS - now;
        long wait = Math.max(next, retry);
        if (wait > 0) {
            main.postDelayed(tick, wait);
            return;
        }
        if (fetching) return;
        fetching = true;
        double lat = Double.longBitsToDouble(settings.getLong(LAT_KEY, 0));
        double lon = Double.longBitsToDouble(settings.getLong(LON_KEY, 0));
        try {
            io.execute(() -> {
                boolean ok;
                try {
                    String where = String.format(Locale.ROOT, "latitude=%.4f&longitude=%.4f", lat, lon);
                    JSONObject current = get("https://api.open-meteo.com/v1/forecast?" + where
                            + "&current=temperature_2m,weather_code,is_day&timezone=auto").getJSONObject("current");
                    cache.edit().putLong("at_ms", System.currentTimeMillis())
                            .putLong("temp_c", Double.doubleToLongBits(current.getDouble("temperature_2m")))
                            .putInt("code", current.getInt("weather_code")).putBoolean("day", current.getInt("is_day") == 1)
                            .remove("failed_ms").commit();
                    ok = true;
                } catch (Exception e) {
                    cache.edit().putLong("failed_ms", System.currentTimeMillis()).commit();
                    ok = false;
                }
                boolean done = ok;
                main.post(() -> {
                    fetching = false;
                    if (done) onChange.run();
                    refresh();
                });
            });
        } catch (RejectedExecutionException shutDown) {
            fetching = false;
        }
    }

    /** Up to five cities matching what was typed, best first; blocks, so call it off the main thread. */
    static List<Place> search(String name) throws Exception {
        JSONObject reply = get("https://geocoding-api.open-meteo.com/v1/search?count=5&format=json&name="
                + URLEncoder.encode(name.trim(), "UTF-8") + "&language=" + Locale.getDefault().getLanguage());
        List<Place> out = new ArrayList<>();
        JSONArray results = reply.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            StringBuilder detail = new StringBuilder(r.optString("admin1"));
            String country = r.optString("country");
            if (!country.isEmpty()) detail.append(detail.length() > 0 ? ", " : "").append(country);
            out.add(new Place(r.getString("name"), detail.toString(), r.getDouble("latitude"), r.getDouble("longitude")));
        }
        return out;
    }

    private static JSONObject get(String url) throws IOException, org.json.JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    /** The words for a WMO weather code. */
    static int sky(int code, boolean day) {
        switch (code) {
            case 0: return day ? R.string.sky_sunny : R.string.sky_clear;
            case 1: case 2: return R.string.sky_partly_cloudy;
            case 3: return R.string.sky_cloudy;
            case 45: case 48: return R.string.sky_fog;
            case 51: case 53: case 55: case 56: case 57: return R.string.sky_drizzle;
            case 61: case 63: case 66: return R.string.sky_rain;
            case 65: case 67: return R.string.sky_heavy_rain;
            case 71: case 73: case 75: case 77: case 85: case 86: return R.string.sky_snow;
            case 80: case 81: case 82: return R.string.sky_showers;
            default: return code >= 95 ? R.string.sky_thunderstorm : R.string.sky_cloudy;
        }
    }
}
