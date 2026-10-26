package com.jigu.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jigu.app.data.PoemsDb
import com.jigu.app.ui.theme.PoemTitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 书架（选本层）：真正的"书"。
 *  顺序按书的「内容时代」（用户定案：内容优先，非成书年代）——曹操虽是汉魏选集但内容最古的依次往前。
 *  era 仅用于排序展示，不进库。 */
private data class BookModule(val title: String, val route: String, val brief: String, val era: Int)

private val BOOK_MODULES = listOf(
    BookModule("诗经", "shijing", "风雅颂 · 先秦歌谣", 0),
    BookModule("楚辞", "chuci", "骚体 · 浪漫之源", 1),
    BookModule("论语", "lunyu", "语录即文章", 2),
    BookModule("孟子", "sishu", "雄辩滔滔", 3),
    BookModule("曹操诗集", "han_wei", "建安风骨", 4),
    BookModule("五代词", "wudai_ci", "花间集 · 南唐二主", 5),
    BookModule("唐诗三百首", "tag:唐诗三百首", "家塾课本 · 熟读成诵", 6),
    BookModule("千家诗", "qianjiashi", "分体选本 · 唐宋名篇", 7),
    BookModule("古文观止", "guwen", "散文正宗 · 十二卷", 8),
    BookModule("幽梦影", "youmengying", "清言小品 · 带评点", 9),
    BookModule("蒙学", "mengxue", "声律启蒙 · 幼学琼林等八种", 10),
    BookModule("纳兰词", "qing_ci", "清词第一", 11),
).sortedBy { it.era }

/** 书库：搜索 + 五维浏览（书架 / 朝代 / 诗人 / 体裁 / 主题） */
@Composable
fun LibraryScreen(
    onOpenModule: (String) -> Unit,
    onOpenEra: (String) -> Unit,
    onOpenAuthors: () -> Unit,
    onOpenAuthor: (Int) -> Unit,
    onOpenType: (String) -> Unit,
    onOpenTheme: (String) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var dim by rememberSaveable { mutableIntStateOf(0) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var eraCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var typeCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var themeCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var tagCount by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            counts = db.categoryCounts()
            eraCounts = db.eraCounts()
            typeCounts = db.typeCounts()
            themeCounts = db.themeCounts()
            tagCount = db.tagCount("唐诗三百首")
        }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) results = emptyList()
        else withContext(Dispatchers.IO) { results = PoemsDb.get(context).search(query) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜诗句、题目、作者（如：月落乌啼）") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(10.dp))

        // 维度切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("书架", "朝代", "诗人", "体裁", "主题").forEachIndexed { index, label ->
                DimensionChip(label = label, selected = dim == index && query.isBlank()) {
                    query = ""
                    dim = index
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (query.isNotBlank()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results.size, key = { results[it].id }) { index ->
                    PoemRow(results[index], onClick = { onOpen(results[index].id) })
                }
                if (results.isEmpty()) {
                    item {
                        Text(
                            text = "无结果",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 32.dp),
                        )
                    }
                }
            }
            return@Column
        }

        when (dim) {
            0 -> ShelfGrid(onOpenModule)
            1 -> EraDimContent(eraCounts, onOpenEra)
            2 -> AuthorDimContent(onOpenAuthors)
            3 -> TypeDimContent(typeCounts, onOpenType)
            4 -> ThemeDimContent(themeCounts, onOpenTheme)
        }
    }
}

@Composable
private fun DimensionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 书架：9 本选本 */
@Composable
private fun ShelfGrid(onOpenModule: (String) -> Unit) {
    val context = LocalContext.current
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var tagCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            counts = db.categoryCounts()
            tagCount = db.tagCount("唐诗三百首")
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        items(BOOK_MODULES, key = { it.route }) { book ->
            val count = if (book.route.startsWith("tag:")) tagCount else counts[book.route] ?: 0
            ModuleCard(title = book.title, brief = book.brief, count = count) { onOpenModule(book.route) }
        }
    }
}

/** 朝代维度：时间轴列表 */
@Composable
private fun EraDimContent(counts: Map<String, Int>, onOpenEra: (String) -> Unit) {
    val eraCounts = counts.filterKeys { it in ERA_ORDER }
    val eras = ERA_ORDER.filter { (eraCounts[it] ?: 0) > 0 }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(eras.size, key = { eras[it] }) { index ->
            val era = eras[index]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenEra(era) }
                    .padding(vertical = 16.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = era,
                        style = PoemTitleStyle.copy(
                            fontSize = MaterialTheme.typography.titleLarge.fontSize,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = ERA_YEARS[era] ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${eraCounts[era] ?: 0} 篇",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** 诗人维度：入口说明 + 索引入口 */
@Composable
private fun AuthorDimContent(onOpenAuthors: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        val briefs = listOf(
            "作品量最多的作者排在最前——名家用量一目了然",
            "点进作者可见小传与全部作品",
            "想按时代看人？请用「朝代」维度",
        )
        briefs.forEach {
            Text(
                text = "· $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAuthors),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "进入诗人索引",
                    style = PoemTitleStyle,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "全部作者 · 按作品量排序",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 体裁维度：结构化分类卡片（五言绝句/七言律诗/词/文…，按存量排序） */
@Composable
private fun TypeDimContent(typeCounts: Map<String, Int>, onOpenType: (String) -> Unit) {
    val types = typeCounts.entries.sortedByDescending { it.value }.toList()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        items(types.size, key = { types[it].key }) { index ->
            val (name, count) = types[index]
            ModuleCard(title = name, brief = "", count = count) { onOpenType(name) }
        }
    }
}

/** 主题维度：题材分类卡片（咏史/田园/边塞/送别…，情感门类） */
@Composable
private fun ThemeDimContent(themeCounts: Map<String, Int>, onOpenTheme: (String) -> Unit) {
    val themes = themeCounts.entries.sortedByDescending { it.value }.toList()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        items(themes.size, key = { themes[it].key }) { index ->
            val (name, count) = themes[index]
            ModuleCard(title = name, brief = "题材分类", count = count) { onOpenTheme(name) }
        }
    }
}

@Composable
private fun ModuleCard(title: String, brief: String, count: Int, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = PoemTitleStyle,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = brief,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$count 篇",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 书架模块浏览页：key 为分类名或 "tag:标签名"，分页加载 */
@Composable
fun ModuleScreen(key: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val tagMode = key.startsWith("tag:")
    val category = if (tagMode) "tang_poem" else key
    val tag = if (tagMode) key.removePrefix("tag:") else null
    val label = if (tagMode) tag ?: key else BOOK_MODULES.firstOrNull { it.route == key }?.title ?: key

    var poems by remember(key) { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }
    var hasMore by remember(key) { mutableStateOf(true) }
    var page by remember(key) { mutableIntStateOf(0) }
    val pageSize = 300

    LaunchedEffect(key, page) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            val batch = if (tagMode) {
                db.listByTag(tag ?: "", pageSize, page * pageSize)
            } else {
                db.listByCategory(category, pageSize, page * pageSize)
            }
            poems = if (page == 0) batch else poems + batch
            hasMore = batch.size == pageSize
        }
    }

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
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(poems.size, key = { poems[it].id }) { index ->
                PoemRow(poems[index], onClick = { onOpen(poems[index].id) })
            }
            if (hasMore && poems.isNotEmpty()) {
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

@Composable
internal fun PoemRow(p: PoemsDb.Poem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = p.title,
            style = PoemTitleStyle,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "${p.author} ｜ " + p.body.replace('\n', ' ').take(30),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
