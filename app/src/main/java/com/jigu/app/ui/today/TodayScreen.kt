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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val line = poem.famousLine.ifBlank {
        poem.paragraphs.firstOrNull().orEmpty()
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
                // 渲染诗卡并唤起系统分享
                scope.launch {
                    val card = withContext(Dispatchers.IO) {
                        runCatching { PoemCard.render(context, poem) }.getOrNull()
                    }
                    card?.let { PoemCard.share(context, it) }
                }
            }) { Text("做卡片", fontSize = 14.sp) }
            TextButton(onClick = { /* 下一增量：TTS 朗诵 */ }) { Text("朗读", fontSize = 14.sp) }
        }
    }
}

fun attribution(poem: PoemsDb.Poem): String {
    val era = poem.dynasty.takeIf { it.isNotBlank() }
    val who = poem.author.ifBlank { "佚名" }
    val head = listOfNotNull(era, who).joinToString(" · ")
    return "$head《${poem.title}》"
}
