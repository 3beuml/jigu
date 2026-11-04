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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.PaddingValues
import com.jigu.app.DOCK_CLEARANCE
import com.jigu.app.ui.theme.PoemTitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 书架（选本层）：真正的"书"。
 *  顺序按书的「内容时代」（用户定案：内容优先，非成书年代）——曹操虽是汉魏选集但内容最古的依次往前。
 *  era 仅用于排序展示，不进库。 */
private data class BookModule(
    val title: String, val route: String, val brief: String, val era: Int,
    val popular: Boolean = false, // 全藏卡：列表按热度名家优先（D-026）
)

private val BOOK_MODULES = listOf(
    BookModule("诗经", "shijing", "风雅颂 · 先秦歌谣", 0),
    BookModule("楚辞", "chuci", "骚体 · 浪漫之源", 1),
    BookModule("论语", "lunyu", "语录即文章", 2),
    BookModule("老子", "laozi", "道德五千言 · 全 81 章", 3),
    BookModule("孟子", "sishu", "雄辩滔滔", 4),
    BookModule("庄子", "zhuangzi", "内篇七全 · 外篇名篇", 5),
    BookModule("古诗十九首", "gushi19", "五言之祖 · 东汉无名氏", 6),
    BookModule("曹操诗集", "han_wei", "建安风骨", 7),
    BookModule("史记", "shiji", "纪传之祖 · 名段选", 8),
    BookModule("世说新语", "shishuo", "魏晋风度 · 名则选", 9),
    BookModule("五代词", "wudai_ci", "花间集 · 南唐二主", 10),
    BookModule("唐诗三百首", "tag:唐诗三百首", "家塾课本 · 熟读成诵", 11),
    BookModule("全唐诗", "tang_poem", "五万七千首 · 名家优先", 12, popular = true),
    BookModule("水墨唐诗", "tag:水墨唐诗", "诗画一题 · 唐人选迹", 13),
    BookModule("千家诗", "qianjiashi", "分体选本 · 唐宋名篇", 14),
    BookModule("宋词三百首", "tag:宋词三百首", "朱祖谋编选 · 两宋词家名篇", 15),
    BookModule("全宋词", "ci", "两宋词章 · 二万一千首", 16, popular = true),
    BookModule("全宋诗", "song_poem", "二十五万首 · 名家优先", 17, popular = true),
    BookModule("古文观止", "guwen", "散文正宗 · 十二卷", 18),
    BookModule("幽梦影", "youmengying", "清言小品 · 带评点", 19),
    BookModule("蒙学", "mengxue", "声律启蒙 · 幼学琼林等八种", 20),
    BookModule("纳兰词", "qing_ci", "清词第一", 21),
    BookModule("名句", "famous", "千古名句 · 今日页同源", 22),
).sortedBy { it.era }

/** 书库：搜索 + 五维浏览（书架 / 题材·文体 / 体裁 / 作者 / 朝代）——二级门全部拉平上首屏（D-026） */
@Composable
fun LibraryScreen(
    onOpenModule: (String) -> Unit,
    onOpenEra: (String) -> Unit,
    onOpenAuthor: (Int) -> Unit,
    onOpenType: (String) -> Unit,
    onOpenTheme: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var dim by rememberSaveable { mutableIntStateOf(0) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var eraCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var typeCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var results by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            counts = db.categoryCounts()
            eraCounts = db.eraCounts()
            typeCounts = db.typeCounts()
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
            listOf("书架", "题材", "体裁", "作者", "朝代").forEachIndexed { index, label ->
                DimensionChip(label = label, selected = dim == index && query.isBlank()) {
                    query = ""
                    dim = index
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (query.isNotBlank()) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
                items(results.size, key = { results[it].id }) { index ->
                    PoemRow(
                        results[index],
                        sourceLabel = sourceLabelOf(results[index]),
                        onClick = { onOpen(results[index].id) },
                    )
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
            1 -> ThemeGenreContent(onOpenTheme, onOpenGenre)
            2 -> TypeFlatContent(typeCounts, onOpenType, onOpenChapter)
            3 -> AuthorFlatContent(onOpenAuthor)
            4 -> EraDimContent(eraCounts, onOpenEra)
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

/** 书架（卡顺序与 hard cap 见 BOOK_MODULES）：计数 = 分类统计 + tag 选本 + 名句表 */
@Composable
private fun ShelfGrid(onOpenModule: (String) -> Unit) {
    val context = LocalContext.current
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var tagCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var famousCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            counts = db.categoryCounts()
            tagCounts = listOf("唐诗三百首", "宋词三百首", "水墨唐诗").associateWith { db.tagCount(it) }
            famousCount = db.famousCount()
        }
    }
    val countOf: (BookModule) -> Int = { book ->
        when {
            book.route == "famous" -> famousCount
            book.route.startsWith("tag:") -> tagCounts[book.route.removePrefix("tag:")] ?: 0
            else -> counts[book.route] ?: 0
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = DOCK_CLEARANCE),
    ) {
        items(BOOK_MODULES, key = { it.route }) { book ->
            ModuleCard(title = book.title, brief = book.brief, count = countOf(book)) { onOpenModule(book.route) }
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
        item { Spacer(Modifier.height(DOCK_CLEARANCE)) }
    }
}

/** 分块 chip（D-026：细类直接上首屏，一跳进列表） */
@Composable
private fun CountChip(label: String, count: Int, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(6.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 块标题（题材四门/古文文体等区块头） */
@Composable
private fun BlockTitle(text: String) {
    Text(
        text = text,
        style = PoemTitleStyle,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
    )
}

/** 题材·文体维（D-026 拉平）：诗词题材按四门分块 chips + 古文文体 13 类块，全部一跳 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeGenreContent(onOpenTheme: (String) -> Unit, onOpenGenre: (String) -> Unit) {
    val context = LocalContext.current
    var groups by remember { mutableStateOf<Map<String, Map<String, Int>>>(emptyMap()) }
    var genres by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            groups = db.themeGroups()
            genres = db.genreCounts()
        }
    }
    val doorOrder = listOf("写景", "抒怀", "咏物", "世情")
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = DOCK_CLEARANCE),
    ) {
        // —— 诗词题材：四门分块，细类 chips 直接平铺 ——
        item(key = "h_theme") { BlockTitle("诗词题材 · 四门三十类") }
        doorOrder.forEach { gate ->
            val fine = groups[gate].orEmpty().entries.sortedByDescending { it.value }.toList()
            if (fine.isEmpty()) return@forEach
            item(key = "gate_$gate") { BlockTitle(gate) }
            item(key = "chips_$gate") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fine.forEach { (name, count) ->
                        CountChip(label = name, count = count) { onOpenTheme(name) }
                    }
                }
            }
        }
        // —— 古文文体：13 类（《古文辞类纂》框架）——
        item(key = "h_genre") { BlockTitle("古文文体 · 十三类") }
        item(key = "chips_genre") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GENRE_ORDER.forEach { g ->
                    val c = genres[g] ?: 0
                    if (c > 0) CountChip(label = g, count = c) { onOpenGenre(g) }
                }
            }
        }
        item(key = "tail") { Spacer(Modifier.height(16.dp)) }
    }
}

/** 文体 13 类固定序（《古文辞类纂》） */
internal val GENRE_ORDER = listOf(
    "论辨", "序跋", "奏议", "书说", "赠序", "诏令", "传状", "碑志",
    "杂记", "箴铭", "颂赞", "辞赋", "哀祭",
)

/** 体裁维（D-026 拉平）：诗 13 子类直接平铺 + 词/曲入口 + 文（书卷直出） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeFlatContent(
    typeCounts: Map<String, Int>,
    onOpenType: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
) {
    val context = LocalContext.current
    var chapters by remember { mutableStateOf<Map<String, Map<String, Int>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { chapters = PoemsDb.get(context).chapterCounts() }
    }
    val poemTypes = listOf(
        "五言绝句", "七言绝句", "五言律诗", "七言律诗", "五言排律", "七言排律",
        "古体诗", "五言古诗", "七言古诗", "乐府", "四言诗", "诗经体", "骚体",
    )
    val counts = { t: String -> typeCounts[t] ?: 0 }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = DOCK_CLEARANCE),
    ) {
        // —— 诗：子体裁全平铺 ——
        item(key = "h_shi") { BlockTitle("诗 · 子体裁") }
        item(key = "chips_shi") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                poemTypes.forEach { t ->
                    val c = counts(t)
                    if (c > 0) CountChip(label = t, count = c) { onOpenType(t) }
                }
            }
        }
        // —— 词 / 曲：词牌 / 曲牌索引入口 ——
        item(key = "h_cq") { BlockTitle("词 · 曲") }
        item(key = "chips_cq") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountChip(label = "词牌", count = counts("词")) { onOpenType("词") }
                CountChip(label = "曲牌", count = counts("曲")) { onOpenType("曲") }
            }
        }
        // —— 文：书卷直出（原两级拉平） ——
        item(key = "h_wen") { BlockTitle("文 · 书卷") }
        chapters.forEach { (cat, vol) ->
            val bookName = mapOf(
                "guwen" to "古文观止", "lunyu" to "论语", "sishu" to "四书",
            )[cat] ?: cat
            item(key = "bk_$cat") {
                Text(
                    bookName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
            }
            item(key = "chips_$cat") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vol.forEach { (chapter, count) ->
                        CountChip(label = chapter, count = count) { onOpenChapter(chapter) }
                    }
                }
            }
        }
        item(key = "tail") { Spacer(Modifier.height(16.dp)) }
    }
}

/** 作者维（D-026 拉平）：分组 chips + 作者索引直出（含古文大家） */
@Composable
private fun AuthorFlatContent(onOpenAuthor: (Int) -> Unit) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf("all") }
    var authors by remember(kind) { mutableStateOf<List<PoemsDb.Author>>(emptyList()) }
    var hasMore by remember(kind) { mutableStateOf(true) }
    var page by remember(kind) { mutableIntStateOf(0) }
    val pageN = 60

    LaunchedEffect(kind, page) {
        withContext(Dispatchers.IO) {
            val batch = PoemsDb.get(context).authorIndex(kind, pageN, page * pageN)
            authors = if (page == 0) batch else authors + batch
            hasMore = batch.size == pageN
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("all" to "全部", "poet" to "诗人", "ciren" to "词人", "wen" to "古文大家")
                .forEach { (k, label) ->
                    DimensionChip(label = label, selected = kind == k) { kind = k }
                }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = DOCK_CLEARANCE),
        ) {
            items(authors.size, key = { authors[it].id }) { index ->
                val a = authors[index]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAuthor(a.id) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
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
            }
            if (hasMore && authors.isNotEmpty()) {
                item {
                    TextButton(onClick = { page += 1 }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                }
            }
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

/** 文本截断：置定长 + 省略号（统一与详情页相关诗词一致的截断形态，P2-1） */
internal fun ellipsize(text: String, max: Int): String {
    val s = text.replace('\n', ' ').trim()
    return if (s.length <= max) s else s.take(max) + "…"
}

/** 书库来源标签（D-015）：命中处尽量给出处书名，搜索结果不再"无出处" */
private val CATEGORY_LABELS = mapOf(
    "tang_poem" to "全唐诗", "song_poem" to "全宋诗", "ci" to "全宋词",
    "qing_ci" to "纳兰词", "wudai_ci" to "五代词", "qianjiashi" to "千家诗",
    "shijing" to "诗经", "chuci" to "楚辞", "lunyu" to "论语", "sishu" to "四书",
    "guwen" to "古文观止", "laozi" to "老子", "gushi19" to "古诗十九首",
    "zhuangzi" to "庄子", "shishuo" to "世说新语", "shiji" to "史记",
    "youmengying" to "幽梦影", "mengxue" to "蒙学",
    "han_wei" to "曹操诗集", "yuan_qu" to "元曲",
)
internal fun sourceLabelOf(p: PoemsDb.Poem): String = when {
    p.tags.contains("唐诗三百首") -> "唐诗三百首"
    p.category == "tang_poem" && p.tags.contains("水墨唐诗") -> "水墨唐诗"
    else -> CATEGORY_LABELS[p.category] ?: ""
}

/** 书架模块浏览页：key 为分类名或 "tag:标签名"，分页加载 */
@Composable
fun ModuleScreen(key: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val tagMode = key.startsWith("tag:")
    val category = if (tagMode) "tang_poem" else key
    val tag = if (tagMode) key.removePrefix("tag:") else null
    val label = if (tagMode) tag ?: key else BOOK_MODULES.firstOrNull { it.route == key }?.title ?: key
    val popular = BOOK_MODULES.firstOrNull { it.route == key }?.popular == true

    var poems by remember(key) { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }
    var hasMore by remember(key) { mutableStateOf(true) }
    var page by remember(key) { mutableIntStateOf(0) }
    val pageSize = 300

    LaunchedEffect(key, page) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            val batch = when {
                tagMode -> db.listByTag(tag ?: "", pageSize, page * pageSize)
                popular -> db.listByCategoryPopular(category, pageSize, page * pageSize)
                else -> db.listByCategory(category, pageSize, page * pageSize)
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
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
            // 小节标题（P1-4）：千家诗/唐诗三百首按体裁卷，古文观止按卷次，元曲按「・」前的剧名，
            // 列表不再整卷平铺（元曲 1.1 万首平列表需翻 37 页——剧名分组后按目翻）
            val sectionOf: ((PoemsDb.Poem) -> String?)? = when {
                key == "qianjiashi" -> { p -> p.type }
                key == "guwen" -> { p -> p.chapter }
                key == "zhuangzi" || key == "shishuo" || key == "shiji" -> { p -> p.chapter.ifBlank { null } }
                key == "yuan_qu" -> { p ->
                    // 「诈妮子调风月・混江龙」→ 节 = 剧名；无分隔的单牌曲无节；
                    // 过长/过短/纯数字前段（如散套序数「二」）不算剧目
                    p.title.split('・').firstOrNull()?.trim()
                        ?.takeIf { it.length in 2..12 && !it.all { ch -> ch.isDigit() || ch == '，' } }
                }
                tagMode && tag == "唐诗三百首" -> { p -> p.type }
                else -> null
            }
            var lastSection = ""
            poems.forEach { poem ->
                if (sectionOf != null) {
                    val sec = sectionOf.invoke(poem)
                    if (!sec.isNullOrBlank() && sec != lastSection) {
                        item(key = "h_$sec") {
                            Text(
                                text = sec,
                                style = PoemTitleStyle,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }
                        lastSection = sec
                    }
                }
                item(key = poem.id) {
                    PoemRow(poem, onClick = { onOpen(poem.id) })
                }
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
internal fun PoemRow(p: PoemsDb.Poem, onClick: () -> Unit, sourceLabel: String? = null) {
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
            text = listOfNotNull(
                sourceLabel?.takeIf { it.isNotBlank() },
                p.author.ifBlank { "佚名" },
            ).joinToString(" ｜ ") + " ｜ " + ellipsize(p.body, 30),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
