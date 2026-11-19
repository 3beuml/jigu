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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jigu.app.data.PoemsDb
import androidx.compose.foundation.layout.PaddingValues
import com.jigu.app.DOCK_CLEARANCE
import com.jigu.app.ui.theme.PoemBodySmallStyle
import com.jigu.app.ui.theme.PoemTitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 分栏（D-030）：诗词 / 古文两个顶层 tab 各有专属维度 */
enum class LibrarySection { POETRY, PROSE }

/** 书架（选本层）：真正的"书"。
 *  诗词栏按「内容时代」排序（D-028 用户定案：内容优先，非成书年代）；
 *  古文栏按四部分类法呈现（经/史/子/集，D-030），part 仅古文卡有值。
 *  era 仅用于排序展示，不进库。 */
private data class BookModule(
    val title: String, val route: String, val brief: String, val era: Int,
    val popular: Boolean = false, // 全藏卡：列表按热度名家优先（D-026）
    val part: String? = null,     // 四部归属（仅古文卡，D-030）：经/史/子/集
)

/** 诗词书架 15 卡（D-030 拆栏）：内容时代序 */
private val POETRY_BOOK_MODULES = listOf(
    BookModule("诗经", "shijing", "风雅颂 · 先秦歌谣", 0),
    BookModule("楚辞", "chuci", "骚体 · 浪漫之源", 1),
    // 古诗源（批9/D-029）：沈德潜汉魏六朝诗选，补先秦与唐诗间的断层
    BookModule("古诗源", "gushiyuan", "汉魏六朝诗选 · 名篇", 2),
    BookModule("古诗十九首", "gushi19", "五言之祖 · 东汉无名氏", 3),
    BookModule("曹操诗集", "han_wei", "建安风骨", 4),
    BookModule("唐诗三百首", "tag:唐诗三百首", "家塾课本 · 熟读成诵", 5),
    BookModule("全唐诗", "tang_poem", "五万八千首 · 名家优先", 6, popular = true),
    BookModule("水墨唐诗", "tag:水墨唐诗", "诗画一题 · 唐人选迹", 7),
    BookModule("千家诗", "qianjiashi", "分体选本 · 唐宋名篇", 8),
    BookModule("五代词", "wudai_ci", "花间南唐 · 阳春一集", 9),
    BookModule("宋词三百首", "tag:宋词三百首", "朱祖谋编选 · 两宋词家名篇", 10),
    BookModule("全宋词", "ci", "两宋词章 · 二万一千首", 11, popular = true),
    // 金元词/元诗（批12，D-035）：殆知阁整理本，补金元断层
    BookModule("金元词", "jinyuan_ci", "中州乐府 · 遗山以下", 12),
    BookModule("全宋诗", "song_poem", "二十五万首 · 名家优先", 13, popular = true),
    BookModule("元诗", "yuan_shi", "虞杨范揭 · 一代之盛", 14),
    // 元曲卡：批1（D-021）声记加入但从未落卡，1.09 万首剧曲散曲一度无书架入口（D-028 补回）
    BookModule("元曲", "yuan_qu", "杂剧散曲 · 万余首", 15),
    BookModule("纳兰词", "qing_ci", "清词第一", 16),
    // 清诗/唐五代词/近三百年词（批13，D-037）：殆知阁整理本，补明清近代断层
    BookModule("清诗", "qing_shi", "晚晴簃诗汇 · 五千八百家", 17),
    BookModule("唐五代词", "tang_ci", "敦煌曲子 · 花间尊前", 18),
    // 乐府诗集（批15，D-042）：郭茂倩百卷十二类，补汉魏六朝乐府（古诗源之外整本总集）
    BookModule("乐府诗集", "yuefu", "郭茂倩百卷 · 十二类", 19),
    BookModule("近三百年词", "jinsanbai_ci", "龙榆生选 · 词学中兴", 20),
    // 名篇榜（D-031）：跨书热度榜，非"书"卡——类似名句卡挂书架尾
    BookModule("名篇榜", "popular", "千古名篇 · 热度序", 21),
)

/** 古文书架 10 卡（D-032 重排）：四库法序——
 *  经（论语/四书·四书类，蒙学·小学附经）、史（史记；经史名段=观止内藏经史选段出处聚合）、
 *  子（诸子：老子/庄子；批11 后补墨儒法兵杂）、说部（子部小说家·杂家：世说/幽梦影——
 *  与诸子分块，免"世说塞子部"之混淆）、集（古文观止·总集） */
private val PROSE_BOOK_MODULES = listOf(
    BookModule("论语", "lunyu", "语录即文章", 0, part = "经"),
    // 卡名四书：sishu 类实收 大学/中庸/孟子 16 篇（原"孟子"卡混收大学中庸，D-028 改名对齐；论语另卡）
    BookModule("四书", "sishu", "大学 · 中庸 · 孟子七篇", 1, part = "经"),
    BookModule("蒙学", "mengxue", "声律启蒙 · 幼学琼林等九种", 2, part = "经"),
    BookModule("史记", "shiji", "纪传之祖 · 名段 + 百三十卷", 3, part = "史"),
    BookModule("经史名段", "guwen_sources", "左传战国策国语 · 观止本选段", 4, part = "史"),
    BookModule("老子", "laozi", "道德五千言 · 全 81 章", 5, part = "子"),
    BookModule("庄子", "zhuangzi", "内篇七全 · 外篇名篇", 6, part = "子"),
    BookModule("世说新语", "shishuo", "魏晋风度 · 名则选", 7, part = "说部"),
    BookModule("幽梦影", "youmengying", "清言小品 · 带评点", 8, part = "说部"),
    BookModule("古文观止", "guwen", "散文正宗 · 十二卷", 9, part = "集"),
    // —— 批11（D-033）经史子扩容：殆知阁白文底本重构，选段制 ——
    BookModule("左传", "zuozhuan", "编年之祖 · 名段选（四库归经）", 10, part = "史"),
    BookModule("国语", "guoyu", "国别体之祖 · 名段选", 11, part = "史"),
    BookModule("战国策", "zhanguoce", "纵横家言 · 名篇选", 12, part = "史"),
    BookModule("墨子", "mozi", "兼爱非攻 · 选", 13, part = "子"),
    BookModule("荀子", "xunzi", "劝学 · 名篇选", 14, part = "子"),
    BookModule("韩非子", "hanfeizi", "刑名法术 · 名篇选", 15, part = "子"),
    BookModule("列子", "liezi", "冲虚至德 · 八篇全本", 16, part = "子"),
    BookModule("孙子", "sunzi", "兵学圣典 · 十三篇", 17, part = "子"),
    BookModule("淮南子", "huainanzi", "杂家鸿篇 · 名篇选", 18, part = "子"),
    BookModule("吕氏春秋", "lvshi", "一字千金 · 名篇选", 19, part = "子"),
    BookModule("管子", "guanzi", "牧民之政 · 名篇选", 20, part = "子"),
    BookModule("晏子春秋", "yanzi", "谏诤故事 · 名篇选", 21, part = "子"),
    BookModule("商君书", "shangjunshu", "变法之书 · 名篇选", 22, part = "子"),
)

/** 全部书卡（ModuleScreen 标题/全藏标记查表用）；名句卡已迁「我的」（D-030） */
private val BOOK_MODULES = POETRY_BOOK_MODULES + PROSE_BOOK_MODULES

/** 书库分栏（D-030）：搜索 + 各栏专属维度——二级门全部拉平上首屏（D-026）。
 *  诗词栏：书架 / 题材 / 体裁 / 作者 / 朝代；古文栏：书架 / 文体 / 体裁 / 作者 / 朝代 */
@Composable
fun LibraryScreen(
    section: LibrarySection,
    onOpenModule: (String) -> Unit,
    onOpenEra: (String) -> Unit,
    onOpenAuthor: (Int) -> Unit,
    onOpenType: (String) -> Unit,
    onOpenTheme: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpen: (Int) -> Unit,
    onOpenHighlight: ((Int, String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val poetry = section == LibrarySection.POETRY
    var query by remember { mutableStateOf("") }
    var dim by rememberSaveable(section) { mutableIntStateOf(0) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var eraCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var typeCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var results by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }
    // 搜索三路命中（D-043 R1）：作者卡置顶 → 名句 → 诗文
    var resAuthors by remember { mutableStateOf<List<PoemsDb.Author>>(emptyList()) }
    var resFamous by remember { mutableStateOf<List<PoemsDb.FamousLine>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            counts = db.categoryCounts()
            eraCounts = db.eraCounts(poetry)
            typeCounts = db.typeCounts()
        }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList(); resAuthors = emptyList(); resFamous = emptyList()
        } else withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            resAuthors = db.searchAuthors(query)
            resFamous = db.searchFamousLines(query)
            results = db.search(query)
        }
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
            placeholder = { Text("搜诗句、作者、名句、篇名（如：柳永 / 月落乌啼）") },
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
            (if (poetry) listOf("书架", "题材", "体裁", "作者", "朝代")
            else listOf("书架", "文体", "体裁", "作者", "朝代")).forEachIndexed { index, label ->
                DimensionChip(label = label, selected = dim == index && query.isBlank()) {
                    query = ""
                    dim = index
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (query.isNotBlank()) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
                if (resAuthors.isNotEmpty()) {
                    item {
                        SearchGroupHeader("作者")
                    }
                    items(resAuthors.size, key = { "a_${resAuthors[it].id}" }) { index ->
                        AuthorResultCard(resAuthors[index], onClick = { onOpenAuthor(resAuthors[index].id) })
                    }
                }
                if (resFamous.isNotEmpty()) {
                    item {
                        SearchGroupHeader("名句")
                    }
                    items(resFamous.size, key = { "f_${resFamous[it].poemId}_${resFamous[it].line.hashCode()}" }) { index ->
                        val fl = resFamous[index]
                        FamousResultRow(fl, onClick = {
                            // 名句 → 原诗全文并高亮该句（D-043 R1）
                            onOpenHighlight?.invoke(fl.poemId, fl.line) ?: onOpen(fl.poemId)
                        })
                    }
                }
                if (resAuthors.isNotEmpty() || resFamous.isNotEmpty()) {
                    item {
                        SearchGroupHeader("诗文")
                    }
                }
                items(results.size, key = { results[it].id }) { index ->
                    PoemRow(
                        results[index],
                        sourceLabel = sourceLabelOf(results[index]),
                        query = query,
                        onClick = { onOpen(results[index].id) },
                    )
                }
                if (results.isEmpty() && resAuthors.isEmpty() && resFamous.isEmpty()) {
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
            0 -> ShelfGrid(section, onOpenModule)
            1 -> if (poetry) PoetryThemeContent(onOpenTheme) else ProseGenreContent(onOpenGenre)
            2 -> TypeFlatContent(poetry, typeCounts, counts, onOpenType, onOpenChapter, onOpenModule)
            3 -> AuthorFlatContent(poetry, onOpenAuthor)
            4 -> EraDimContent(eraCounts, onOpenEra)
        }
    }
}

/** 搜索结果分组头（D-043 R1：作者/名句/诗文 三段） */
@Composable
private fun SearchGroupHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

/** 作者命中卡（搜"柳永"置顶出人，点击进诗人主页） */
@Composable
private fun AuthorResultCard(a: PoemsDb.Author, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    listOfNotNull(
                        a.dynasty.takeIf { it.isNotBlank() },
                        "${a.workCount} 首",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (a.brief.isNotBlank()) {
                Text(
                    a.brief,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 名句命中行（点击 → 原诗全文并高亮该句） */
@Composable
private fun FamousResultRow(fl: PoemsDb.FamousLine, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            fl.line,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            fl.signature + "《" + fl.title + "》",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
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

/** 书架：计数 = 分类统计 + tag 选本。诗词栏平铺内容时代序；古文栏按四部分块（D-030） */
@Composable
private fun ShelfGrid(section: LibrarySection, onOpenModule: (String) -> Unit) {
    val context = LocalContext.current
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var tagCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var popularCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            counts = db.categoryCounts()
            tagCounts = listOf("唐诗三百首", "宋词三百首", "水墨唐诗").associateWith { db.tagCount(it) }
            popularCount = db.popularCount()
        }
    }
    val countOf: (BookModule) -> Int = { book ->
        when {
            book.route == "popular" -> popularCount
            book.route.startsWith("tag:") -> tagCounts[book.route.removePrefix("tag:")] ?: 0
            else -> counts[book.route] ?: 0
        }
    }
    val books = if (section == LibrarySection.POETRY) POETRY_BOOK_MODULES else PROSE_BOOK_MODULES
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = DOCK_CLEARANCE),
    ) {
        if (section == LibrarySection.PROSE) {
            // 四库法头占整行（D-030/D-032）：经/史/子/说部/集 分组呈现
            listOf("经", "史", "子", "说部", "集").forEach { part ->
                item(key = "part_$part", span = { GridItemSpan(2) }) {
                    Text(
                        text = part,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 14.dp, bottom = 0.dp),
                    )
                }
                items(books.filter { it.part == part }, key = { it.route }) { book ->
                    ModuleCard(title = book.title, brief = book.brief, count = countOf(book)) { onOpenModule(book.route) }
                }
            }
        } else {
            items(books, key = { it.route }) { book ->
                ModuleCard(title = book.title, brief = book.brief, count = countOf(book)) { onOpenModule(book.route) }
            }
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

/** 题材维（D-026 拉平；D-030 归诗词栏）：诗词题材按四门分块 chips，全部一跳 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PoetryThemeContent(onOpenTheme: (String) -> Unit) {
    val context = LocalContext.current
    var groups by remember { mutableStateOf<Map<String, Map<String, Int>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { groups = PoemsDb.get(context).themeGroups() }
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
        item(key = "tail") { Spacer(Modifier.height(16.dp)) }
    }
}

/** 文体维（D-030 归古文栏）：古文文体 13 类（《古文辞类纂》框架） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProseGenreContent(onOpenGenre: (String) -> Unit) {
    val context = LocalContext.current
    var genres by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { genres = PoemsDb.get(context).genreCounts() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = DOCK_CLEARANCE),
    ) {
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

/** 体裁维（D-026 拉平；D-030 分栏）：诗词栏 = 诗 13 子类 + 词/曲（调名索引，计数=调数）；
 *  古文栏 = 文（九书直出） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeFlatContent(
    poetry: Boolean,
    typeCounts: Map<String, Int>,
    catCounts: Map<String, Int>,
    onOpenType: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val context = LocalContext.current
    var chapters by remember { mutableStateOf<Map<String, Map<String, Int>>>(emptyMap()) }
    var rhythmicCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            if (poetry) rhythmicCounts = db.rhythmicTypeCounts()
            else chapters = db.chapterCounts()
        }
    }
    val poemTypes = listOf(
        "五言绝句", "七言绝句", "五言律诗", "七言律诗", "五言排律", "七言排律",
        "古体诗", "五言古诗", "七言古诗", "乐府", "四言诗", "诗经体", "骚体",
    )
    val counts = { t: String -> typeCounts[t] ?: 0 }
    // 文 · 书卷：九书全直出（D-028）。章 chips 用于 chapter 有值的书；
    // 老子/幽梦影/蒙学章号在题内（chapter 空），以书级 chip 进 ModuleScreen 按序浏览
    val wenBooks = listOf(
        "guwen" to "古文观止", "lunyu" to "论语", "sishu" to "四书",
        "laozi" to "老子", "zhuangzi" to "庄子", "shiji" to "史记",
        "shishuo" to "世说新语", "youmengying" to "幽梦影", "mengxue" to "蒙学",
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = DOCK_CLEARANCE),
    ) {
        if (poetry) {
            // —— 诗：子体裁全平铺 + 其他兜底（原 175 篇 type="其他" 无 chip 不可达） ——
            item(key = "h_shi") { BlockTitle("诗 · 子体裁") }
            item(key = "chips_shi") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (poemTypes + "其他").forEach { t ->
                        val c = counts(t)
                        if (c > 0) CountChip(label = t, count = c) { onOpenType(t) }
                    }
                }
            }
            // —— 词 / 曲：词牌 / 曲牌索引入口（计数=调名数，非篇数） ——
            item(key = "h_cq") { BlockTitle("词 · 曲") }
            item(key = "chips_cq") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountChip(label = "词牌", count = rhythmicCounts["词"] ?: 0) { onOpenType("词") }
                    CountChip(label = "曲牌", count = rhythmicCounts["曲"] ?: 0) { onOpenType("曲") }
                }
            }
        } else {
            // —— 文：书卷直出（原只白名单三书，三书/幽梦影/蒙学漏，D-028 补全九书） ——
            item(key = "h_wen") { BlockTitle("文 · 书卷") }
            wenBooks.forEach { (cat, bookName) ->
                val vol = chapters[cat]
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
                        if (vol.isNullOrEmpty()) {
                            CountChip(label = "全书", count = catCounts[cat] ?: 0) { onOpenModule(cat) }
                        } else {
                            vol.forEach { (chapter, count) ->
                                CountChip(label = chapter, count = count) { onOpenChapter(chapter) }
                            }
                        }
                    }
                }
            }
        }
        item(key = "tail") { Spacer(Modifier.height(16.dp)) }
    }
}

/** 作者维（D-026 拉平；D-028 修订；D-030 分栏）：分组 = 有该体裁作品即入组（按该体裁作品量序），
 *  多栖作者（苏轼/欧阳修/韩愈）在各组都可见；体裁组排除无名氏/佚名防霸榜。
 *  诗词栏：全部/诗人/词曲；古文栏：全部/文家 */
@Composable
private fun AuthorFlatContent(poetry: Boolean, onOpenAuthor: (Int) -> Unit) {
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
            (if (poetry) listOf("all" to "全部", "poet" to "诗人", "ci" to "词曲")
            else listOf("all" to "全部", "wen" to "文家"))
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
    "gushiyuan" to "古诗源",
    // 批11（D-033）
    "zuozhuan" to "左传", "guoyu" to "国语", "zhanguoce" to "战国策",
    "mozi" to "墨子", "xunzi" to "荀子", "hanfeizi" to "韩非子", "liezi" to "列子",
    "sunzi" to "孙子", "huainanzi" to "淮南子", "lvshi" to "吕氏春秋",
    "guanzi" to "管子", "yanzi" to "晏子春秋", "shangjunshu" to "商君书",
    // 批12（D-035）
    "jinyuan_ci" to "全金元词", "yuan_shi" to "元诗选",
    // 批13（D-037）
    "qing_shi" to "晚晴簃诗汇", "tang_ci" to "全唐五代词", "jinsanbai_ci" to "近三百年名家词选",
    // 批15（D-042）
    "yuefu" to "乐府诗集",
)
internal fun sourceLabelOf(p: PoemsDb.Poem): String = when {
    p.tags.contains("唐诗三百首") -> "唐诗三百首"
    p.category == "tang_poem" && p.tags.contains("水墨唐诗") -> "水墨唐诗"
    else -> CATEGORY_LABELS[p.category] ?: ""
}

/** 书架模块浏览页：key 为分类名或 "tag:标签名"，分页加载。
 *  经部成篇书（论语/四书）走篇章目录（D-032）：目录直达详情，不再整篇平铺卡片 */
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
        if (key == "lunyu" || key == "sishu") {
            // —— 篇章目录（D-032）：论语二十篇 / 大学·中庸·孟子七篇，目录直达详情 ——
            // 语录体每篇=一条记录（章名即题），平铺卡片难定位，目录页给"篇名+则数"
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
                var lastGroup = ""
                poems.forEach { poem ->
                    // 孟子七篇归组（D-033）：数据实为每篇分上下两条（通行本结构），目录组头还原
                    // 七篇之目（孟子·梁惠王 → 上/下两行），不再散成 14 条平铺
                    val group = if (key == "sishu") when (poem.title) {
                        "大学" -> "大学"
                        "中庸" -> "中庸"
                        else -> "孟子 · " + poem.title.removeSuffix("上").removeSuffix("下")
                    } else ""
                    if (group.isNotEmpty() && group != lastGroup) {
                        item(key = "g_$group") {
                            Text(
                                text = group,
                                style = PoemTitleStyle,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }
                        lastGroup = group
                    }
                    item(key = poem.id) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(poem.id) }
                                .padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = poem.title,
                                style = PoemTitleStyle,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${poem.paragraphs.size} 则",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
            }
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
            // 小节标题（P1-4）：千家诗/唐诗三百首按体裁卷，古文观止按卷次，元曲按「・」前的剧名，
            // 列表不再整卷平铺（元曲 1.1 万首平列表需翻 37 页——剧名分组后按目翻）
            val sectionOf: ((PoemsDb.Poem) -> String?)? = when {
                key == "qianjiashi" -> { p -> p.type }
                key == "guwen" -> { p -> p.chapter }
                key == "zhuangzi" || key == "shishuo" || key == "shiji" ||
                    // 批11（D-033）：经史子新卡按卷/篇次分组
                    key in setOf(
                        "zuozhuan", "guoyu", "zhanguoce", "mozi", "xunzi", "hanfeizi",
                        "liezi", "sunzi", "huainanzi", "lvshi", "guanzi", "yanzi", "shangjunshu",
                        // 批15（D-042）乐府按十二类分组；列子整本按八篇
                        "yuefu",
                    ) -> { p -> p.chapter.ifBlank { null } }
                key == "yuan_qu" -> { p ->
                    // 「诈妮子调风月・混江龙」→ 节 = 剧名；无分隔的单牌曲无节；
                    // 过长/过短/纯数字前段（如散套序数「二」）不算剧目
                    p.title.split('・').firstOrNull()?.trim()
                        ?.takeIf { it.length in 2..12 && !it.all { ch -> ch.isDigit() || ch == '，' } }
                }
                tagMode && tag == "唐诗三百首" -> { p -> p.type }
                else -> null
            }
            // 体裁分组模式先按体裁重排（唐诗三百首原书体裁交错——id 序里"五言律诗"非连续
            // 出现 208 次，分组头 key 必撞 → 必闪退，批1 潜伏雷，D-038 修复）
            val sortBySection = (tagMode && tag == "唐诗三百首") || key == "qianjiashi"
            val renderPoems = if (sectionOf != null && sortBySection) {
                poems.sortedWith(compareBy({ sectionOf.invoke(it) ?: "" }, { it.id }))
            } else poems
            val seenSections = mutableSetOf<String>()
            renderPoems.forEach { poem ->
                if (sectionOf != null) {
                    val sec = sectionOf.invoke(poem)
                    if (!sec.isNullOrBlank() && sec !in seenSections) {
                        seenSections.add(sec)
                        item(key = "h_$sec") {
                            Text(
                                text = sec,
                                style = PoemTitleStyle,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }
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

/** 搜索预览（P1-17）：正文里定位首个命中处，展示命中上下文并高亮；
 * 出处书名（D-015）保留在前；命中在题名/作者时回退常规预览。 */
@Composable
private fun searchPreviewAnnotated(p: PoemsDb.Poem, query: String, sourceLabel: String?): AnnotatedString {
    val body = p.body.replace('\n', ' ').trim()
    val hl = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    val prefix = listOfNotNull(
        sourceLabel?.takeIf { it.isNotBlank() },
        p.author.ifBlank { "佚名" },
    ).joinToString(" ｜ ")
    val idx = body.indexOf(query)
    return if (idx >= 0) {
        val s = (idx - 10).coerceAtLeast(0)
        val e = (idx + query.length + 22).coerceAtMost(body.length)
        val start = idx - s + if (s > 0) 1 else 0
        buildAnnotatedString {
            append("$prefix ｜ ")
            if (s > 0) append("…")
            append(body.substring(s, e))
            if (e < body.length) append("…")
            addStyle(hl, prefix.length + 3 + (if (s > 0) 1 else 0), prefix.length + 3 + (if (s > 0) 1 else 0) + query.length)
        }
    } else {
        buildAnnotatedString { append("$prefix ｜ " + ellipsize(body, 30)) }
    }
}

@Composable
internal fun PoemRow(
    p: PoemsDb.Poem,
    onClick: () -> Unit,
    sourceLabel: String? = null,
    query: String? = null,
    hideAuthor: Boolean = false,
) {
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
        val meta: AnnotatedString = if (hideAuthor) {
            // 诗人主页内：作者与本页同名，前缀冗余——只留体裁/出处 + 正文预览（D-043 骨干重排）
            val prefix = sourceLabel?.takeIf { it.isNotBlank() } ?: p.type.takeIf { it.isNotBlank() } ?: ""
            AnnotatedString(if (prefix.isBlank()) ellipsize(p.body, 30) else "$prefix ｜ " + ellipsize(p.body, 30))
        } else {
            AnnotatedString(
                listOfNotNull(
                    sourceLabel?.takeIf { it.isNotBlank() },
                    p.author.ifBlank { "佚名" },
                ).joinToString(" ｜ ") + " ｜ " + ellipsize(p.body, 30)
            )
        }
        Text(
            text = if (query != null) searchPreviewAnnotated(p, query, sourceLabel) else meta,
            style = PoemBodySmallStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
