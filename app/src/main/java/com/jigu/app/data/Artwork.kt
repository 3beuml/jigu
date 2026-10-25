package com.jigu.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/** 节气配画（assets/art/，由 tools/artwork/fetch_artworks.py 从克利夫兰艺术博物馆 CC0 抓取） */
object ArtworkRepo {

    data class Artwork(
        val term: String,
        val file: String,
        val title: String,
        val artist: String,
        val date: String,
        val credit: String,
    )

    @Volatile
    private var loaded = false
    private var byTerm: Map<String, Artwork> = emptyMap()

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val arr = org.json.JSONArray(
                    context.assets.open("art/manifest.json").bufferedReader().readText(),
                )
                val out = mutableMapOf<String, Artwork>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val a = Artwork(
                        term = o.getString("term"),
                        file = o.getString("file"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        date = o.optString("date"),
                        credit = o.optString("credit"),
                    )
                    out[a.term] = a
                }
                byTerm = out
            }
            loaded = true
        }
    }

    fun forTerm(context: Context, term: String?): Artwork? {
        load(context)
        return term?.let { byTerm[it] }
    }

    fun decode(context: Context, artwork: Artwork): Bitmap? = runCatching {
        BitmapFactory.decodeStream(context.assets.open("art/${artwork.file}"))
    }.getOrNull()
}

/** 轻量设置：画意开关、推送等用户偏好 */
object JiguSettings {

    private const val PREFS = "jigu_prefs"
    private const val KEY_ART = "art_enabled"

    fun artEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ART, true)

    fun setArtEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ART, value).apply()
    }

    private const val KEY_PUSH = "push_enabled"

    fun pushEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PUSH, true)

    fun setPushEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PUSH, value).apply()
    }

    fun pushHour(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("push_hour", 7)

    fun pushMinute(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("push_minute", 30)

    fun setPushTime(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("push_hour", hour).putInt("push_minute", minute).apply()
    }
}
