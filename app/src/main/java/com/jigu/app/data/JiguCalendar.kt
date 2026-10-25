package com.jigu.app.data

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/**
 * 节气/节日日期表（assets/data/calendar.json，由 tools/datapack/build_calendar.py 预生成）。
 * App 端只查表；范围外年份相关查询返回 null，由日推逻辑回退普通池。
 */
object JiguCalendar {

    @Volatile
    private var loaded = false

    private var solarTerms: Map<LocalDate, String> = emptyMap()
    private var festivals: Map<LocalDate, String> = emptyMap()
    private var termDates: List<LocalDate> = emptyList()

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val json = JSONObject(
                    context.assets.open("data/calendar.json").bufferedReader().readText(),
                )
                fun parse(section: String): Map<LocalDate, String> {
                    val obj = json.getJSONObject(section)
                    val out = mutableMapOf<LocalDate, String>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        runCatching { out[LocalDate.parse(k)] = obj.getString(k) }
                    }
                    return out
                }
                solarTerms = parse("solar_terms")
                festivals = parse("festivals")
                termDates = solarTerms.keys.sorted()
            }
            loaded = true
        }
    }

    /** 当天恰好是交节日 -> 节气名 */
    fun termOn(date: LocalDate): String? = solarTerms[date]

    /** 当天节日（含除夕）-> 名称 */
    fun festival(date: LocalDate): String? = festivals[date]

    /** 当前所在节气（最近一个已到的交节日）；范围外返回 null */
    fun currentTerm(date: LocalDate): String? =
        termDates.lastOrNull { !it.isAfter(date) }?.let { solarTerms[it] }
}
