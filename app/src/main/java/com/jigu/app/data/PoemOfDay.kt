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
 */
object PoemOfDay {

    data class Result(val poem: PoemsDb.Poem, val badge: String?)

    fun pick(context: Context, db: PoemsDb, today: LocalDate): Result? {
        val swap = context.getSharedPreferences("jigu_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("swap_${today.toEpochDay()}", 0)
        // 今日诗偏好（D-030）：偏好题材非空时，精选池换成"偏好题材内"的同构池
        // （洗牌顺位/指针机制不变，池空自然回退全池）；节日/节气配诗池不受偏好影响——时序感优先。
        val themes = JiguSettings.preferredThemes(context)
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
        val poem = atOffset(absIndex(today, n)) ?: return null
        // 角标仍展示当前所在节气，让普通日子也有时序感
        return Result(poem, curTerm)
    }

    private fun absIndex(date: LocalDate, size: Int): Int =
        ((date.toEpochDay() % size + size) % size).toInt()
}
