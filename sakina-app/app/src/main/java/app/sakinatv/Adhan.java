package app.sakinatv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/**
 * The adhan at each prayer time for the city in gradle.properties. With the home screen up and nothing playing it plays the adhan in
 * res/raw (tools/fetch-adhan.sh) under a banner; while a film, a channel or HDMI is on it only shows the banner, so nothing is
 * cut off. Fajr is banner only: most adhan recordings lack «الصلاة خير من النوم». Only the Kahf shortcut runs it.
 */
public class Adhan extends BroadcastReceiver {
    static final String FIRE = "app.sakinatv.ADHAN";
    static final double LAT = BuildConfig.LAT, LON = BuildConfig.LON;
    static final String HOME = "com.gathin.tvhome";
    private static final String[] KEYS = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] NAMES = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};

    private static MediaPlayer player;

    /** Sets the alarm for the next prayer; fetches today's and tomorrow's times in the background. */
    static void schedule(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            Calendar now = Calendar.getInstance();
            for (int dayOffset = 0; dayOffset < 2; dayOffset++) {
                Calendar day = (Calendar) now.clone();
                day.add(Calendar.DAY_OF_YEAR, dayOffset);
                String[] times = times(app, day);
                if (times == null) continue;
                for (int i = 0; i < KEYS.length; i++) {
                    String[] hm = times[i].split(":");
                    Calendar at = (Calendar) day.clone();
                    at.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                    at.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                    at.set(Calendar.SECOND, 0);
                    at.set(Calendar.MILLISECOND, 0);
                    if (at.getTimeInMillis() > now.getTimeInMillis() + 30_000) {
                        set(app, at.getTimeInMillis(), i);
                        return;
                    }
                }
            }
        }, "adhan").start();
    }

    private static void set(Context c, long when, int prayer) {
        Intent fire = new Intent(c, Adhan.class).setAction(FIRE).putExtra("p", prayer);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, fire,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    /** "HH:mm" for Fajr..Isha on that day, from Aladhan (sakina.method), cached per day for offline days. */
    private static String[] times(Context c, Calendar day) {
        String date = String.format(Locale.US, "%02d-%02d-%d", day.get(Calendar.DAY_OF_MONTH),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.YEAR));
        SharedPreferences p = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
        String cached = p.getString(date, null);
        if (cached != null) return cached.split(",");
        try {
            JSONObject t = new JSONObject(get("https://api.aladhan.com/v1/timings/" + date + "?latitude=" + LAT
                    + "&longitude=" + LON + "&method=" + BuildConfig.METHOD)).getJSONObject("data").getJSONObject("timings");
            String[] out = new String[KEYS.length];
            for (int i = 0; i < KEYS.length; i++) out[i] = t.getString(KEYS[i]).substring(0, 5);
            p.edit().putString(date, String.join(",", out)).apply();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!BuildConfig.REMINDERS || !FIRE.equals(i.getAction())) return;
        Context app = c.getApplicationContext();
        int prayer = i.getIntExtra("p", 1);
        schedule(app);
        if (!app.getSystemService(PowerManager.class).isInteractive()) return; // TV is off
        boolean idle = prayer != 0 && HOME.equals(topApp(app))
                && !app.getSystemService(AudioManager.class).isMusicActive();
        fire(app, prayer, idle);
    }

    /** Banner always; the adhan itself only when play is true. */
    static void fire(Context app, int prayer, boolean play) {
        String text = "🕌 حان الآن موعد أذان " + NAMES[prayer];
        if (!play) {
            banner(app, text, 30_000);
            return;
        }
        int raw = app.getResources().getIdentifier("adhan", "raw", app.getPackageName());
        if (raw == 0) {
            banner(app, text, 30_000);
            return;
        }
        stop();
        player = MediaPlayer.create(app, raw, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                app.getSystemService(AudioManager.class).generateAudioSessionId());
        if (player == null) {
            banner(app, text, 30_000);
            return;
        }
        player.setOnCompletionListener(mp -> stop());
        player.start();
        banner(app, text, player.getDuration());
        watch(app);
    }

    /** Stops the adhan as soon as an app is opened over the home screen. */
    private static void watch(Context app) {
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player == null) return;
                String top = topApp(app);
                if (top != null && !HOME.equals(top)) {
                    stop();
                    return;
                }
                h.postDelayed(this, 1500);
            }
        }, 1500);
    }

    private static void stop() {
        if (player == null) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        player.release();
        player = null;
    }

    /** The app in front, from usage events (GET_USAGE_STATS is granted over adb), or null if unknown. */
    static String topApp(Context c) {
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(now - 24 * 3600_000L, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String top = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) top = e.getPackageName();
        }
        return top;
    }

    private static void banner(Context c, String text, int ms) {
        if (!Settings.canDrawOverlays(c)) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            WindowManager wm = c.getSystemService(WindowManager.class);
            TextView v = new TextView(c);
            v.setText(text);
            v.setTextColor(Color.WHITE);
            v.setTextSize(30);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setPadding(56, 24, 56, 24);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE60F3D2E);
            bg.setCornerRadius(48);
            bg.setStroke(3, 0xFFFFE08A);
            v.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = 215; // below the clock block, so the weather and next prayer stay readable
            try {
                wm.addView(v, lp);
            } catch (Exception e) {
                return;
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    wm.removeView(v);
                } catch (Exception ignored) {
                }
            }, ms);
        });
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
