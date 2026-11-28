package com.jigu.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jigu.app.DOCK_CLEARANCE
import com.jigu.app.data.JiguCalendar
import com.jigu.app.data.PoemOfDay
import com.jigu.app.data.PoemsDb
import com.jigu.app.ui.detail.paragraphToLines
import com.jigu.app.ui.signatureWithTitle
import com.jigu.app.ui.theme.PoemBodySmallStyle
import com.jigu.app.ui.theme.PoemTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 每日一句·诗历（R3 几枝化 + D-053 月历化）：
 *  顶部月历（有记录=实心墨点、节气日=朱色描边、点格跳到当日句、可翻月），
 *  下方仍按天倒序的时间线；数据源 = PoemOfDay 日志（prefs），跨版本 id 漂移条目自然跳过。
 *
 *  月历化依据（docs/research/DAILY-REC-GLOBAL.md 建议①、DAILY-REC-CN.md 建议⑥）：
 *  «每日一条» 的留存不靠 push，靠"看得见自己过了多少天"——POTD 按日归档、日めくり每日一撕、
 *  豆瓣/单向历把日期做成物件。只动本页，首页零变化；不做 streak/补签/红点。 */
@Composable
fun PoemLogScreen(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<Pair<PoemOfDay.LogEntry, PoemsDb.Poem>>>(emptyList()) }
    /** 完整日志日期（含跨版本 id 漂移、查不到正文的条目）——月历点用它，
     *  "那天打开过"本身就是事实，不因数据重建而消失。 */
    var loggedDates by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val history = withContext(Dispatchers.IO) { PoemOfDay.history(context) }
        val loaded = withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            history.mapNotNull { e -> db.poemById(e.poemId)?.let { e to it } }
        }
        entries = loaded
        loggedDates = history.mapTo(HashSet()) { it.date }
        // 打开时先落在"最近一次记录"所在的月份，而不是空白的本月
        (history.firstOrNull() ?: loaded.firstOrNull()?.first)?.let {
            month = YearMonth.from(it.date)
        }
    }

    /** 日期 -> 时间线下标（只有能查到正文的条目可跳） */
    val indexByDate = remember(entries) {
        entries.withIndex().associate { (i, pair) -> pair.first.date to i }
    }

    // 可浏览范围：最早一条记录所在月 ~ 本月（不让人翻进无边无际的空月份）
    val thisMonth = YearMonth.now()
    val minMonth = remember(loggedDates) {
        loggedDates.minOfOrNull { YearMonth.from(it) } ?: thisMonth
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    "每日一句",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = DOCK_CLEARANCE),
            ) {
                // ① 月历（随内容滚动；点格跳到当日句）
                item(key = "month-calendar") {
                    MonthCalendar(
                        month = month,
                        loggedDates = loggedDates,
                        canGoPrev = month > minMonth,
                        canGoNext = month < thisMonth,
                        onPrevMonth = { month = month.minusMonths(1) },
                        onNextMonth = { month = month.plusMonths(1) },
                        onPickDay = { date ->
                            // 月历格子以"打开过"为准；能查到正文的才滚动定位
                            indexByDate[date]?.let { idx ->
                                scope.launch { listState.animateScrollToItem(1 + idx) }
                            }
                        },
                        recordedCount = loggedDates.size,
                    )
                }
                if (entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            // 两种情况要分开说：从没打开过 vs 打开过但数据重建后诗句查不到了
                            if (loggedDates.isEmpty()) {
                                "还没有记录。\n每天打开汲古，当日的一句会留在这里。"
                            } else {
                                "月历上的日子都打开过，但数据包更新后原诗已无法对应，时间线暂时为空。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                entries.forEachIndexed { index, (e, p) ->
                    item(key = "${e.date.toEpochDay()}_${p.id}") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(p.id) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    e.date.format(DateTimeFormatter.ofPattern("M月d日")),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                e.badge?.let {
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                MaterialTheme.shapes.small,
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                // 今日注（D-052）：回看时也能看到"那天为什么是这句"
                                e.why?.let {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            val line = p.famousLine.takeIf { it.length >= 6 }
                                ?: p.paragraphs.firstOrNull().takeIf { !it.isNullOrBlank() && it.length >= 6 }
                                ?: p.paragraphs.joinToString("").takeIf { it.isNotBlank() } ?: ""
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                paragraphToLines(line, maxSentence = 14).forEach { l ->
                                    Text(
                                        text = l,
                                        style = PoemTextStyle,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                signatureWithTitle(p),
                                style = PoemBodySmallStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    }
                }
            }
        }
    }
}

/** 月历网格：周一起算，首行前置补 null，末行补齐到 7 的倍数。
 *  抽成纯函数便于穷举验证（闰年 2 月、月首为周日等边界）。 */
internal fun monthGridCells(month: YearMonth): List<LocalDate?> {
    val leading = month.atDay(1).dayOfWeek.value - 1
    val cells = mutableListOf<LocalDate?>()
    repeat(leading) { cells.add(null) }
    for (d in 1..month.lengthOfMonth()) cells.add(month.atDay(d))
    while (cells.size % 7 != 0) cells.add(null)
    return cells
}

/** 月历主体：头部（‹ 年月 ›）+ 星期行 + 7 列网格 + 底部「已记 N 天」。
 *  设计约束（调研「不要抄」清单）：不叫 streak、无补签/冻结/红点/连续天数压力。 */
@Composable
private fun MonthCalendar(
    month: YearMonth,
    loggedDates: Set<LocalDate>,
    canGoPrev: Boolean,
    canGoNext: Boolean,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onPickDay: (LocalDate) -> Unit,
    recordedCount: Int,
) {
    val today = LocalDate.now()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        // 头部：翻月
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevMonth, enabled = canGoPrev, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "上个月",
                    tint = if (canGoPrev) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "${month.year}年${month.monthValue}月",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onNextMonth,
                enabled = canGoNext,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = "下个月",
                    tint = if (canGoNext) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // 星期行（周一起）
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                Text(
                    text = w,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // 7 列网格：用等宽 Row 而不是 LazyVerticalGrid（避免与外层 LazyColumn 嵌套滚动冲突）
        monthGridCells(month).chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (date == null) {
                            Spacer(Modifier.size(38.dp))
                        } else {
                            DayCell(
                                date = date,
                                hasEntry = date in loggedDates,
                                isTerm = JiguCalendar.termOn(date) != null,
                                isToday = date == today,
                                onClick = { if (date in loggedDates) onPickDay(date) },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (recordedCount > 0) "已记 $recordedCount 天" else "还没有记录",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    }
}

/** 单日格：数字 + 有记录时的实心墨点；节气日加朱色圆环；今天用淡底点出。 */
@Composable
private fun DayCell(
    date: LocalDate,
    hasEntry: Boolean,
    isTerm: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .then(if (hasEntry) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (isTerm) {
            // 节气：朱色细圆环（沿用主题 primary，随节气换肤）
            Box(
                Modifier
                    .size(34.dp)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
            )
        }
        if (isToday) {
            Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    hasEntry -> MaterialTheme.colorScheme.onBackground
                    isToday -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
                },
            )
            Spacer(Modifier.height(2.dp))
            if (hasEntry) {
                Box(
                    Modifier
                        .size(3.dp)
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f), CircleShape),
                )
            } else {
                Spacer(Modifier.size(3.dp))
            }
        }
    }
}
