package com.jigu.app.data

import android.content.Context
import java.time.LocalDate

/**
 * 今日一首（D-006 日推引擎 v0）：
 * 1) 当天有节日 -> 节日配诗池
 * 2) 当天恰逢交节 -> 该节气配诗池
 * 3) 其余日子 -> 精选库全池按日期稳定轮转（池序为时代打散序，D-018/P1-8：
 *    旧实现按 rowid 爬片导致连续多日全是清代；现固定种子洗牌+同时代连片修复）
 * 池内取诗用 epochDay 做确定性偏移：同一天任何时候打开都是同一首。
 * 「换一首」偏移（P0-6 修复）：swap_$day 持久化计数 >0 时，当日一切读取都落到
 * 精选池的偏移位——此前偏移只写不读（死代码），换一首在切页/重开后回退。
 * R3 几枝化（D-044）：pick 统一记"每日一句"日志（诗历时间线数据源）；
 * 关注诗人轻加权——每 5 天 1 天优先关注作者的名气作，主体仍是全库池。
 */
object PoemOfDay {

    data class Result(val poem: PoemsDb.Poem, val badge: String?)

    /** 每日一句日志条目（诗历时间线） */
    data class LogEntry(val date: LocalDate, val poemId: Int, val badge: String?)

    private const val LOG_KEY = "daily_log"
    private const val LOG_CAP = 730

    fun pick(context: Context, db: PoemsDb, today: LocalDate): Result? {
        val r = computePick(context, db, today)
        r?.let { logShown(context, today, it) }
        return r
    }

    private fun computePick(context: Context, db: PoemsDb, today: LocalDate): Result? {
        val swap = context.getSharedPreferences("jigu_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("swap_${today.toEpochDay()}", 0)
        // 今日诗偏好（D-030）+ R4 情境（D-045）：题材 = 偏好 ∪ 心情 ∪ 最近状态，
        // 并集后走同一同构池（洗牌顺位/指针机制不变，池空自然回退全池）；
        // 节日/节气配诗池不受偏好影响——时序感优先。
        val themes = (
            JiguSettings.preferredThemes(context) +
                JiguSettings.moodTags(context) +
                (MoodProfiles.STATUS.find { it.first == JiguSettings.lifeStatus(context) }?.second ?: emptyList())
            ).distinct()
        val count = {
            if (themes.isEmpty()) db.curatedCount()
            else db.curatedThemeCount(themes).takeIf { it > 0 } ?: db.curatedCount()
        }
        val atOffset = { off: Int ->
            if (themes.isEmpty()) db.curatedAtOffset(off)
            else db.curatedThemeAtOffset(off, themes) ?: db.curatedAtOffset(off)
        }
        if (swap > 0) {
            val n = count()
            if (n > 0) {
                val off = Math.floorMod(today.toEpochDay() + swap, n.toLong()).toInt()
                atOffset(off)?.let { return Result(it, JiguCalendar.currentTerm(today)) }
            }
        }
        val festival = JiguCalendar.festival(today)
        if (festival != null) {
            db.calendarPoemIds("festival", festival).takeIf { it.isNotEmpty() }
                ?.let { ids -> db.poemById(ids[absIndex(today, ids.size)])?.let { return Result(it, festival) } }
        }
        val termToday = JiguCalendar.termOn(today)
        if (termToday != null) {
            db.calendarPoemIds("solar_term", termToday).takeIf { it.isNotEmpty() }
                ?.let { ids -> db.poemById(ids[absIndex(today, ids.size)])?.let { return Result(it, termToday) } }
        }
        val n = count()
        if (n <= 0) return null
        // 季节情境（D-019，对齐今日诗词思路）：每 4 天里 1 天优先当前节气配诗池，
        // 其余日子走配平后的精选池；节气池空则自然回退精选池。
        val curTerm = JiguCalendar.currentTerm(today)
        if (curTerm != null && Math.floorMod(today.toEpochDay(), 4L) == 0L) {
            db.calendarPoemIds("solar_term", curTerm).takeIf { it.isNotEmpty() }
                ?.let { ids -> db.poemById(ids[absIndex(today, ids.size)])?.let { return Result(it, curTerm) } }
        }
        // 关注诗人轻加权（D-043 R3，用户定案"适度加权，不要太大"）：每 5 天 1 天优先
        // 关注作者的名气作（精选池内），其余日子仍是全库——主体不被关注人挤占。
        // 天气情境槽（R4/D-045，可选联网）：城市已配置且当日天气在手 → 每 3 天里 1 天走天气题材池；
        // 角标沿用节气，无节气时用天气（今日·雨/晴/雪）。
        val weather = WeatherService.weatherToday(context)
        val weatherThemes = weather?.let { MoodProfiles.weatherThemes(it.code) }.orEmpty()
        if (weather != null && weatherThemes.isNotEmpty() && Math.floorMod(today.toEpochDay(), 3L) == 1L) {
            db.curatedThemeOrder(weatherThemes).takeIf { it.isNotEmpty() }
                ?.let { ids ->
                    db.poemById(ids[absIndex(today, ids.size)])?.let {
                        return Result(it, curTerm ?: MoodProfiles.weatherLabel(weather.code))
                    }
                }
        }
        val followed = db.followedAuthorIds()
        if (followed.isNotEmpty() && Math.floorMod(today.toEpochDay(), 5L) == 2L) {
            db.curatedByFollowed(followed).takeIf { it.isNotEmpty() }
                ?.let { list -> return Result(list[absIndex(today, list.size)], curTerm) }
        }
        val poem = atOffset(absIndex(today, n)) ?: return null
        // 角标仍展示当前所在节气，让普通日子也有时序感
        return Result(poem, curTerm)
    }

    private fun absIndex(date: LocalDate, size: Int): Int =
        ((date.toEpochDay() % size + size) % size).toInt()

    /** 每日一句日志（诗历）：prefs JSON {epochDay: {id, badge}}，保留 730 天。
     *  每次覆盖当日条目（换一首后记最后展示的一句）。 */
    fun logShown(context: Context, today: LocalDate, r: Result) {
        runCatching {
            val prefs = context.getSharedPreferences("jigu_prefs", android.content.Context.MODE_PRIVATE)
            val obj = org.json.JSONObject(prefs.getString(LOG_KEY, "{}") ?: "{}")
            val entry = org.json.JSONObject().put("id", r.poem.id)
            r.badge?.let { entry.put("badge", it) }
            obj.put(today.toEpochDay().toString(), entry)
            while (obj.length() > LOG_CAP) {
                val oldest = obj.keys().asSequence().minByOrNull { it.toLongOrNull() ?: 0L } ?: break
                obj.remove(oldest)
            }
            prefs.edit().putString(LOG_KEY, obj.toString()).apply()
        }
    }

    /** 诗历时间线：按天倒序；poem_id 跨版本漂移时该条目自然失联（读取时跳过）。 */
    fun history(context: Context): List<LogEntry> {
        return runCatching {
            val prefs = context.getSharedPreferences("jigu_prefs", android.content.Context.MODE_PRIVATE)
            val obj = org.json.JSONObject(prefs.getString(LOG_KEY, "{}") ?: "{}")
            obj.keys().asSequence()
                .mapNotNull { key ->
                    val day = key.toLongOrNull() ?: return@mapNotNull null
                    val e = obj.optJSONObject(key) ?: return@mapNotNull null
                    val id = e.optInt("id", -1)
                    if (id < 0) return@mapNotNull null
                    LogEntry(LocalDate.ofEpochDay(day), id, e.optString("badge").ifBlank { null })
                }
                .sortedByDescending { it.date.toEpochDay() }
                .toList()
        }.getOrDefault(emptyList())
    }
}
