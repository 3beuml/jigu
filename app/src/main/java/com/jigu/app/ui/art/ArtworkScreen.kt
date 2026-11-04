package com.jigu.app.ui.art

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jigu.app.data.ArtworkRepo
import com.jigu.app.data.JiguCalendar
import com.jigu.app.data.JiguSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 节气序（NO. 018 式微字用），与 build_calendar 的节气序一致 */
private val TERM_ORDER = listOf(
    "立春", "雨水", "惊蛰", "春分", "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
    "小暑", "大暑", "立秋", "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪",
    "大雪", "冬至", "小寒", "大寒",
)

private val CN_DIGITS = mapOf('0' to '〇', '1' to '一', '2' to '二', '3' to '三', '4' to '四', '5' to '五', '6' to '六', '7' to '七', '8' to '八', '9' to '九')

private fun cnYear(year: Int): String = buildString {
    append('二'); append('〇')
    year.toString().drop(2).forEach { append(CN_DIGITS[it] ?: it) }
}

/** 全屏鉴赏页（D-019 二联式）：上原作羽化 + 竖排题款，下「画之蒸馏」+ 微字 + 换一幅。
 *  seed = poemId + 池内序——同一首诗永远同一画同一蒸馏，不同诗同画也各异其构。 */
@Composable
fun ArtworkScreen(poemId: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val artOn = remember { JiguSettings.artEnabled(context) }
    androidx.compose.runtime.LaunchedEffect(artOn, dark) {
        if (!artOn || dark) onBack() // 画意关/深色模式：与列表同护栏，不留空页
    }
    var idx by rememberSaveable { mutableStateOf(0) }
    var art by remember { mutableStateOf<ArtworkRepo.Artwork?>(null) }
    var orig by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var distill by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val term = remember { JiguCalendar.currentTerm(LocalDate.now()) }
    val termNo = term?.let { TERM_ORDER.indexOf(it) }?.takeIf { it >= 0 }?.plus(1)

    LaunchedEffect(poemId, idx) {
        withContext(Dispatchers.IO) {
            val seed = poemId + idx
            val a = ArtworkRepo.forTerm(context, term, seed)
            art = a
            orig = a?.let { ArtworkRepo.decode(context, it) }
            distill = a?.let { ArtworkRepo.distill(context, it, seed) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(PaperInk.PAPER))
            .statusBarsPadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                text = "赏画 · ${term ?: ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8A8272),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(48.dp))
        }

        // 上联：原作羽化 + 竖排题款
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val a = art
            val bmp = orig
            if (a == null || bmp == null) {
                Text("…", color = Color(0xFF8A8272))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = a.displayTitle(),
                        modifier = Modifier
                            .fillMaxWidth(0.64f)
                            .aspectRatio(3f / 4f)
                            .paperFeather(0.16f),
                        contentScale = ContentScale.FillBounds,
                    )
                    Spacer(Modifier.width(14.dp))
                    // 竖排题款：题名 + 朝代作者（自上而下，右起）
                    Column(
                        modifier = Modifier.fillMaxHeight(0.62f),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        a.displayTitle().take(10).forEach { ch ->
                            Text(ch.toString(), fontSize = 20.sp, lineHeight = 26.sp,
                                color = Color(0xFF23211C))
                        }
                        Spacer(Modifier.height(10.dp))
                        a.displayArtist().take(10).forEach { ch ->
                            Text(ch.toString(), fontSize = 11.sp, lineHeight = 17.sp,
                                color = Color(0xFF5C574C))
                        }
                    }
                }
            }
        }

        Text(
            text = art?.credit ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A8272),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider(
            color = Color(0x33C6BEAC),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )

        // 下联：画之蒸馏 + 微字 + 换一幅
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val d = distill
            if (d != null) {
                Image(
                    bitmap = d.asImageBitmap(),
                    contentDescription = "画之蒸馏",
                    modifier = Modifier
                        .width(86.dp)
                        .height(115.dp)
                        .paperFeather(0.10f),
                    contentScale = ContentScale.FillBounds,
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "NO. " + (termNo ?: 0).toString().padStart(3, '0'),
                    fontSize = 12.sp, letterSpacing = 2.sp, color = Color(0xFF23211C),
                )
                Text(
                    text = "${term ?: ""} · ${cnYear(LocalDate.now().year)}",
                    fontSize = 11.sp, color = Color(0xFF8A8272),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = art?.displayTitle() ?: "",
                    fontSize = 14.sp, letterSpacing = 2.sp, color = Color(0xFF5C574C),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            TextButton(onClick = { idx++ }) {
                Text("换一幅 ↻", fontSize = 13.sp, color = Color(0xFF9E2F26))
            }
        }
    }
}
