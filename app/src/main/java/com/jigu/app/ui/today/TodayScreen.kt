package com.jigu.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jigu.app.data.JiguCalendar
import com.jigu.app.data.JiguSettings
import com.jigu.app.data.PoemOfDay
import com.jigu.app.data.PoemsDb
import com.jigu.app.ui.signatureWithTitle
import com.jigu.app.ui.theme.PoemTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 今日页（D-008 纯沉浸 + 名句制）：亮出一句最有名的，其余藏进详情。
 * 显示优先级：名句表 -> 首句；点击任意处进详情。
 * 朗读（TTS）用户定案不做（D-013），按钮已移除。
 * 角隅画（D-019）：淡墨融合 + 羽化入纸 + 竖排小字注，点击进全屏鉴赏页。
 */
@Composable
fun TodayScreen(
    onOpen: (Int) -> Unit,
    onCard: (Int) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenLog: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<PoemOfDay.Result?>(null) }
    var fav by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            val r = PoemOfDay.pick(context, db, LocalDate.now())
            result = r
            r?.let { fav = db.isFavorite(it.poem.id) }
        }
    }

    val r = result
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val poem = r.poem
    val badge = r.badge
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日"))
    val line = poem.famousLine.takeIf { it.length >= 6 }
        ?: poem.paragraphs.firstOrNull().takeIf { !it.isNullOrBlank() && it.length >= 6 }
        ?: poem.paragraphs.joinToString("").takeIf { it.isNotBlank() }
        ?: ""

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // dock 只住三 tab（D-025 修订）：今日页静态无滚动，内容整体让出胶囊高度，
                // 底部动作行（收藏/做卡片/换一首）不被压
                .padding(start = 28.dp, end = 28.dp, bottom = com.jigu.app.DOCK_CLEARANCE),
        ) {
        // 顶部：日期 + 节气/节日角标相邻排布（收紧两者间的大留白，P2-4）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = today,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!badge.isNullOrBlank()) {
                Text(
                    text = "今日 · $badge",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // R3 搜索前置 + 诗历入口：右上角两枚安静的小图标，不破几枝式留白
            IconButton(onClick = onOpenSearch, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onOpenLog, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.DateRange,
                    contentDescription = "每日一句诗历",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 中部：名句（点击进详情看全篇）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable { onOpen(poem.id) },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 今日卡逐联分行（P0-7）：摘句按句读组建行，与详情页排版同构；不再整段流排
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                com.jigu.app.ui.detail.paragraphToLines(line, maxSentence = 14).forEach { l ->
                    Text(
                        text = l,
                        style = PoemTextStyle,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = attribution(poem),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth(),
            )
            // 今日注（D-052）：一行极淡小字说明"为什么是今天"，
            // 让日推从随机感变成被编排的一天（借鉴 Poem-a-Day 编辑手记/单向历宜忌）。
            r.why?.takeIf { it.isNotBlank() }?.let { why ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = why,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 底部：安静的出口（朗读已移除，见 D-013）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = {
                fav = PoemsDb.get(context).toggleFavorite(poem.id)
            }) {
                Icon(
                    if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("收藏", fontSize = 14.sp)
            }
            TextButton(onClick = { onCard(poem.id) }) { Text("做卡片", fontSize = 14.sp) }
            TextButton(onClick = {
                // 换一首：当日精选池内顺位后移，持久化（P0-6：偏移现在被 pick 读取，重开/切页不变）
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val db = PoemsDb.get(context)
                        val prefs = context.getSharedPreferences(
                            "jigu_prefs", android.content.Context.MODE_PRIVATE,
                        )
                        val key = "swap_${LocalDate.now().toEpochDay()}"
                        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
                        val r = PoemOfDay.pick(context, db, LocalDate.now())
                        r?.let {
                            result = it
                            fav = db.isFavorite(it.poem.id)
                        }
                    }
                }
            }) { Text("换一首", fontSize = 14.sp) }
        }
        }
    }
}

fun attribution(poem: PoemsDb.Poem): String = signatureWithTitle(poem)
