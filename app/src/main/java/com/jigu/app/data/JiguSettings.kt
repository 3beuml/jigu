package com.jigu.app.data

import android.content.Context

/** 应用设置（原在 Artwork.kt 内；批11 拆除赏画体系后抽出独立，只余推送设置） */
object JiguSettings {

    private const val PREFS = "jigu_prefs"

    fun pushEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PUSH, true)

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

    private const val KEY_PUSH = "push_enabled"

    // ---------- 今日诗偏好（D-030）：题材多选，逗号分隔存储 ----------
    private const val KEY_THEMES = "pref_themes"

    fun preferredThemes(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEMES, "")?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    fun setPreferredThemes(context: Context, themes: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEMES, themes.joinToString(",")).apply()
    }
}
