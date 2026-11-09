package com.ansub.tvhome;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;

// The weather panel's data: now, and the three days after today, from Open-Meteo (free, no key, no
// account). Only the place is sent, as the latitude and longitude set with --es weather (or WEATHER=
// in install.sh).
final class Weather {
    static final long MAX_AGE_MS = 30 * 60 * 1000L; // a forecast older than this is fetched again
    static final long IP_MAX_AGE_MS = 24 * 60 * 60 * 1000L; // the IP-based place is looked up once a day
    static final int DAYS = 3;

    // Icons on the same 24-unit grid as LineIcon (ops in tenths), so they match the rest of the screen.
    static final int[] SUN = {3, 120, 120, 45, 0, 195, 120, 1, 215, 120, 0, 173, 173, 1, 187, 187,
            0, 120, 195, 1, 120, 215, 0, 67, 173, 1, 53, 187, 0, 45, 120, 1, 25, 120,
            0, 67, 67, 1, 53, 53, 0, 120, 45, 1, 120, 25, 0, 173, 67, 1, 187, 53};
    static final int[] CLOUD = {0, 75, 170, 1, 170, 170, 4, 170, 140, 30, 90, -180,
            4, 115, 110, 50, -30, -150, 4, 72, 140, 30, -100, -170, 2};
    // The cloud moved down and right, for sitting in front of a sun.
    static final int[] CLOUD_LOW = {0, 105, 200, 1, 200, 200, 4, 200, 170, 30, 90, -180,
            4, 145, 140, 50, -30, -150, 4, 102, 170, 30, -100, -170, 2};
    static final int[] SUN_SMALL = {3, 85, 85, 32,
            0, 127, 85, 1, 139, 85, 0, 85, 127, 1, 85, 139, 0, 43, 85, 1, 31, 85, 0, 85, 43, 1, 85, 31};
    static final int[] DROPS = {0, 80, 190, 1, 70, 220, 0, 130, 190, 1, 120, 220, 0, 180, 190, 1, 170, 220};
    static final int[] FLAKES = {3, 80, 205, 8, 3, 125, 215, 8, 3, 170, 205, 8};
    static final int[] BOLT = {0, 130, 180, 1, 105, 205, 1, 125, 205, 1, 100, 235};
    static final int[] FOG = {0, 45, 95, 1, 195, 95, 0, 30, 140, 1, 210, 140, 0, 45, 185, 1, 195, 185};

    // Colours, as ARGB. The sun is yellow, the cloud light grey, and rain, snow, lightning and fog each
    // have their own, drawn over the cloud.
    static final int SUN_COLOR = 0xFFFFC94D, CLOUD_COLOR = 0xFFE9EEF4, RAIN_COLOR = 0xFF5FB8FF,
            SNOW_COLOR = 0xFFD4F0FF, BOLT_COLOR = 0xFFFFD84D, FOG_COLOR = 0xFFAAB6C4;

    // What the panel shows, read from the forecast JSON.
    static final class Forecast {
        int nowTemp, nowCode;
        int offset = Integer.MIN_VALUE; // the place's UTC offset in seconds (MIN_VALUE: not saved)
        int sunrise = -1, sunset = -1;  // today, in minutes after midnight there (-1: not saved)
        final String[] day = new String[DAYS];
        final int[] code = new int[DAYS], max = new int[DAYS], min = new int[DAYS];
    }

    // "lat,lon" as the forecast needs it, or null if it isn't two numbers in range.
    static String location(String raw) {
        try {
            String[] p = raw.trim().split("[,\\s]+");
            if (p.length != 2) return null;
            double lat = Double.parseDouble(p[0]), lon = Double.parseDouble(p[1]);
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) return null; // also rejects NaN
            return String.format(Locale.US, "%.4f,%.4f", lat, lon);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // The forecast JSON for a place from location(), in Celsius. Throws if the network or the server fails.
    static String fetch(String place) throws IOException {
        String[] p = place.split(",");
        return get("https://api.open-meteo.com/v1/forecast?latitude=" + p[0] + "&longitude=" + p[1]
                + "&current=temperature_2m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset"
                + "&forecast_days=" + (DAYS + 1) + "&timezone=auto");
    }

    // Rough place from the IP address, as location(), or null if the lookup fails. ipapi.co sees the
    // IP address; the forecast request then only ever gets the rounded-off place.
    static String locateByIp() {
        try {
            JSONObject o = new JSONObject(get("https://ipapi.co/json/"));
            return location(o.getDouble("latitude") + "," + o.getDouble("longitude"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "tv-detox");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // Today's "2026-11-09T06:12" in a daily list, as minutes after midnight, or -1.
    private static int minutes(JSONArray a) {
        try {
            String t = a.getString(0);
            return Integer.parseInt(t.substring(11, 13)) * 60 + Integer.parseInt(t.substring(14, 16));
        } catch (Exception e) {
            return -1;
        }
    }

    // The part of the day at the place: morning, noon and afternoon share the daylight (30 / 35 / 35
    // percent), and night is the rest. Without a saved sunrise and sunset: 6:00 to 19:00.
    static String slot(Forecast f, long nowMs) {
        long off = f.offset != Integer.MIN_VALUE ? f.offset : java.util.TimeZone.getDefault().getOffset(nowMs) / 1000;
        int now = (int) ((((nowMs / 1000 + off) % 86400) + 86400) % 86400 / 60);
        int rise = f.sunrise, set = f.sunset;
        if (rise < 0 || set <= rise) { rise = 6 * 60; set = 19 * 60; }
        if (now < rise || now >= set) return "night";
        int at = (now - rise) * 100 / (set - rise);
        return at < 30 ? "morning" : at < 65 ? "noon" : "afternoon";
    }

    // The weather code as one of the five kinds of photo: clear, cloudy, fog, snow or rain.
    static String bucket(int code) {
        if (code <= 2) return "clear";
        if (code == 45 || code == 48) return "fog";
        if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) return "snow";
        if (code >= 51) return "rain"; // drizzle, rain, showers, thunderstorms
        return "cloudy";
    }

    static Forecast parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONObject cur = o.getJSONObject("current");
        JSONObject daily = o.getJSONObject("daily");
        JSONArray times = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray tMax = daily.getJSONArray("temperature_2m_max");
        JSONArray tMin = daily.getJSONArray("temperature_2m_min");
        Forecast f = new Forecast();
        f.nowTemp = (int) Math.round(cur.getDouble("temperature_2m"));
        f.nowCode = cur.getInt("weather_code");
        // Older saved forecasts have no sunrise or sunset: the wallpaper then uses fixed hours.
        f.offset = o.optInt("utc_offset_seconds", Integer.MIN_VALUE);
        f.sunrise = minutes(daily.optJSONArray("sunrise"));
        f.sunset = minutes(daily.optJSONArray("sunset"));
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.getDefault());
        for (int i = 0; i < DAYS; i++) {
            int k = i + 1; // index 0 is today; the panel shows the days after it
            f.day[i] = out.format(in.parse(times.getString(k)));
            f.code[i] = codes.getInt(k);
            f.max[i] = (int) Math.round(tMax.getDouble(k));
            f.min[i] = (int) Math.round(tMin.getDouble(k));
        }
        return f;
    }

    // Open-Meteo's WMO weather codes, as a coloured icon. Layers are drawn back to front.
    static Drawable icon(int code) {
        if (code == 0) return layer(SUN, SUN_COLOR);
        if (code <= 2) return layers(SUN_SMALL, SUN_COLOR, CLOUD_LOW, CLOUD_COLOR);
        if (code == 45 || code == 48) return layer(FOG, FOG_COLOR);
        if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) return layers(CLOUD, CLOUD_COLOR, FLAKES, SNOW_COLOR);
        if (code >= 95) return layers(CLOUD, CLOUD_COLOR, BOLT, BOLT_COLOR);
        if (code >= 51) return layers(CLOUD, CLOUD_COLOR, DROPS, RAIN_COLOR); // drizzle, rain, showers
        return layer(CLOUD, CLOUD_COLOR);
    }

    // The same codes in words (English; Strings.t translates them).
    static String condition(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 45 || code == 48) return "Fog";
        if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) return "Snow";
        if (code >= 95) return "Thunderstorm";
        if (code >= 51) return "Rain";
        return "Cloudy";
    }

    // Shadowed, since the panel has no background and the icons sit straight on the wallpaper.
    private static Drawable layer(int[] ops, int color) {
        return new LineIcon(ops, color).withShadow();
    }

    private static Drawable layers(int[] a, int ca, int[] b, int cb) {
        return new LayerDrawable(new Drawable[] {new LineIcon(a, ca).withShadow(), new LineIcon(b, cb).withShadow()});
    }
}
