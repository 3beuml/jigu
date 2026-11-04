package com.jigu.app.data

import java.time.LocalDate

/**
 * 今日一首（D-006 日推引擎 v0）：
 * 1) 当天有节日 -> 节日配诗池
 * 2) 当天恰逢交节 -> 该节气配诗池
 * 3) 其余日子 -> 精选库全池按日期稳定轮转（池序为时代打散序，D-018/P1-8：
 *    旧实现按 rowid 爬片导致连续多日全是清代；现固定种子洗牌+同时代连片修复）
 * 池内取诗用 epochDay 做确定性偏移：同一天任何时候打开都是同一首。
 */
object PoemOfDay {

    data class Result(val poem: PoemsDb.Poem, val badge: String?)

    fun pick(db: PoemsDb, today: LocalDate): Result? {
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
        val n = db.curatedCount()
        if (n <= 0) return null
        // 季节情境（D-019，对齐今日诗词思路）：每 4 天里 1 天优先当前节气配诗池，
        // 其余日子走配平后的精选池；节气池空则自然回退精选池。
        val curTerm = JiguCalendar.currentTerm(today)
        if (curTerm != null && Math.floorMod(today.toEpochDay(), 4L) == 0L) {
            db.calendarPoemIds("solar_term", curTerm).takeIf { it.isNotEmpty() }
                ?.let { ids -> db.poemById(ids[absIndex(today, ids.size)])?.let { return Result(it, curTerm) } }
        }
        val poem = db.curatedAtOffset(absIndex(today, n)) ?: return null
        // 角标仍展示当前所在节气，让普通日子也有时序感
        return Result(poem, curTerm)
    }

    private fun absIndex(date: LocalDate, size: Int): Int =
        ((date.toEpochDay() % size + size) % size).toInt()
}
