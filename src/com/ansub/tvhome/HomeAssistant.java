package com.ansub.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Home Assistant tiles: air conditioners, cameras and automations, talked to over Home Assistant's
// own REST API with a long-lived access token. Nothing else is needed on the TV or on the server.
//
// install.sh sends homeassistant.json (see homeassistant.example.json) and the TV's address. One
// file describes every TV: under "tvs", each TV has its own climate, cameras and automations, and
// the TV keeps only its own part (picked by name or address), with the server and the token.
final class HomeAssistant {
    static final String CLIMATE = "climate", CAMERAS = "cameras", AUTOMATIONS = "automations";

    // One thing on the home screen: its Home Assistant entity and the name shown on its tile.
    static final class Item {
        final String section, entity;
        String name; // from the file, else Home Assistant's friendly name once it is known
        final boolean named;
        // Cameras only: sensors that pop the camera up when they turn on, and for how long (CameraWatch).
        final List<String> popup = new ArrayList<>();
        int popupSeconds = 30;

        Item(String section, String entity, String name) {
            this.section = section;
            this.entity = entity;
            this.named = name != null && !name.isEmpty();
            this.name = named ? name : pretty(entity);
        }

        String key() { return "ha:" + section + ":" + entity; }
    }

    final String server, token;
    final List<Item> items = new ArrayList<>();

    private HomeAssistant(String server, String token) {
        this.server = server.replaceAll("/+$", "");
        this.token = token;
    }

    // This TV's part of the whole file, as saved on the TV: {"server", "token", "climate": [...],
    // "cameras": [...], "automations": [...]}. tv is the name install.sh was given (HA_TV) or the TV's
    // address; it matches a key under "tvs" or one of that TV's "address" entries. A TV with no part of
    // its own gets "default", if there is one. Null if the file has nothing for this TV.
    static JSONObject pick(String file, String tv) throws Exception {
        JSONObject all = new JSONObject(file);
        JSONObject tvs = all.optJSONObject("tvs");
        JSONObject mine = null;
        if (tvs != null && tv != null) {
            String want = tv.trim().replaceAll(":5555$", "");
            for (java.util.Iterator<String> it = tvs.keys(); it.hasNext() && mine == null; ) {
                String name = it.next();
                JSONObject t = tvs.optJSONObject(name);
                if (t == null) continue;
                if (name.equalsIgnoreCase(want)) mine = t;
                for (String a : strings(t.opt("address"))) if (a.equals(want)) mine = t;
            }
            if (mine == null) mine = tvs.optJSONObject("default");
        }
        if (mine == null) return null;
        JSONObject out = new JSONObject();
        // A TV can have its own server or token; otherwise it gets the file's.
        out.put("server", mine.optString("server", all.optString("server", "")));
        out.put("token", mine.optString("token", all.optString("token", "")));
        for (String s : new String[] {CLIMATE, CAMERAS, AUTOMATIONS})
            if (mine.has(s)) out.put(s, mine.get(s));
        if (out.getString("server").isEmpty() || out.getString("token").isEmpty()) return null;
        return out;
    }

    // What pick() saved, ready to use; null if it is missing or broken.
    static HomeAssistant load(String saved) {
        if (saved == null) return null;
        try {
            JSONObject o = new JSONObject(saved);
            HomeAssistant ha = new HomeAssistant(o.getString("server"), o.getString("token"));
            for (String s : new String[] {CLIMATE, CAMERAS, AUTOMATIONS}) {
                JSONArray a = o.optJSONArray(s);
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    Object e = a.get(i);
                    // An entry is "climate.x", or {"entity": "climate.x", "name": "Living room"}. A camera
                    // can add "popup": a sensor (or a list) that pops it up, and "popup_seconds".
                    if (e instanceof JSONObject) {
                        JSONObject j = (JSONObject) e;
                        String id = j.optString("entity", "");
                        if (id.isEmpty()) continue;
                        Item it = new Item(s, id, j.optString("name", null));
                        for (String p : strings(j.opt("popup"))) if (!p.isEmpty()) it.popup.add(p);
                        it.popupSeconds = Math.max(5, j.optInt("popup_seconds", 30));
                        ha.items.add(it);
                    } else if (e instanceof String && !((String) e).isEmpty()) {
                        ha.items.add(new Item(s, (String) e, null));
                    }
                }
            }
            return ha.items.isEmpty() ? null : ha;
        } catch (Exception e) {
            return null;
        }
    }

    Item item(String key) {
        for (Item i : items) if (i.key().equals(key)) return i;
        return null;
    }

    List<Item> items(String section) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) if (i.section.equals(section)) out.add(i);
        return out;
    }

    // ---- REST ----

    JSONObject state(String entity) throws Exception {
        return new JSONObject(new String(request("GET", "/api/states/" + entity, null), "UTF-8"));
    }

    void call(String domain, String service, JSONObject data) throws Exception {
        request("POST", "/api/services/" + domain + "/" + service, data.toString());
    }

    // What OK on an automation tile does, by the kind of entity: automations run (their conditions are
    // skipped, as Home Assistant's own "Run" does), scripts and scenes start, buttons are pressed, and
    // anything else (a light, a switch, a helper) is toggled.
    void run(String entity) throws Exception {
        String domain = entity.substring(0, Math.max(0, entity.indexOf('.')));
        JSONObject data = new JSONObject().put("entity_id", entity);
        switch (domain) {
            case "automation": call("automation", "trigger", data); break;
            case "script": call("script", "turn_on", data); break;
            case "scene": call("scene", "turn_on", data); break;
            case "button": case "input_button": call(domain, "press", data); break;
            default: call("homeassistant", "toggle", data);
        }
    }

    private byte[] request(String method, String path, String body) throws IOException {
        HttpURLConnection c = open(path);
        try {
            c.setRequestMethod(method);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream o = c.getOutputStream();
                o.write(body.getBytes("UTF-8"));
                o.close();
            }
            int code = c.getResponseCode();
            if (code != 200 && code != 201) throw new IOException("HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    HttpURLConnection open(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("User-Agent", "tv-detox");
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ---- Cameras ----

    interface Frames { boolean frame(byte[] jpeg, int length); } // false stops the stream

    // A camera's live picture as Home Assistant serves it to its own dashboards: MJPEG, one JPEG after
    // another (an RTSP camera is turned into this by Home Assistant). Each frame is cut out by its JPEG
    // start and end markers, so it works whatever headers the parts carry. Returns when frames() says
    // stop or the connection drops; c is handed out first so another thread can close it.
    void stream(String entity, java.util.concurrent.atomic.AtomicReference<HttpURLConnection> c, Frames frames)
            throws IOException {
        HttpURLConnection conn = open("/api/camera_proxy_stream/" + entity);
        conn.setReadTimeout(20000);
        c.set(conn);
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[1 << 20];
            int len = 0, start = -1;
            while (true) {
                if (len == buf.length) { // a frame bigger than the buffer: grow it
                    if (start > 0) { System.arraycopy(buf, start, buf, 0, len - start); len -= start; start = 0; }
                    else buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                }
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) return;
                int from = Math.max(0, len - 1);
                len += n;
                for (int i = from; i + 1 < len; i++) {
                    if ((buf[i] & 0xFF) != 0xFF) continue;
                    int m = buf[i + 1] & 0xFF;
                    if (start < 0 && m == 0xD8) start = i;
                    else if (start >= 0 && m == 0xD9) {
                        int end = i + 2;
                        byte[] jpeg = java.util.Arrays.copyOfRange(buf, start, end);
                        if (!frames.frame(jpeg, jpeg.length)) return;
                        System.arraycopy(buf, end, buf, 0, len - end);
                        len -= end;
                        i = -1;
                        start = -1;
                    }
                }
                if (start < 0 && len > 1) { buf[0] = buf[len - 1]; len = 1; } // nothing started: keep the last byte only
            }
        } finally {
            conn.disconnect();
        }
    }

    // A frame decoded at about the screen's width or less, so a 2K camera doesn't cost a 2K bitmap.
    static Bitmap decode(byte[] jpeg, int length, int maxWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(jpeg, 0, length, o);
    }

    // ---- Words ----

    // "climate.aire_acondicionado_living" as "Aire acondicionado living", until the real name is known.
    static String pretty(String entity) {
        String s = entity.substring(entity.indexOf('.') + 1).replace('_', ' ').trim();
        return s.isEmpty() ? entity : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    // Home Assistant's HVAC modes in words (English; Strings.t translates them).
    static String mode(String m) {
        switch (m) {
            case "off": return "Off";
            case "cool": return "Cool";
            case "heat": return "Heat";
            case "dry": return "Dry";
            case "fan_only": return "Fan";
            case "heat_cool": return "Heat/Cool";
            case "auto": return "Auto";
            default: return pretty("x." + m);
        }
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof String) out.add((String) o);
        else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        }
        return out;
    }
}
