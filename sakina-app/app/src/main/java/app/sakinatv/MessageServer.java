package app.sakinatv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A tiny web page on port 8080: open http://<tv-ip>:8080 on a phone on the same Wi-Fi, then write a message
 * (shown in the middle of the TV for 20 seconds) or draw with a finger (drawn on the TV live, gone 15 seconds
 * after the last line). Only reachable from the home network.
 */
public class MessageServer extends Service {
    static final int PORT = 8080;
    static final int MAX = 200;
    static final int NAME_MAX = 20;
    static final long DRAW_IDLE = 15_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket server;
    private View shown;
    private DrawLayer drawing;
    private final Runnable hideDrawing = this::hideDrawing;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, MessageServer.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("msg", "رسائل الجوال", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "msg")
                .setContentTitle("رسائل الجوال")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .build();
        startForeground(1, n);
        new Thread(this::serve, "msg-server").start();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private void serve() {
        try {
            server = new ServerSocket(PORT);
            while (true) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5000);
                    handle(s);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            // Port taken (a second start): the first server keeps running.
        }
    }

    private void handle(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        String head = readHead(in);
        String first = head.split("\r\n", 2)[0];
        if (!first.startsWith("POST ")) {
            reply(s.getOutputStream(), "text/html", page());
            return;
        }
        String body = readBody(in, head);
        if (first.startsWith("POST /send")) {
            // "name\ntext": the name is optional and may be empty.
            int nl = body.indexOf('\n');
            String name = nl < 0 ? "" : body.substring(0, nl).trim();
            String text = (nl < 0 ? body : body.substring(nl + 1)).trim();
            if (name.length() > NAME_MAX) name = name.substring(0, NAME_MAX);
            if (text.length() > MAX) text = text.substring(0, MAX);
            if (!text.isEmpty()) {
                String t = text, n = name;
                main.post(() -> show(t, n));
            }
        } else if (first.startsWith("POST /draw")) {
            String segs = body;
            main.post(() -> draw(segs));
        } else if (first.startsWith("POST /clear")) {
            main.post(this::hideDrawing);
        }
        reply(s.getOutputStream(), "text/plain", "ok");
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) != -1 && b.size() < 8192) {
            b.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) break;
        }
        return b.toString("UTF-8");
    }

    private static String readBody(InputStream in, String head) throws Exception {
        int len = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
        }
        byte[] body = new byte[Math.min(len, 65536)];
        int got = 0;
        while (got < body.length) {
            int r = in.read(body, got, body.length - got);
            if (r < 0) break;
            got += r;
        }
        return new String(body, 0, got, StandardCharsets.UTF_8);
    }

    private static void reply(OutputStream out, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static WindowManager.LayoutParams overlay(int size) {
        return new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
    }

    private void draw(String segs) {
        if (!Settings.canDrawOverlays(this)) return;
        if (drawing == null) {
            drawing = new DrawLayer(this);
            try {
                getSystemService(WindowManager.class).addView(drawing, overlay(WindowManager.LayoutParams.MATCH_PARENT));
            } catch (Exception e) {
                drawing = null;
                return;
            }
        }
        DrawLayer d = drawing;
        // The view has no size until its first layout; draw after it.
        d.post(() -> {
            for (String seg : segs.split("\n")) {
                if (!seg.isEmpty()) d.add(seg);
            }
        });
        main.removeCallbacks(hideDrawing);
        main.postDelayed(hideDrawing, DRAW_IDLE);
    }

    private void hideDrawing() {
        main.removeCallbacks(hideDrawing);
        if (drawing == null) return;
        DrawLayer d = drawing;
        drawing = null;
        d.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            try {
                getSystemService(WindowManager.class).removeView(d);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void show(String text, String name) {
        if (!Settings.canDrawOverlays(this)) return;
        WindowManager wm = getSystemService(WindowManager.class);
        if (shown != null) {
            try {
                wm.removeView(shown);
            } catch (Exception ignored) {
            }
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(90, 44, 90, 48);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF0163A2C, 0xF00B1F18});
        bg.setCornerRadius(56);
        bg.setStroke(4, 0xCCE0B45A);
        card.setBackground(bg);
        card.setElevation(24);

        TextView top = new TextView(this);
        top.setText("💌 رسالة من الموبايل");
        top.setTextColor(0xFFE0B45A);
        top.setTextSize(22);
        top.setGravity(Gravity.CENTER);
        card.addView(top);

        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(0xFFF4EFE3);
        v.setTextSize(text.length() > 80 ? 40 : 56);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setLineSpacing(8, 1f);
        v.setMaxWidth(1500);
        v.setPadding(0, 18, 0, 0);
        card.addView(v);

        if (!name.isEmpty()) {
            TextView from = new TextView(this);
            from.setText("— من " + name);
            from.setTextColor(0xCCF4EFE3);
            from.setTextSize(24);
            from.setGravity(Gravity.CENTER);
            from.setPadding(0, 20, 0, 0);
            card.addView(from);
        }

        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        WindowManager.LayoutParams lp = overlay(WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        try {
            wm.addView(card, lp);
        } catch (Exception e) {
            return;
        }
        shown = card;
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).start();
        main.postDelayed(() -> card.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            try {
                wm.removeView(card);
            } catch (Exception ignored) {
            }
            if (shown == card) shown = null;
        }).start(), 20_000);
    }

    /** The phone page, from assets/phone.html; read once. */
    private String page;

    private String page() {
        if (page == null) {
            try (InputStream in = getAssets().open("phone.html")) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int r; (r = in.read(buf)) > 0; ) b.write(buf, 0, r);
                page = b.toString("UTF-8");
            } catch (Exception e) {
                page = "<meta charset=utf-8>سكينة TV";
            }
        }
        return page;
    }
}
