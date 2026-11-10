package com.ansub.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// What opens from a Home Assistant tile: the air conditioner's panel and the full-screen camera.
// Both use the home screen's own glass look and close with Back.
final class HomeAssistantViews {
    private static final int RADIUS = 26;
    static volatile boolean fullScreenOpen; // a camera is full screen: CameraWatch doesn't pop one up over it
    // QUIET_MS: after a new temperature is sent, the unit's own reading is ignored this long, since
    // it can still report the old one for a few seconds.
    private static final long CLIMATE_POLL_MS = 5000, SEND_AFTER_MS = 700, QUIET_MS = 8000;

    private final Activity a;
    private Dialog shown; // the panel or camera that's open, if any
    private final float scale;
    private final Typeface regular, semibold;

    HomeAssistantViews(Activity a, float scale, Typeface regular, Typeface semibold) {
        this.a = a;
        this.scale = scale;
        this.regular = regular;
        this.semibold = semibold;
    }

    // ---- Air conditioner ----

    // The panel: the name, the target temperature between - and +, the room temperature, and one
    // button per mode the unit has. A new temperature is sent a moment after the last press, so
    // holding + doesn't send every step. The panel reads the unit again every few seconds.
    void climate(final HomeAssistant ha, final HomeAssistant.Item item) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(px(48), px(40), px(48), px(40));
        box.setBackground(glass(0xF0231D1F));

        TextView title = text(px(36), Color.WHITE, semibold);
        title.setText(item.name);
        title.setGravity(Gravity.CENTER);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final TextView minus = button("−", px(56));
        final TextView target = text(px(120), Color.WHITE, semibold);
        target.setGravity(Gravity.CENTER);
        target.setText("--°");
        final TextView plus = button("+", px(56));
        row.addView(minus, new LinearLayout.LayoutParams(px(110), px(110)));
        row.addView(target, new LinearLayout.LayoutParams(px(360), -2));
        row.addView(plus, new LinearLayout.LayoutParams(px(110), px(110)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = px(16);
        box.addView(row, rp);

        final TextView room = text(px(26), 0xB3FFFFFF, regular);
        room.setGravity(Gravity.CENTER);
        box.addView(room, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout modes = new LinearLayout(a);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = px(32);
        box.addView(modes, mp);

        final TextView status = text(px(22), 0xB3FFFFFF, regular);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = px(20);
        box.addView(status, sp);

        // What the panel knows: the unit's state, and a temperature picked here and not yet sent.
        final double[] t = {Double.NaN}, range = {16, 30, 0.5};
        final String[] mode = {null};
        final List<String> shownModes = new ArrayList<>();
        final AtomicBoolean open = new AtomicBoolean(true);
        final View host = a.getWindow().getDecorView();

        final Runnable[] read = new Runnable[1];
        final boolean[] pending = {false}; // a picked temperature waits to be sent
        final long[] quietUntil = {0};
        final Runnable send = () -> {
            pending[0] = false;
            quietUntil[0] = android.os.SystemClock.uptimeMillis() + QUIET_MS;
            final double want = t[0];
            new Thread(() -> {
                try {
                    ha.call("climate", "set_temperature", new JSONObject()
                            .put("entity_id", item.entity).put("temperature", want));
                    a.runOnUiThread(() -> status.setText(""));
                } catch (Exception e) {
                    a.runOnUiThread(() -> status.setText(Strings.t("Couldn't reach Home Assistant")));
                }
            }).start();
        };
        final Runnable showTarget = () -> target.setText(Double.isNaN(t[0]) ? "--°" : number(t[0]) + "°");
        View.OnClickListener step = v -> {
            double s = range[2];
            double base = Double.isNaN(t[0]) ? Math.round((range[0] + range[1]) / 2) : t[0];
            t[0] = Math.max(range[0], Math.min(range[1], base + (v == plus ? s : -s)));
            showTarget.run();
            pending[0] = true;
            host.removeCallbacks(send);
            host.postDelayed(send, SEND_AFTER_MS);
        };
        minus.setOnClickListener(step);
        plus.setOnClickListener(step);

        read[0] = new Runnable() {
            @Override public void run() {
                if (!open.get()) return;
                new Thread(() -> {
                    try {
                        final JSONObject s = ha.state(item.entity);
                        a.runOnUiThread(() -> {
                            if (!open.get()) return;
                            JSONObject at = s.optJSONObject("attributes");
                            if (at == null) at = new JSONObject();
                            if (!item.named && at.has("friendly_name")) title.setText(at.optString("friendly_name"));
                            mode[0] = s.optString("state");
                            range[0] = at.optDouble("min_temp", 16);
                            range[1] = at.optDouble("max_temp", 30);
                            // Some units report a top far above what they can do (88 for a Celsius split): keep it sane.
                            if (range[1] - range[0] > 20) range[1] = range[0] + 16;
                            range[2] = at.optDouble("target_temp_step", 0.5);
                            if (!(range[2] > 0)) range[2] = 0.5;
                            // A target read back as Fahrenheit (see target()) comes from a unit that only
                            // takes whole degrees: 24.5 sent comes back as 24.
                            if (at.optDouble("temperature", range[0]) < range[0]) range[2] = Math.max(1, range[2]);
                            // Don't overwrite a temperature that's about to be sent.
                            double want = target(at.optDouble("temperature", Double.NaN), range[0], range[1], range[2]);
                            if (!pending[0] && android.os.SystemClock.uptimeMillis() >= quietUntil[0]) t[0] = want;
                            showTarget.run();
                            double now = at.optDouble("current_temperature", Double.NaN);
                            room.setText(Double.isNaN(now) ? "" : Strings.t("Room") + " " + number(now) + "°");
                            fillModes(ha, item, modes, shownModes, at.optJSONArray("hvac_modes"), mode[0], status, read[0]);
                            status.setText("unavailable".equals(mode[0]) ? Strings.t("Couldn't reach Home Assistant") : "");
                        });
                    } catch (Exception e) {
                        a.runOnUiThread(() -> status.setText(Strings.t("Couldn't reach Home Assistant")));
                    }
                }).start();
                host.postDelayed(this, CLIMATE_POLL_MS);
            }
        };

        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setLayout(px(900), -2);
        d.setOnDismissListener(x -> {
            open.set(false);
            host.removeCallbacks(read[0]);
            // A temperature picked just before Back is still sent.
            host.removeCallbacks(send);
            if (pending[0]) send.run();
        });
        show(d);
        plus.requestFocus();
        read[0].run();
    }

    // One button per mode, the unit's own list, with the current one lit. Rebuilt only when the list
    // changes, so focus stays put while the panel refreshes.
    private void fillModes(final HomeAssistant ha, final HomeAssistant.Item item, LinearLayout modes,
                           List<String> shown, JSONArray list, String current, final TextView status,
                           final Runnable reread) {
        List<String> want = new ArrayList<>();
        if (list != null) for (int i = 0; i < list.length(); i++) want.add(list.optString(i));
        if (!want.equals(shown)) {
            modes.removeAllViews();
            shown.clear();
            shown.addAll(want);
            for (final String m : want) {
                TextView b = button(Strings.t(HomeAssistant.mode(m)), px(24));
                b.setTag(m);
                b.setPadding(px(22), 0, px(22), 0);
                b.setOnClickListener(v -> new Thread(() -> {
                    try {
                        ha.call("climate", "set_hvac_mode", new JSONObject()
                                .put("entity_id", item.entity).put("hvac_mode", m));
                        a.getWindow().getDecorView().postDelayed(reread, 800);
                    } catch (Exception e) {
                        a.runOnUiThread(() -> status.setText(Strings.t("Couldn't reach Home Assistant")));
                    }
                }).start());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(64));
                lp.leftMargin = px(6);
                lp.rightMargin = px(6);
                modes.addView(b, lp);
            }
        }
        for (int i = 0; i < modes.getChildCount(); i++) {
            TextView b = (TextView) modes.getChildAt(i);
            boolean on = b.getTag().equals(current);
            b.setTextColor(on ? 0xFF1B1518 : (b.hasFocus() ? Color.WHITE : 0xD9FFFFFF));
            b.setSelected(on);
            ((GradientDrawable) b.getBackground()).setColor(on ? 0xF2FFFFFF : b.hasFocus() ? 0x38FFFFFF : 0x13FFFFFF);
        }
    }

    // ---- Cameras ----

    // A camera full screen, live. Left and right go to the other cameras of this TV; Back closes it.
    void camera(final HomeAssistant ha, final List<HomeAssistant.Item> cams, int first) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout box = new FrameLayout(a);
        box.setBackgroundColor(Color.BLACK);
        final ImageView picture = new ImageView(a);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        final TextView label = text(px(28), Color.WHITE, semibold);
        label.setShadowLayer(px(10), 0, px(2), 0xCC000000);
        // Bottom left: cameras usually print their own date and time at the top.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = px(48);
        lp.bottomMargin = px(36);
        box.addView(label, lp);
        final TextView note = text(px(26), 0xD9FFFFFF, regular);
        note.setShadowLayer(px(10), 0, px(2), 0xCC000000);
        box.addView(note, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        d.setContentView(box);
        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final int[] at = {first};
        final AtomicReference<Thread> running = new AtomicReference<>();
        final AtomicReference<HttpURLConnection> conn = new AtomicReference<>();
        final int maxWidth = a.getResources().getDisplayMetrics().widthPixels;

        final Runnable[] start = new Runnable[1];
        start[0] = () -> {
            stop(running, conn);
            final HomeAssistant.Item cam = cams.get(at[0]);
            label.setText(cam.name + (cams.size() > 1 ? "   ‹ " + (at[0] + 1) + "/" + cams.size() + " ›" : ""));
            note.setText(Strings.t("Connecting…"));
            picture.setImageDrawable(null);
            // Only one frame waits for the screen at a time: a slow TV drops frames instead of falling behind.
            final AtomicBoolean drawing = new AtomicBoolean(false);
            Thread th = new Thread(() -> {
                Thread me = Thread.currentThread();
                while (running.get() == me) {
                    try {
                        ha.stream(cam.entity, conn, (jpeg, n) -> {
                            if (running.get() != me) return false;
                            if (drawing.get()) return true;
                            final Bitmap b = HomeAssistant.decode(jpeg, n, maxWidth);
                            if (b == null) return true;
                            drawing.set(true);
                            a.runOnUiThread(() -> {
                                if (running.get() == me) {
                                    picture.setImageBitmap(b);
                                    note.setText("");
                                }
                                drawing.set(false);
                            });
                            return true;
                        });
                    } catch (Exception e) {
                        if (running.get() != me) return;
                        a.runOnUiThread(() -> note.setText(Strings.t("Couldn't reach Home Assistant")));
                    }
                    // The stream ended or failed: try again shortly, while the camera is still open.
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                }
            });
            running.set(th);
            th.start();
        };
        d.setOnKeyListener((dialog, code, ev) -> {
            if (cams.size() < 2 || ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) at[0] = (at[0] + 1) % cams.size();
            else if (code == KeyEvent.KEYCODE_DPAD_LEFT) at[0] = (at[0] + cams.size() - 1) % cams.size();
            else return false;
            start[0].run();
            return true;
        });
        d.setOnDismissListener(x -> {
            fullScreenOpen = false;
            stop(running, conn);
        });
        fullScreenOpen = true;
        show(d);
        start[0].run();
    }

    private static void stop(AtomicReference<Thread> running, AtomicReference<HttpURLConnection> conn) {
        Thread old = running.getAndSet(null);
        final HttpURLConnection c = conn.getAndSet(null);
        if (old != null) old.interrupt();
        // Closing the connection ends the blocked read; off the main thread, since it can touch the network.
        if (c != null) new Thread(c::disconnect).start();
    }

    private void show(Dialog d) {
        close();
        shown = d;
        d.show();
    }

    // Home was pressed: the home screen comes back as it is, without a panel or camera on top.
    void close() {
        if (shown != null && shown.isShowing()) shown.dismiss();
        shown = null;
    }

    // ---- Look ----

    private TextView button(String label, int size) {
        final TextView b = text(size, 0xD9FFFFFF, regular);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setFocusable(true);
        final GradientDrawable bg = glass(0x13FFFFFF);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            b.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(150).start();
            if (b.isSelected()) return; // the lit mode keeps its colours
            bg.setColor(has ? 0x38FFFFFF : 0x13FFFFFF);
            b.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
        });
        return b;
    }

    private GradientDrawable glass(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(RADIUS));
        g.setColor(color);
        g.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
        return g;
    }

    private TextView text(int size, int color, Typeface tf) {
        TextView t = new TextView(a);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private int px(int v) {
        return Math.round(v * scale);
    }

    // The unit's target temperature as the panel shows it, or NaN. Some Tuya air conditioners have it
    // read back by Home Assistant as Fahrenheit although it is Celsius: 24 shows as -4.4 (setting it
    // works). A target below the unit's minimum is that mistake, undone, and rounded to the unit's step.
    static double target(double t, double min, double max, double step) {
        if (Double.isNaN(t)) return t;
        if (t < min) t = Math.round((t * 9 / 5 + 32) / step) * step;
        return t >= min && t <= max ? t : Double.NaN;
    }

    // 24 or 23.5, without a stray ".0".
    static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }
}
