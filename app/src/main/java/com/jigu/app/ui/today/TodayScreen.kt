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
import androidx.compose.ui.graphics.ColorFilter
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
import com.jigu.app.ui.art.PaperInk
import com.jigu.app.ui.art.paperFeather
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
fun TodayScreen(onOpen: (Int) -> Unit, onCard: (Int) -> Unit, onArtwork: (Int) -> Unit = {}) {
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

    // 角隅画（D-019 photo-revival 法则）：淡墨融合 + 羽化入纸 + 竖排小字注；点击进鉴赏页；
    // 深色模式隐藏；画意开关可全关；护栏"文字区净空"：总字数 >64（长文/长调）不配画
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
                contentDescription = "节气配画（点击鉴赏）",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 84.dp)
                    .size(width = 144.dp, height = 192.dp)
                    .paperFeather(0.20f)
                    .clickable { onArtwork(poem.id) },
                contentScale = ContentScale.Crop,
                colorFilter = PaperInk.composeColorFilter(PaperInk.INK_SOFT),
            )
            // 竖排小字注（photo-revival 的 tiny caption）
            Text(
                text = "《${artwork?.displayTitle()}》${artwork?.displayArtist() ?: ""}".take(14),
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 158.dp, bottom = 96.dp),
            )
        }
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
                maxLines = 2,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth(),
            )
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
        }
        }
    }
}

fun attribution(poem: PoemsDb.Poem): String = signatureWithTitle(poem)
