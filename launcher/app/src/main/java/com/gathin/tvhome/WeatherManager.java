package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ultra-low-power weather pill.
 * - Caches in SharedPreferences for 45 minutes.
 * - Zero continuous CPU / zero background services / zero alarms.
 * - Updates only on launcher resume if cache is expired (>45 min).
 * - Instant UI display from cache on start/resume.
 * - Fails silently on network errors without blocking UI.
 * - Clicking the pill displays location details and conditions.
 */
public class WeatherManager {
    private static final String TAG = "TvHome.Weather";
    private static final String PREFS_NAME = "weather_cache";
    private static final String KEY_SUMMARY = "summary";
    private static final String KEY_DETAILS = "details";
    private static final String KEY_TIMESTAMP = "timestamp";
    private static final String KEY_CITY = "city";
    private static final long CACHE_DURATION_MS = 45 * 60 * 1000L; // 45 minutes
    private static final long MIN_REFRESH_INTERVAL_MS = 5 * 60 * 1000L; // 5 min for manual click refresh

    private final Activity activity;
    private final TextView weatherButton;
    private final boolean enabled;
    private final String city;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private boolean isFetching = false;
    private String currentDetails = null;

    public WeatherManager(Activity activity, TextView weatherButton, boolean enabled, String city) {
        this.activity = activity;
        this.weatherButton = weatherButton;
        this.enabled = enabled;
        this.city = city != null ? city.trim() : "";
        this.prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (!enabled) {
            weatherButton.setVisibility(View.GONE);
            return;
        }

        String cachedCity = prefs.getString(KEY_CITY, "");
        boolean cityChanged = !cachedCity.equalsIgnoreCase(this.city);
        if (cityChanged) {
            prefs.edit().remove(KEY_SUMMARY).remove(KEY_DETAILS).remove(KEY_TIMESTAMP).apply();
            fetchWeatherAsync();
        } else {
            // Show cached weather immediately if available
            showCachedWeather();
            long lastFetch = prefs.getLong(KEY_TIMESTAMP, 0L);
            if (System.currentTimeMillis() - lastFetch > CACHE_DURATION_MS) {
                fetchWeatherAsync();
            }
        }

        weatherButton.setOnClickListener(v -> {
            if (currentDetails != null && !currentDetails.isEmpty()) {
                Toast.makeText(activity, currentDetails, Toast.LENGTH_SHORT).show();
            }
            long lastFetch = prefs.getLong(KEY_TIMESTAMP, 0L);
            if (System.currentTimeMillis() - lastFetch > MIN_REFRESH_INTERVAL_MS) {
                fetchWeatherAsync();
            }
        });
    }

    public void onResume() {
        if (!enabled) return;
        String cachedCity = prefs.getString(KEY_CITY, "");
        boolean cityChanged = !cachedCity.equalsIgnoreCase(this.city);
        if (cityChanged) {
            prefs.edit().remove(KEY_SUMMARY).remove(KEY_DETAILS).remove(KEY_TIMESTAMP).apply();
            fetchWeatherAsync();
        } else {
            showCachedWeather();
            long lastFetch = prefs.getLong(KEY_TIMESTAMP, 0L);
            if (System.currentTimeMillis() - lastFetch > CACHE_DURATION_MS) {
                fetchWeatherAsync();
            }
        }
    }

    public void onDestroy() {
        executor.shutdownNow();
    }

    private void showCachedWeather() {
        String summary = prefs.getString(KEY_SUMMARY, null);
        String details = prefs.getString(KEY_DETAILS, null);
        if (summary != null && !summary.isEmpty()) {
            currentDetails = details;
            weatherButton.setText(summary);
            weatherButton.setVisibility(View.VISIBLE);
        }
    }

    private synchronized void fetchWeatherAsync() {
        if (isFetching || !enabled) return;
        isFetching = true;

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String urlString;
                if (!city.isEmpty()) {
                    urlString = "https://wttr.in/" + URLEncoder.encode(city, "UTF-8") + "?format=j1";
                } else {
                    urlString = "https://wttr.in/?format=j1";
                }

                URL url = new URL(urlString);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("User-Agent", "TvHome/1.0 (Android TV)");
                conn.setRequestProperty("Accept", "application/json");

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray conditionArr = root.optJSONArray("current_condition");
                    if (conditionArr != null && conditionArr.length() > 0) {
                        JSONObject cur = conditionArr.getJSONObject(0);
                        String tempC = cur.optString("temp_C", "");
                        String weatherCode = cur.optString("weatherCode", "");

                        String weatherDesc = "";
                        JSONArray descArr = cur.optJSONArray("weatherDesc");
                        if (descArr != null && descArr.length() > 0) {
                            weatherDesc = descArr.getJSONObject(0).optString("value", "");
                        }

                        String areaName = "";
                        JSONArray areaArr = root.optJSONArray("nearest_area");
                        if (areaArr != null && areaArr.length() > 0) {
                            JSONArray nameArr = areaArr.getJSONObject(0).optJSONArray("areaName");
                            if (nameArr != null && nameArr.length() > 0) {
                                areaName = nameArr.getJSONObject(0).optString("value", "");
                            }
                        }

                        String displayLocation = !city.isEmpty() ? city : areaName;
                        String emoji = getWeatherEmoji(weatherCode, weatherDesc);
                        String summary = emoji + " " + tempC + "°C";
                        String details = (!displayLocation.isEmpty() ? displayLocation + ": " : "") + tempC + "°C, " + weatherDesc;

                        prefs.edit()
                                .putString(KEY_SUMMARY, summary)
                                .putString(KEY_DETAILS, details)
                                .putString(KEY_CITY, city)
                                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                                .apply();

                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            currentDetails = details;
                            weatherButton.setText(summary);
                            weatherButton.setVisibility(View.VISIBLE);
                        });
                    }
                }
            } catch (Throwable t) {
                Log.d(TAG, "Weather fetch skipped or failed: " + t.getMessage());
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {}
                }
                isFetching = false;
            }
        });
    }

    private static String getWeatherEmoji(String code, String desc) {
        if (code != null) {
            switch (code) {
                case "113": return "☀️"; // Clear/Sunny
                case "116": return "⛅"; // Partly Cloudy
                case "119":
                case "122": return "☁️"; // Cloudy / Overcast
                case "143":
                case "248":
                case "260": return "🌫️"; // Mist / Fog
                case "176":
                case "263":
                case "266":
                case "281":
                case "284":
                case "293":
                case "296":
                case "299":
                case "302":
                case "305":
                case "308":
                case "311":
                case "314":
                case "353":
                case "356":
                case "359": return "🌧️"; // Rain / Drizzle / Shower
                case "200":
                case "386":
                case "389":
                case "392":
                case "395": return "⛈️"; // Thunderstorm
                case "179":
                case "182":
                case "185":
                case "227":
                case "230":
                case "317":
                case "320":
                case "323":
                case "326":
                case "329":
                case "332":
                case "335":
                case "338":
                case "350":
                case "368":
                case "371":
                case "374":
                case "377": return "🌨️"; // Snow / Sleet / Ice
            }
        }
        if (desc != null) {
            String lower = desc.toLowerCase();
            if (lower.contains("thunder")) return "⛈️";
            if (lower.contains("snow") || lower.contains("sleet") || lower.contains("ice") || lower.contains("blizzard")) return "🌨️";
            if (lower.contains("rain") || lower.contains("drizzle") || lower.contains("shower")) return "🌧️";
            if (lower.contains("fog") || lower.contains("mist") || lower.contains("haze")) return "🌫️";
            if (lower.contains("partly")) return "⛅";
            if (lower.contains("cloud") || lower.contains("overcast")) return "☁️";
            if (lower.contains("sun") || lower.contains("clear")) return "☀️";
        }
        return "☀️";
    }
}
