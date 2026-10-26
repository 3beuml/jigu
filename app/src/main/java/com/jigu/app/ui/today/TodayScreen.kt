package com.jigu.app.ui.today

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jigu.app.data.ArtworkRepo
import com.jigu.app.data.JiguCalendar
import com.jigu.app.data.JiguSettings
import com.jigu.app.data.PoemOfDay
import com.jigu.app.data.PoemsDb
import com.jigu.app.ui.share.PoemCard
import com.jigu.app.ui.theme.PoemTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 今日页（D-008 纯沉浸 + 名句制）：亮出一句最有名的，其余藏进详情。
 * 显示优先级：名句表 -> 首句；点击任意处进详情。
 */
@Composable
fun TodayScreen(onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<PoemOfDay.Result?>(null) }
    var fav by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            val r = PoemOfDay.pick(db, LocalDate.now())
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

    // 角隅山水：当前节气的画，右下角淡出（A 手法）；深色模式隐藏；画意开关可全关；
    // 护栏"文字区净空"：总字数 >64（长文/长调）不配画，避免压字
    val artEnabled = JiguSettings.artEnabled(context)
    val shortPoem = poem.paragraphs.sumOf { it.length } <= 64
    val artwork = if (artEnabled && !isSystemInDarkTheme() && shortPoem) {
        ArtworkRepo.forTerm(context, badge, poem.id)
    } else null
    var artBitmap by remember(artwork?.file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artwork?.file) {
        artBitmap = artwork?.let {
            withContext(Dispatchers.IO) { ArtworkRepo.decode(context, it) }
        }?.asImageBitmap()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val bmp = artBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "节气配画",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 72.dp)
                    .size(width = 168.dp, height = 224.dp)
                    .alpha(0.32f),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 28.dp),
        ) {
        // 顶部：左日期，右节气/节日角标
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
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
            Text(
                text = line,
                style = PoemTextStyle,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = attribution(poem),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // 底部：三个安静的出口（朗读/卡片为占位）
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
            TextButton(onClick = {
                // 渲染诗卡（带节气配画）并唤起系统分享
                scope.launch {
                    val art = ArtworkRepo.forTerm(context, badge)
                    val card = withContext(Dispatchers.IO) {
                        runCatching { PoemCard.render(context, poem, art) }.getOrNull()
                    }
                    card?.let { PoemCard.share(context, it) }
                }
            }) { Text("做卡片", fontSize = 14.sp) }
            TextButton(onClick = {
                // 换一首：当日精选池内顺位后移，重开 App 不变
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val db = PoemsDb.get(context)
                        val prefs = context.getSharedPreferences(
                            "jigu_prefs", android.content.Context.MODE_PRIVATE,
                        )
                        val day = LocalDate.now().toEpochDay()
                        val key = "swap_$day"
                        val next = prefs.getInt(key, 0) + 1
                        prefs.edit().putInt(key, next).apply()
                        val n = db.curatedCount()
                        if (n > 0) {
                            val off = (((day + next) % n + n) % n).toInt()
                            db.curatedAtOffset(off)?.let { p ->
                                result = PoemOfDay.Result(p, JiguCalendar.currentTerm(LocalDate.now()))
                                fav = db.isFavorite(p.id)
                            }
                        }
                    }
                }
            }) { Text("换一首", fontSize = 14.sp) }
            TextButton(onClick = { /* 下一增量：TTS 朗诵 */ }) { Text("朗读", fontSize = 14.sp) }
        }
        }
    }
}

fun attribution(poem: PoemsDb.Poem): String {
    val era = poem.dynasty.takeIf { it.isNotBlank() }
    val who = poem.author.ifBlank { "佚名" }
    val head = listOfNotNull(era, who).joinToString(" · ")
    return "$head《${poem.title}》"
}
