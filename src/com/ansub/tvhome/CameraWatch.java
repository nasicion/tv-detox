package com.ansub.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

// Camera pop-ups: watches the sensors named by "popup" on this TV's cameras in homeassistant.json, and
// when one turns on (a person at the door, motion), shows that camera live in a corner window over
// whatever is playing. It closes by itself after "popup_seconds" (30 by default; a new detection keeps
// it up). While it's up, OK opens the camera full screen and any other button closes it.
//
// It runs as a foreground service, so Android keeps it going while another app is in front, and asks
// Home Assistant for each sensor once a second (a few hundred bytes each). The window needs "display
// over other apps", which install.sh grants. MainActivity starts it when there's something to watch.
public final class CameraWatch extends Service {
    private static final String CHANNEL = "camera-watch";
    private static final long POLL_MS = 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Thread poller;

    // The window, while it's up.
    private View window;
    private final AtomicReference<Thread> streaming = new AtomicReference<>();
    private final AtomicReference<HttpURLConnection> conn = new AtomicReference<>();
    private final Runnable close = this::hide;

    // Start or stop the watch to match what's saved: running only while a camera has "popup".
    static void sync(Context c, HomeAssistant ha) {
        boolean want = ha != null;
        if (want) {
            want = false;
            for (HomeAssistant.Item it : ha.items(HomeAssistant.CAMERAS)) if (!it.popup.isEmpty()) want = true;
        }
        Intent i = new Intent(c, CameraWatch.class);
        try {
            if (!want) c.stopService(i);
            else if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Camera pop-ups",
                    NotificationManager.IMPORTANCE_MIN));
            startForeground(1, new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(getApplicationInfo().icon)
                    .setContentTitle("Watching cameras").build());
        }
        // A new start (the file changed) reads the settings again.
        poller = null;
        final HomeAssistant ha = HomeAssistant.load(getSharedPreferences("config", 0).getString("homeassistant", null));
        if (ha == null) { stopSelf(); return START_NOT_STICKY; }
        Thread t = new Thread(() -> watch(ha));
        poller = t;
        t.start();
        return START_STICKY;
    }

    private void watch(HomeAssistant ha) {
        Thread me = Thread.currentThread();
        Map<String, String> last = new HashMap<>(); // sensor -> state last read
        List<HomeAssistant.Item> cams = ha.items(HomeAssistant.CAMERAS);
        while (poller == me) {
            for (HomeAssistant.Item cam : cams) {
                for (String sensor : cam.popup) {
                    String state;
                    try {
                        state = ha.state(sensor).optString("state");
                    } catch (Exception e) {
                        continue; // Home Assistant away for a moment: try again next round
                    }
                    String before = last.put(sensor, state);
                    // Only a change to "on" counts: not one already on when the watch started.
                    if ("on".equals(state) && before != null && !"on".equals(before)) {
                        main.post(() -> show(ha, cam));
                    }
                }
            }
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
        }
    }

    // ---- The corner window ----

    private void show(final HomeAssistant ha, final HomeAssistant.Item cam) {
        main.removeCallbacks(close);
        main.postDelayed(close, cam.popupSeconds * 1000L);
        if (window != null && cam.entity.equals(window.getTag())) return; // already showing it: just stay up longer
        hide();
        if (HomeAssistantViews.fullScreenOpen) return; // the camera is already full screen
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;

        float s = getResources().getDisplayMetrics().widthPixels / 1920f;
        int w = Math.round(640 * s), h = Math.round(360 * s), margin = Math.round(48 * s);

        FrameLayout box = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_UP) return true;
                int k = e.getKeyCode();
                hide();
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    // Full screen, on the home screen.
                    startActivity(new Intent(CameraWatch.this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("camera", cam.key()));
                }
                return true;
            }
        };
        box.setTag(cam.entity);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(26 * s);
        bg.setColor(Color.BLACK);
        bg.setStroke(Math.max(1, Math.round(3 * s)), 0x59FFFFFF);
        box.setBackground(bg);
        box.setClipToOutline(true);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        final ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        TextView label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * s);
        label.setTextColor(Color.WHITE);
        label.setShadowLayer(10 * s, 0, 2 * s, 0xCC000000);
        label.setText(cam.name + "   OK ⤢");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = Math.round(20 * s);
        lp.bottomMargin = Math.round(14 * s);
        box.addView(label, lp);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = margin;
        p.y = margin;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(box, p);
        } catch (Exception e) {
            return; // no permission after all
        }
        window = box;
        box.requestFocus();

        final int maxWidth = w;
        Thread th = new Thread(() -> {
            Thread me = Thread.currentThread();
            while (streaming.get() == me) {
                try {
                    ha.stream(cam.entity, conn, (jpeg, n) -> {
                        if (streaming.get() != me) return false;
                        final Bitmap b = HomeAssistant.decode(jpeg, n, maxWidth);
                        if (b != null) main.post(() -> { if (streaming.get() == me) picture.setImageBitmap(b); });
                        return true;
                    });
                } catch (Exception ignored) {}
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        });
        streaming.set(th);
        th.start();
    }

    private void hide() {
        main.removeCallbacks(close);
        Thread old = streaming.getAndSet(null);
        if (old != null) old.interrupt();
        final HttpURLConnection c = conn.getAndSet(null);
        if (c != null) new Thread(c::disconnect).start();
        if (window != null) {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(window); } catch (Exception ignored) {}
            window = null;
        }
    }

    @Override
    public void onDestroy() {
        poller = null;
        hide();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
