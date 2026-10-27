package com.clean.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Forecast from Open-Meteo (free, no key), location from the TV's public IP (ipwho.is).
 * The last answer is cached so the widget shows at once after a restart.
 */
final class Weather {

    private static final String TAG = "CleanHomeWeather";
    static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long LOCATION_MS = 24 * 60 * 60 * 1000L;

    enum Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

    static final int DAYS = 8; // today and the week ahead

    /** Precipitation marks on the chart. */
    static final int WET_NONE = 0, WET_RAIN = 1, WET_SNOW = 2, WET_STORM = 3;

    /** One parsed forecast. */
    static final class Data {
        String city;
        boolean day;
        int temp, humidity, pressure, wind, code;
        int nowIndex;                     // index of the current hour in the hourly arrays
        String[] hourTime = new String[0];  // yyyy-MM-ddTHH:mm
        int[] hourTemp = new int[0], hourCode = new int[0], hourProb = new int[0],
                hourWind = new int[0], hourWindDir = new int[0], hourDay = new int[0];
        double[] hourPrecip = new double[0];
        int[] dayCode = new int[0], dayMax = new int[0], dayMin = new int[0], dayHumidity = new int[0],
                dayWind = new int[0], dayPressure = new int[0], dayProb = new int[0];
        double[] dayPrecip = new double[0];
        String[] dayDate = new String[0]; // yyyy-MM-dd
    }

    /** The chart for one day: today = the next 24 hours, later days = 00:00 to 24:00. */
    static final class Hours {
        int[] temps, hours, wet, codes, day, wind, windDir;
        double[] precip;
    }

    static Hours hours(Data d, int day) {
        int start, count = 25;
        if (day == 0) {
            start = d.nowIndex;
        } else {
            start = -1;
            for (int i = 0; i < d.hourTime.length; i++) {
                if (d.hourTime[i].startsWith(d.dayDate[day])) {
                    start = i;
                    break;
                }
            }
            if (start < 0) start = 0;
        }
        count = Math.max(0, Math.min(count, d.hourTime.length - start));
        Hours h = new Hours();
        h.temps = new int[count];
        h.hours = new int[count];
        h.wet = new int[count];
        h.codes = new int[count];
        h.day = new int[count];
        h.wind = new int[count];
        h.windDir = new int[count];
        h.precip = new double[count];
        for (int i = 0; i < count; i++) {
            int k = start + i;
            h.temps[i] = d.hourTemp[k];
            h.hours[i] = Integer.parseInt(d.hourTime[k].substring(11, 13));
            h.wet[i] = wet(d.hourCode[k], d.hourPrecip[k], d.hourProb[k]);
            h.codes[i] = d.hourCode[k];
            h.day[i] = d.hourDay.length > k ? d.hourDay[k] : 1;
            h.wind[i] = d.hourWind.length > k ? d.hourWind[k] : 0;
            h.windDir[i] = d.hourWindDir.length > k ? d.hourWindDir[k] : 0;
            h.precip[i] = d.hourPrecip[k];
        }
        return h;
    }

    /** Precipitation worth marking: some is forecast, or it is likely (50 %+) and coded as wet. */
    static int wet(int code, double mm, int prob) {
        Kind k = kind(code);
        boolean wetCode = k == Kind.DRIZZLE || k == Kind.RAIN || k == Kind.SNOW || k == Kind.STORM;
        if (mm < 0.1 && !(wetCode && prob >= 50)) return WET_NONE;
        if (k == Kind.SNOW) return WET_SNOW;
        if (k == Kind.STORM) return WET_STORM;
        return WET_RAIN;
    }

    private Weather() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return ScreenEffects.prefs(c).getBoolean("weather", true);
    }

    static void setEnabled(Context c, boolean on) {
        ScreenEffects.prefs(c).edit().putBoolean("weather", on).apply();
    }

    /** Cached forecast, or null. */
    static Data cached(Context c) {
        String json = prefs(c).getString("forecast", null);
        String city = prefs(c).getString("city", "");
        if (json == null) return null;
        try {
            return parse(json, city);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean stale(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong("fetched", 0) > REFRESH_MS;
    }

    /** Network call: run off the main thread. Returns null on failure (the cache stays). */
    static Data fetch(Context c) {
        SharedPreferences p = prefs(c);
        try {
            if (System.currentTimeMillis() - p.getLong("located", 0) > LOCATION_MS || !p.contains("lat")) {
                JSONObject ip = new JSONObject(get("https://ipwho.is/?fields=success,city,latitude,longitude"));
                if (ip.optBoolean("success", false)) {
                    p.edit().putFloat("lat", (float) ip.getDouble("latitude"))
                            .putFloat("lon", (float) ip.getDouble("longitude"))
                            .putString("city", ip.optString("city", ""))
                            .putLong("located", System.currentTimeMillis()).apply();
                }
            }
            if (!p.contains("lat")) return null;
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                            + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,surface_pressure,is_day"
                            + "&hourly=temperature_2m,weather_code,precipitation,precipitation_probability,is_day,wind_speed_10m,wind_direction_10m"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,relative_humidity_2m_mean,"
                            + "wind_speed_10m_max,pressure_msl_mean,precipitation_sum,precipitation_probability_max"
                            + "&timezone=auto&forecast_days=" + DAYS,
                    p.getFloat("lat", 0), p.getFloat("lon", 0));
            String json = get(url);
            Data d = parse(json, p.getString("city", ""));
            p.edit().putString("forecast", json).putLong("fetched", System.currentTimeMillis()).apply();
            return d;
        } catch (Exception e) {
            Log.w(TAG, "weather fetch failed: " + e);
            return null;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestProperty("User-Agent", "CleanHome-TV");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }

    private static Data parse(String json, String city) throws Exception {
        JSONObject o = new JSONObject(json);
        Data d = new Data();
        d.city = city;
        JSONObject cur = o.getJSONObject("current");
        d.temp = (int) Math.round(cur.getDouble("temperature_2m"));
        d.humidity = (int) Math.round(cur.optDouble("relative_humidity_2m", 0));
        d.pressure = (int) Math.round(cur.optDouble("surface_pressure", 0));
        d.wind = (int) Math.round(cur.optDouble("wind_speed_10m", 0));
        d.code = cur.optInt("weather_code", 0);
        d.day = cur.optInt("is_day", 1) == 1;

        // Hourly series for all days; nowIndex marks the current hour.
        String now = cur.optString("time", "");               // e.g. 2026-10-22T15:45
        String hourPrefix = now.length() >= 13 ? now.substring(0, 13) : "";
        JSONObject h = o.getJSONObject("hourly");
        JSONArray times = h.getJSONArray("time");
        int n = times.length();
        d.hourTime = new String[n];
        d.hourTemp = new int[n];
        d.hourCode = new int[n];
        d.hourProb = new int[n];
        d.hourWind = new int[n];
        d.hourWindDir = new int[n];
        d.hourDay = new int[n];
        JSONArray ws = h.optJSONArray("wind_speed_10m"), wd = h.optJSONArray("wind_direction_10m"),
                isDay = h.optJSONArray("is_day");
        d.hourPrecip = new double[n];
        JSONArray temps = h.getJSONArray("temperature_2m"), codes = h.optJSONArray("weather_code"),
                prec = h.optJSONArray("precipitation"), prob = h.optJSONArray("precipitation_probability");
        for (int i = 0; i < n; i++) {
            d.hourTime[i] = times.getString(i);
            if (d.hourTime[i].startsWith(hourPrefix)) d.nowIndex = i;
            d.hourTemp[i] = (int) Math.round(temps.optDouble(i, 0));
            d.hourCode[i] = codes == null ? 0 : codes.optInt(i, 0);
            d.hourPrecip[i] = prec == null ? 0 : prec.optDouble(i, 0);
            d.hourProb[i] = prob == null ? 0 : prob.optInt(i, 0);
            d.hourWind[i] = ws == null ? 0 : (int) Math.round(ws.optDouble(i, 0));
            d.hourWindDir[i] = wd == null ? 0 : wd.optInt(i, 0);
            d.hourDay[i] = isDay == null ? 1 : isDay.optInt(i, 1);
        }

        JSONObject dl = o.getJSONObject("daily");
        JSONArray dt = dl.getJSONArray("time");
        int days = dt.length();
        d.dayDate = new String[days];
        d.dayCode = new int[days];
        d.dayMax = new int[days];
        d.dayMin = new int[days];
        d.dayHumidity = new int[days];
        d.dayWind = new int[days];
        d.dayPressure = new int[days];
        d.dayProb = new int[days];
        d.dayPrecip = new double[days];
        for (int i = 0; i < days; i++) {
            d.dayDate[i] = dt.getString(i);
            d.dayCode[i] = dl.getJSONArray("weather_code").optInt(i, 0);
            d.dayMax[i] = (int) Math.round(dl.getJSONArray("temperature_2m_max").optDouble(i, 0));
            d.dayMin[i] = (int) Math.round(dl.getJSONArray("temperature_2m_min").optDouble(i, 0));
            d.dayHumidity[i] = optInt(dl, "relative_humidity_2m_mean", i);
            d.dayWind[i] = optInt(dl, "wind_speed_10m_max", i);
            d.dayPressure[i] = optInt(dl, "pressure_msl_mean", i);
            d.dayProb[i] = optInt(dl, "precipitation_probability_max", i);
            JSONArray ps = dl.optJSONArray("precipitation_sum");
            d.dayPrecip[i] = ps == null ? 0 : ps.optDouble(i, 0);
        }
        return d;
    }

    private static int optInt(JSONObject o, String key, int i) {
        JSONArray a = o.optJSONArray(key);
        return a == null ? -1 : (int) Math.round(a.optDouble(i, -1));
    }

    /** WMO weather code to an icon family. */
    static Kind kind(int code) {
        if (code == 0) return Kind.CLEAR;
        if (code <= 2) return Kind.PARTLY;
        if (code == 3) return Kind.CLOUDY;
        if (code == 45 || code == 48) return Kind.FOG;
        if (code >= 51 && code <= 57) return Kind.DRIZZLE;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return Kind.RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Kind.SNOW;
        if (code >= 95) return Kind.STORM;
        return Kind.CLOUDY;
    }

    static int describe(int code) {
        switch (kind(code)) {
            case CLEAR: return R.string.wx_clear;
            case PARTLY: return R.string.wx_partly;
            case CLOUDY: return R.string.wx_cloudy;
            case FOG: return R.string.wx_fog;
            case DRIZZLE: return R.string.wx_drizzle;
            case RAIN: return R.string.wx_rain;
            case SNOW: return R.string.wx_snow;
            default: return R.string.wx_storm;
        }
    }
}
