package com.jigu.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jigu.app.data.PoemsDb
import com.jigu.app.ui.theme.PoemTitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 朝代固定顺序与纪年（时间轴展示用；数据里没有的朝代不显示） */
val ERA_ORDER = listOf("先秦", "汉", "魏晋", "南北朝", "隋", "唐", "五代", "宋", "元", "明", "清")
val ERA_YEARS = mapOf(
    "先秦" to "?–前221", "汉" to "前202–220", "魏晋" to "220–420", "南北朝" to "420–589",
    "隋" to "581–618", "唐" to "618–907", "五代" to "907–960", "宋" to "960–1279",
    "元" to "1271–1368", "明" to "1368–1644", "清" to "1636–1912",
)

fun formatYears(birth: Int?, death: Int?): String {
    if (birth == null) return ""
    val b = if (birth < 0) "前${-birth}" else "${birth}"
    val d = death?.let { if (it < 0) "前${-it}" else "$it" } ?: "?"
    return "$b–$d"
}

private const val PAGE = 300

/** 通用分页加载状态 */
private class Pager(val loader: (page: Int) -> List<PoemsDb.Poem>) {
    var items by mutableStateOf<List<PoemsDb.Poem>>(emptyList())
    var hasMore by mutableStateOf(true)
    var page by mutableIntStateOf(0)
}

@Composable
private fun rememberPagedPoems(key: Any?, loader: (page: Int) -> List<PoemsDb.Poem>): Pager {
    val pager = remember(key) { Pager(loader) }
    LaunchedEffect(key, pager.page) {
        withContext(Dispatchers.IO) {
            val batch = pager.loader(pager.page)
            pager.items = if (pager.page == 0) batch else pager.items + batch
            pager.hasMore = batch.size == PAGE
        }
    }
    return pager
}

@Composable
private fun PagedPoemList(pager: Pager, onOpen: (Int) -> Unit, header: (@Composable () -> Unit)? = null) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        header?.let { item { it() } }
        items(pager.items.size, key = { pager.items[it].id }) { index ->
            PoemRow(pager.items[index], onClick = { onOpen(pager.items[index].id) })
        }
        if (pager.hasMore && pager.items.isNotEmpty()) {
            item {
                TextButton(
                    onClick = { pager.page += 1 },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) { Text("加载更多") }
            }
        }
        if (pager.items.isEmpty()) {
            item {
                Text(
                    "无内容",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp, start = 20.dp),
                )
            }
        }
    }
}

/** 朝代 -> 作者列表（按生年时序，无生年者垫底） */
@Composable
fun EraAuthorsScreen(era: String, onBack: () -> Unit, onOpenAuthor: (Int) -> Unit) {
    val context = LocalContext.current
    var authors by remember(era) { mutableStateOf<List<PoemsDb.Author>>(emptyList()) }
    var hasMore by remember(era) { mutableStateOf(true) }
    var page by remember(era) { mutableIntStateOf(0) }

    LaunchedEffect(era, page) {
        withContext(Dispatchers.IO) {
            val batch = PoemsDb.get(context).authorsByEra(era, PAGE, page * PAGE)
            authors = if (page == 0) batch else authors + batch
            hasMore = batch.size == PAGE
        }
    }

    BrowseScaffold(title = era, subtitle = ERA_YEARS[era] ?: "", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(authors.size, key = { authors[it].id }) { index ->
                val a = authors[index]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAuthor(a.id) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = a.name,
                            style = PoemTitleStyle,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = formatYears(a.birthYear, a.deathYear),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${a.workCount} 篇" + a.brief.take(24).let { if (it.isNotBlank()) " ｜ $it…" else "" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }
            if (hasMore && authors.isNotEmpty()) {
                item {
                    TextButton(
                        onClick = { page += 1 },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) { Text("加载更多") }
                }
            }
        }
    }
}

/** 诗人索引：全部作者按作品量排序 */
@Composable
fun AuthorIndexScreen(onBack: () -> Unit, onOpenAuthor: (Int) -> Unit) {
    val context = LocalContext.current
    var authors by remember { mutableStateOf<List<PoemsDb.Author>>(emptyList()) }
    var hasMore by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(0) }

    LaunchedEffect(page) {
        withContext(Dispatchers.IO) {
            val batch = PoemsDb.get(context).authorIndex(PAGE, page * PAGE)
            authors = if (page == 0) batch else authors + batch
            hasMore = batch.size == PAGE
        }
    }

    BrowseScaffold(title = "诗人", subtitle = "按作品量排序", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(authors.size, key = { authors[it].id }) { index ->
                val a = authors[index]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAuthor(a.id) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = a.name,
                            style = PoemTitleStyle,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "  ${a.dynasty}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${a.workCount} 篇",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    formatYears(a.birthYear, a.deathYear).takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }
            if (hasMore && authors.isNotEmpty()) {
                item {
                    TextButton(
                        onClick = { page += 1 },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) { Text("加载更多") }
                }
            }
        }
    }
}

/** 作者主页：小传 + 全部作品 */
@Composable
fun AuthorScreen(authorId: Int, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var author by remember { mutableStateOf<PoemsDb.Author?>(null) }

    LaunchedEffect(authorId) {
        withContext(Dispatchers.IO) { author = PoemsDb.get(context).authorById(authorId) }
    }

    val a = author
    // pager 必须无条件创建（Compose 规则），author 尚未加载时先用 id 查询
    val pager = rememberPagedPoems(authorId) { page ->
        PoemsDb.get(context).poemsByAuthor(authorId, PAGE, page * PAGE)
    }
    BrowseScaffold(
        title = a?.name ?: "…",
        subtitle = a?.let {
            listOfNotNull(
                it.dynasty.takeIf { d -> d.isNotBlank() },
                formatYears(it.birthYear, it.deathYear).takeIf { y -> y.isNotBlank() },
            ).joinToString(" · ")
        } ?: "",
        onBack = onBack,
    ) {
        if (a == null) {
            Text(
                "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            PagedPoemList(pager, onOpen) {
                if (a.brief.isNotBlank()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(
                            "小传",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            a.brief,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 体裁 -> 作品列表（诗/词/曲/文） */
@Composable
fun GenreScreen(categories: List<String>, title: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagedPoems(categories.joinToString(",")) { page ->
        PoemsDb.get(context).listByGenres(categories, PAGE, page * PAGE)
    }
    BrowseScaffold(title = title, subtitle = "", onBack = onBack) {
        PagedPoemList(pager, onOpen)
    }
}

/** 浏览页公共骨架：返回 + 标题 + 副题（纪年等） */
@Composable
private fun BrowseScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
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
            Column {
                Text(
                    text = title,
                    style = PoemTitleStyle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}
