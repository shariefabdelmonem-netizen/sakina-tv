package com.gathin.tvhome;

import android.icu.util.IslamicCalendar;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/**
 * The line under the clock: Hijri date (Umm al-Qura), the weather now, and the time left to the next
 * prayer. Weather and prayer times come from Open-Meteo and Aladhan in the background; until they arrive the line
 * shows the Hijri date alone. Read once a minute with the clock.
 */
final class Sakina {
    static final double LAT = @LAT@, LON = @LON@;
    static final int METHOD = @METHOD@; // Aladhan calculation method

    private static final String[] MONTHS = {"محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى",
            "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"};
    private static final String[] PRAYERS = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] PRAYERS_AR = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};

    private static volatile String weather = "";
    private static volatile long weatherAt;
    private static volatile int[] prayerMinutes; // minutes after midnight, Fajr..Isha
    private static volatile int prayerDay = -1;
    private static volatile boolean fetching;
    private static volatile String hijri; // "19 جمادى الأولى 1448" from Aladhan, for the local calendar
    private static volatile int hijriDayAladhan = -1;

    private Sakina() {
    }

    static int hijriDay() {
        if (hijriDayAladhan > 0 && prayerDay == Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) return hijriDayAladhan;
        IslamicCalendar h = new IslamicCalendar();
        h.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        return h.get(IslamicCalendar.DAY_OF_MONTH);
    }

    /** "الأيام البيض 🤍 اليوم 13" on the 13th to 15th, "جمعة مباركة 🌿" on Friday, otherwise null. */
    static String greeting() {
        int d = hijriDay();
        if (d >= 13 && d <= 15) return "الأيام البيض 🤍 اليوم " + d;
        if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY) return "جمعة مباركة 🌿";
        return null;
    }

    static boolean whiteDay() {
        int d = hijriDay();
        return d >= 13 && d <= 15;
    }

    static String line() {
        refreshIfStale();
        StringBuilder b = new StringBuilder();
        if (hijri != null && prayerDay == Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) {
            b.append(hijri);
        } else {
            IslamicCalendar h = new IslamicCalendar();
            h.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            b.append(h.get(IslamicCalendar.DAY_OF_MONTH)).append(' ')
                    .append(MONTHS[h.get(IslamicCalendar.MONTH)]).append(' ')
                    .append(h.get(IslamicCalendar.EXTENDED_YEAR));
        }
        if (!weather.isEmpty()) b.append("  ·  ").append(weather);
        String next = nextPrayer();
        if (next != null) b.append('\n').append(next);
        return b.toString();
    }

    /** "🕌 العصر بعد 3:51", or null before the times arrive. */
    private static String nextPrayer() {
        int[] p = prayerMinutes;
        if (p == null) return null;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        for (int i = 0; i < p.length; i++) {
            if (p[i] > now) return "🕌 " + PRAYERS_AR[i] + " بعد " + hm(p[i] - now);
        }
        return "🕌 الفجر بعد " + hm(24 * 60 - now + p[0]); // after Isha: tomorrow's Fajr, close enough
    }

    private static String hm(int minutes) {
        return String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60);
    }

    private static void refreshIfStale() {
        int today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        boolean needWeather = System.currentTimeMillis() - weatherAt > 30 * 60_000L;
        boolean needPrayer = prayerDay != today;
        if ((!needWeather && !needPrayer) || fetching) return;
        fetching = true;
        new Thread(() -> {
            try {
                if (needPrayer) loadPrayers(today);
                if (needWeather) loadWeather();
            } finally {
                fetching = false;
            }
        }, "sakina").start();
    }

    private static void loadPrayers(int today) {
        try {
            Calendar c = Calendar.getInstance();
            String date = String.format(Locale.US, "%02d-%02d-%d", c.get(Calendar.DAY_OF_MONTH),
                    c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
            JSONObject data = new JSONObject(get("https://api.aladhan.com/v1/timings/" + date + "?latitude=" + LAT
                    + "&longitude=" + LON + "&method=" + METHOD)).getJSONObject("data");
            JSONObject t = data.getJSONObject("timings");
            JSONObject h = data.getJSONObject("date").getJSONObject("hijri");
            hijriDayAladhan = Integer.parseInt(h.getString("day"));
            hijri = hijriDayAladhan + " " + MONTHS[h.getJSONObject("month").getInt("number") - 1] + " " + h.getString("year");
            int[] m = new int[PRAYERS.length];
            for (int i = 0; i < PRAYERS.length; i++) {
                String[] hhmm = t.getString(PRAYERS[i]).substring(0, 5).split(":");
                m[i] = Integer.parseInt(hhmm[0]) * 60 + Integer.parseInt(hhmm[1]);
            }
            prayerMinutes = m;
            prayerDay = today;
        } catch (Exception ignored) {
            // Offline: keep yesterday's times until the next minute tries again.
        }
    }

    private static void loadWeather() {
        try {
            JSONObject cur = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + LAT
                    + "&longitude=" + LON + "&current=temperature_2m,weather_code,is_day&timezone=auto"))
                    .getJSONObject("current");
            long t = Math.round(cur.getDouble("temperature_2m"));
            weather = icon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1) + " " + t + "°";
            weatherAt = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    /** WMO weather code to one symbol. */
    private static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "🌤️" : "🌙";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 95) return "⛈️";
        if (code >= 51) return "🌧️";
        return "🌤️";
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
