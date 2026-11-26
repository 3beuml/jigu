package com.jigu.app.data

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * 天气服务（R4/D-045，可选联网例外）：用户在「我的」填了天气城市才联网。
 * open-meteo（免 key）：地理编码 → 当日 weather_code，缓存进 prefs（当日有效）。
 * 纯离线承诺不变：城市留空 = 一次网络请求都不发。
 */
object WeatherService {

    private const val PREFS = "jigu_prefs"
    private const val KEY_CACHE = "weather_cache"

    data class Weather(val code: Int, val city: String, val date: LocalDate)

    fun cached(context: Context): Weather? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = org.json.JSONObject(prefs.getString(KEY_CACHE, "{}") ?: "{}")
        Weather(
            code = obj.optInt("code", -1),
            city = obj.optString("city"),
            date = LocalDate.parse(obj.optString("date").ifBlank { "1970-01-01" }),
        ).takeIf { it.code >= 0 && it.city.isNotBlank() }
    }.getOrDefault(null)

    /** 当日有效天气（今日页/推送/pick 用）；过期则视为无天气（不发请求）。 */
    fun weatherToday(context: Context): Weather? =
        cached(context)?.takeIf { it.date == LocalDate.now() }

    /** 后台刷新（应用启动 / 推送 worker 调）；城市空 = 纯离线零请求。 */
    fun refreshAsync(context: Context) {
        Thread {
            runCatching { refresh(context) }
        }.start()
    }

    fun refresh(context: Context): Weather? {
        val city = JiguSettings.weatherCity(context)
        if (city.isBlank()) return null
        return runCatching {
            val geo = json(
                "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(city, "UTF-8") + "&count=1&language=zh&format=json",
            )
            val first = geo.optJSONArray("results")?.optJSONObject(0)
                ?: return null
            val lat = first.optDouble("latitude")
            val lon = first.optDouble("longitude")
            val fc = json(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=weather_code",
            )
            val code = fc.optJSONObject("current")?.optInt("weather_code", -1) ?: -1
            if (code < 0) return null
            val w = Weather(code = code, city = city, date = LocalDate.now())
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(
                    KEY_CACHE,
                    org.json.JSONObject()
                        .put("code", code)
                        .put("city", city)
                        .put("date", w.date.toString())
                        .toString(),
                )
                .apply()
            w
        }.getOrNull()
    }

    private fun json(url: String): org.json.JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "jigu-weather")
        conn.inputStream.use { inp ->
            return org.json.JSONObject(inp.bufferedReader().readText())
        }
    }
}
