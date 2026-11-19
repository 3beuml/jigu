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

    // ---------- R4 情境（D-045）：心情多选 / 最近状态 / 天气城市 ----------
    private const val KEY_MOODS = "pref_moods"
    private const val KEY_STATUS = "pref_status"
    private const val KEY_CITY = "weather_city"

    fun moodTags(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MOODS, "")?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    fun setMoodTags(context: Context, tags: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MOODS, tags.joinToString(",")).apply()
    }

    fun lifeStatus(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "默认") ?: "默认"

    fun setLifeStatus(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATUS, value).apply()
    }

    fun weatherCity(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CITY, "") ?: ""

    fun setWeatherCity(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CITY, value.trim()).apply()
    }

    // ---------- R9 书架自选置顶（D-046）：长按卡片置顶，置顶卡排架首 ----------
    private const val KEY_PINNED = "pinned_modules"

    fun pinnedModules(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PINNED, "")?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    fun setPinnedModules(context: Context, mods: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PINNED, mods.joinToString(",")).apply()
    }
}

/** 情境映射（D-045）：心情/状态/天气 → 题材池（题材词表 30 类内取交集） */
object MoodProfiles {

    /** 心情 → 题材（多选） */
    val MOODS: List<Pair<String, List<String>>> = listOf(
        "振奋豪迈" to listOf("边塞", "爱国", "咏史怀古"),
        "闲适养心" to listOf("田园", "山水", "饮酒"),
        "思念怀人" to listOf("送别", "思乡", "相思"),
        "静夜读书" to listOf("读书", "写月", "惜时"),
        "低落自慰" to listOf("田园", "山水", "写雨"),
        "天伦之乐" to listOf("儿童", "田园", "写花"),
    )

    /** 最近状态 → 题材（单选持续偏向） */
    val STATUS: List<Pair<String, List<String>>> = listOf(
        "默认" to emptyList(),
        "备考冲刺" to listOf("读书", "惜时", "爱国"),
        "近期低落" to listOf("田园", "山水", "写雨", "饮酒"),
        "闲居养心" to listOf("田园", "山水", "饮酒", "写花"),
        "求职闯荡" to listOf("边塞", "爱国", "咏史怀古"),
        "退休颐养" to listOf("田园", "山水", "儿童", "写花"),
    )

    /** 天气码（open-meteo WMO）→ 题材 */
    fun weatherThemes(code: Int): List<String> = when (code) {
        in 0..1 -> listOf("山水", "田园")
        in 2..3, 45, 48 -> listOf("咏物", "山水")
        in 51..67, in 80..82, in 95..99 -> listOf("写雨", "田园")
        in 71..77, 85, 86 -> listOf("写雪", "梅花")
        else -> emptyList()
    }

    fun weatherLabel(code: Int): String = when (code) {
        in 0..1 -> "晴"
        in 2..3, 45, 48 -> "阴"
        in 51..67, in 80..82, in 95..99 -> "雨"
        in 71..77, 85, 86 -> "雪"
        else -> ""
    }
}
