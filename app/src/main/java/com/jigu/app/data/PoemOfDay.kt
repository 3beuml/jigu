package com.jigu.app.data

import java.time.LocalDate

/**
 * 今日一首（D-006 日推引擎 v0）：
 * 1) 当天有节日 -> 节日配诗池
 * 2) 当天恰逢交节 -> 该节气配诗池
 * 3) 其余日子 -> 精选库全池按日期稳定轮转
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
        val n = db.count()
        if (n <= 0) return null
        val poem = db.poemAtOffset(absIndex(today, n)) ?: return null
        // 角标仍展示当前所在节气，让普通日子也有时序感
        return Result(poem, JiguCalendar.currentTerm(today))
    }

    private fun absIndex(date: LocalDate, size: Int): Int =
        ((date.toEpochDay() % size + size) % size).toInt()
}
