package com.jigu.app.ui.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jigu.app.data.ArtworkRepo
import com.jigu.app.data.JiguCalendar
import com.jigu.app.data.JiguSettings
import com.jigu.app.data.PoemsDb
import com.jigu.app.ui.theme.PoemTitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 详情页：原文 / 诗人小传 / 意象与标签 / 相关诗词；横竖排切换（D-007 雅言取材） */
@Composable
fun DetailScreen(id: Int, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var poem by remember { mutableStateOf<PoemsDb.Poem?>(null) }
    var related by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }
    var vertical by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            db.poemById(id)?.let {
                poem = it
                related = db.related(it)
            }
        }
    }

    val p = poem
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = if (vertical) "竖排" else "横排",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton2(text = if (vertical) "横排" else "竖排") { vertical = !vertical }
        }

        if (p == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = p.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = attributionLine(p),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 20.dp),
            )

            if (vertical) {
                VerticalPoem(p.paragraphs)
            } else {
                PoemBody(p.paragraphs, p.category)
            }
            Spacer(Modifier.height(28.dp))

            // 意象 / 标签
            val chips = (p.imagery + p.tags).distinct()
            if (chips.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chips.forEach { chip ->
                        Text(
                            text = chip,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // 诗人小传（公版）
            if (p.brief.isNotBlank()) {
                SectionTitle("诗人小传")
                Text(
                    text = p.brief,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }

            // 公版评点（幽梦影等自带；正式评点库阶段 4+）
            if (p.notes.isNotEmpty()) {
                SectionTitle("评点")
                p.notes.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            // 赏画（D 手法：默认收起，展开才见画；深色模式隐藏）
            val artEnabled = JiguSettings.artEnabled(context)
            val dArt = if (artEnabled && !isSystemInDarkTheme()) {
                ArtworkRepo.forTerm(context, JiguCalendar.currentTerm(java.time.LocalDate.now()))
            } else null
            if (dArt != null) {
                var artBitmap by remember(dArt.file) {
                    mutableStateOf<android.graphics.Bitmap?>(null)
                }
                LaunchedEffect(dArt.file) {
                    artBitmap = withContext(Dispatchers.IO) { ArtworkRepo.decode(context, dArt) }
                }
                var artOpen by remember(dArt.file) { mutableStateOf(false) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { artOpen = !artOpen }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "赏画 · ${dArt.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (artOpen) "收起" else "展开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (artOpen) {
                    artBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "节气配画",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .alpha(0.9f),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            text = "${dArt.artist}《${dArt.title}》 ${dArt.date} ｜ ${dArt.credit}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                        )
                    }
                }
            }

            // 相关诗词
            if (related.isNotEmpty()) {
                SectionTitle("相关诗词")
                related.forEach { rp ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(rp.id) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = "《${rp.title}》",
                            style = PoemTitleStyle,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "${rp.author} ｜ " + rp.body.replace('\n', ' ').take(28) + "…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun TextButton2(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private fun attributionLine(p: PoemsDb.Poem): String {
    val parts = buildList {
        p.era.ifBlank { p.dynasty }.takeIf { it.isNotBlank() }?.let { add(it) }
        add(p.author.ifBlank { "佚名" })
        p.rhythmic.takeIf { it.isNotBlank() }?.let { add("〔$it〕") }
        p.genre.takeIf { it.isNotBlank() }?.let { add("[$it]") }
        p.section.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

/** 竖排（v0）：段落分列、列内自上而下；用 RTL 布局方向实现段与列自右向左。标点暂随字竖排 */
@Composable
private fun VerticalPoem(paragraphs: List<String>) {
    val charsPerCol = 14
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            paragraphs.forEach { para ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    para.chunked(charsPerCol).forEach { colText ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            colText.forEach { ch ->
                                Text(
                                    text = ch.toString(),
                                    fontSize = 24.sp,
                                    lineHeight = 28.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
