package com.jigu.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jigu.app.data.PoemsDb
import com.jigu.app.DOCK_CLEARANCE
import androidx.compose.foundation.layout.PaddingValues
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
private fun PagedPoemList(
    pager: Pager,
    onOpen: (Int) -> Unit,
    header: (@Composable () -> Unit)? = null,
    hideAuthor: Boolean = false,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
        header?.let { item { it() } }
        items(pager.items.size, key = { pager.items[it].id }) { index ->
            PoemRow(pager.items[index], onClick = { onOpen(pager.items[index].id) }, hideAuthor = hideAuthor)
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
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
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
                        text = "${a.workCount} 篇" + a.brief.take(24).replace('\n', ' ').trim()
                            .let { if (it.isNotBlank()) " ｜ ${if (it.length < 24) it else it + "…"}" else "" },
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
            val batch = PoemsDb.get(context).authorIndex("all", PAGE, page * PAGE)
            authors = if (page == 0) batch else authors + batch
            hasMore = batch.size == PAGE
        }
    }

    BrowseScaffold(title = "诗人", subtitle = "按作品量排序", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
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

/** 作者主页（D-043 R2 + 骨干重排）：身份区（关注+统计）→ 小传（折叠）→ 名篇 → 全部作品（体裁筛选）。
 *  四区以发丝线分隔；页内行隐藏作者前缀（同名冗余）；名篇行与全库列表行同款式。 */
@Composable
fun AuthorScreen(authorId: Int, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var author by remember { mutableStateOf<PoemsDb.Author?>(null) }
    var following by remember { mutableStateOf(false) }
    var topPoems by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }
    var typeCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var selectedType by remember { mutableStateOf<String?>(null) }
    var bioExpanded by remember(authorId) { mutableStateOf(false) }

    LaunchedEffect(authorId) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            author = db.authorById(authorId)
            following = db.isFollowing(authorId)
            topPoems = db.poemsByAuthorTop(authorId)
            typeCounts = db.authorTypeCounts(authorId)
        }
    }
    val topIds = topPoems.map { it.id }
    // key 换（作者/体裁筛选/名篇加载完）即重挂分页——excludeIds 随之生效
    val pager = rememberPagedPoems(authorId to selectedType to topIds) { page ->
        PoemsDb.get(context).poemsByAuthor(
            authorId, PAGE, page * PAGE,
            type = selectedType, excludeIds = topIds,
        )
    }

    val a = author
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
            PagedPoemList(pager, onOpen, hideAuthor = true, header = {
                Column(Modifier.fillMaxWidth()) {
                    // —— 身份区：关注 + 作品统计 ——
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FollowToggle(following) {
                            following = !following
                            Thread {
                                val db = PoemsDb.get(context)
                                if (following) db.followAuthor(authorId, a.name, a.dynasty)
                                else db.unfollowAuthor(authorId)
                            }.start()
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${typeCounts.values.sum()} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    // —— 小传区（超长折叠）——
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
                                maxLines = if (bioExpanded) Int.MAX_VALUE else 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            if (a.brief.length > 120) {
                                TextButton(onClick = { bioExpanded = !bioExpanded }) {
                                    Text(if (bioExpanded) "收起" else "展开全文")
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    }

                    // —— 名篇区（与列表行同款式）——
                    if (topPoems.isNotEmpty()) {
                        Text(
                            "名篇",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp),
                        )
                        topPoems.forEach { p ->
                            PoemRow(p, onClick = { onOpen(p.id) }, hideAuthor = true)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    }

                    // —— 作品区：体裁筛选 chips ——
                    if (typeCounts.size > 1) {
                        Text(
                            "全部作品",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val all = listOf("全部" to typeCounts.values.sum()) +
                                typeCounts.map { (t, c) -> (t.ifBlank { "未分" }) to c }
                            all.forEach { (label, cnt) ->
                                val selected = (label == "全部" && selectedType == null) ||
                                    (label != "全部" && selectedType == typeCounts.keys.find { it.ifBlank { "未分" } == label })
                                AuthorTypeChip("$label $cnt", selected) {
                                    selectedType = if (label == "全部") null
                                    else typeCounts.keys.find { it.ifBlank { "未分" } == label }
                                }
                            }
                        }
                    }
                }
            })
        }
    }
}

/** 关注开关：胶囊 chip 形制（选中淡朱底，呼应 D-004 零彩色秩序） */
@Composable
private fun FollowToggle(following: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (following) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.clickable(onClick = onToggle),
    ) {
        Text(
            text = if (following) "● 已关注" else "＋ 关注",
            style = MaterialTheme.typography.titleSmall,
            color = if (following) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 体裁筛选 chip（与书库维度 chips 同款形制） */
@Composable
private fun AuthorTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 我关注的诗人（D-043 R2 独立列表页）：按关注时间倒序 */
@Composable
fun FollowsScreen(onBack: () -> Unit, onOpenAuthor: (Int) -> Unit) {
    val context = LocalContext.current
    var authors by remember { mutableStateOf<List<PoemsDb.Author>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { authors = PoemsDb.get(context).followedAuthors() }
    }
    BrowseScaffold(title = "关注的诗人", subtitle = "${authors.size} 位", onBack = onBack) {
        if (authors.isEmpty()) {
            Text(
                "还没有关注的诗人。\n在诗人主页点「关注」，他就会出现在这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(authors, key = { _, a -> a.id }) { _, a ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenAuthor(a.id) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Row {
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                }
            }
        }
    }
}

/** 体裁 -> 作品列表（结构化分类：五言绝句/词/文…） */
@Composable
fun TypeScreen(type: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagedPoems(type) { page ->
        PoemsDb.get(context).listByType(type, PAGE, page * PAGE)
    }
    BrowseScaffold(title = type, subtitle = "", onBack = onBack) {
        PagedPoemList(pager, onOpen)
    }
}

/** 书卷 -> 作品列表（古文观止 12 卷 / 论语 20 篇 / 四书篇目） */
@Composable
fun ChapterScreen(chapter: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagedPoems(chapter) { page ->
        PoemsDb.get(context).listByChapter(chapter, PAGE, page * PAGE)
    }
    BrowseScaffold(title = chapter, subtitle = "书卷", onBack = onBack) {
        PagedPoemList(pager, onOpen)
    }
}

/** 主题 -> 作品列表（咏史/田园/边塞/送别…题材门类） */
@Composable
fun ThemePoemsScreen(theme: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagedPoems(theme) { page ->
        PoemsDb.get(context).listByTheme(theme, PAGE, page * PAGE)
    }
    BrowseScaffold(title = theme, subtitle = "题材", onBack = onBack) {
        PagedPoemList(pager, onOpen)
    }
}

/** 词牌/曲牌索引（批3）：体裁"词"/"曲"的二级页，调名按篇数降序，再点调进同调作品列表。
 *  批10（P1-12）：加调名筛选框——1477 调长尾靠翻页难找，输入直达（命中 DB LIKE 检索）。 */
@Composable
fun RhythmicIndexScreen(type: String, onBack: () -> Unit, onOpenRhythmic: (String) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var hasMore by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    val pageN = 120

    LaunchedEffect(page, filter) {
        withContext(Dispatchers.IO) {
            val batch = if (filter.isBlank()) {
                PoemsDb.get(context).rhythmicCounts(type, pageN, page * pageN)
            } else {
                PoemsDb.get(context).rhythmicCountsSearch(type, filter.trim())
            }
            items = if (page == 0 || filter.isNotBlank()) batch else items + batch
            hasMore = filter.isBlank() && batch.size == pageN
        }
    }

    BrowseScaffold(
        title = if (type == "曲") "曲牌" else "词牌",
        subtitle = "调名 · 按篇数排序",
        onBack = onBack,
    ) {
        Column {
            OutlinedTextField(
                value = filter,
                onValueChange = {
                    filter = it
                    page = 0
                },
                singleLine = true,
                placeholder = { Text("筛选调名（如：浣溪）", style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
            items(items.size, key = { items[it].first }) { index ->
                val (name, count) = items[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRhythmic(name) }
                        .padding(vertical = 14.dp, horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = name, style = PoemTitleStyle, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "$count 篇",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }
            if (hasMore && items.isNotEmpty()) {
                item {
                    TextButton(
                        onClick = { page += 1 },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) { Text("加载更多") }
                }
            }
            if (items.isEmpty()) {
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
    }
}

/** 同调作品列表（批3）：词牌/曲牌末级页 */
@Composable
fun RhythmicListScreen(type: String, rhythmic: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagedPoems(rhythmic) { page ->
        PoemsDb.get(context).listByRhythmic(type, rhythmic, PAGE, page * PAGE)
    }
    BrowseScaffold(title = rhythmic, subtitle = if (type == "曲") "曲牌" else "词牌", onBack = onBack) {
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

/** 名句/名段浏览（批1 书库"名句"卡；D-011 名句制的浏览兑现）：今日页同源数据，按名气序分页 */
@Composable
fun FamousLinesScreen(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<PoemsDb.FamousLine>>(emptyList()) }
    var hasMore by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(0) }
    val pageN = 100

    LaunchedEffect(page) {
        withContext(Dispatchers.IO) {
            val batch = PoemsDb.get(context).famousLines(pageN, page * pageN)
            lines = if (page == 0) batch else lines + batch
            hasMore = batch.size == pageN
        }
    }

    BrowseScaffold(title = "名句", subtitle = "名段 · 今日页与名句表同源", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
            items(lines.size, key = { lines[it].poemId * 1000 + it }) { index ->
                val f = lines[index]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(f.poemId) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = f.line,
                        style = PoemTitleStyle,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "${f.signature}《${f.title}》",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }
            if (hasMore && lines.isNotEmpty()) {
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


/** 文体 -> 作品列表（批7 题材·文体维：古文 13 类，范围=guwen/老子/庄子/史记） */
@Composable
fun GenreScreen(genre: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagedPoems(genre) { page ->
        PoemsDb.get(context).listByGenre(genre, PAGE, page * PAGE)
    }
    BrowseScaffold(title = genre, subtitle = "古文文体", onBack = onBack) {
        PagedPoemList(pager, onOpen)
    }
}

/** 经史名段（D-032）：观止 222 篇内藏 左传/战国策/史记/国语/礼记/公羊/谷梁 等经史选段，
 *  按出处书聚合浮出水面——原藏于"古文观止"卡按卷次排列，出处不可见 */
@Composable
fun GuwenSourcesScreen(onBack: () -> Unit, onOpenSource: (String) -> Unit) {
    val context = LocalContext.current
    var sources by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { sources = PoemsDb.get(context).guwenSourceCounts() }
    }
    BrowseScaffold(title = "经史名段", subtitle = "古文观止选本 · 按出处书", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
            itemsIndexed(sources) { _, (name, count) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSource(name) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (name == "历代文集") "历代文集（唐宋明清诸家）" else name,
                        style = PoemTitleStyle,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$count 篇",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }
        }
    }
}

/** 某出处书的观止篇目（D-032）：按库序（原书卷次）排 */
@Composable
fun GuwenSourceScreen(source: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagedPoems(source) { page ->
        PoemsDb.get(context).listByGuwenSection(source, PAGE, page * PAGE)
    }
    BrowseScaffold(
        title = source.ifBlank { "历代文集" },
        subtitle = "古文观止选段 · 按原书序",
        onBack = onBack,
    ) {
        PagedPoemList(pager, onOpen)
    }
}

/** 名篇榜（D-031）：全库可信搜索热度序（rank/ 四引擎之和；残卷/摘句题已沉底不列），
 *  序位跨页连续（page*页量+行序）。 */
@Composable
fun PopularListScreen(onBack: () -> Unit, onOpen: (Int) -> Unit) {    val context = LocalContext.current
    var poems by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }
    var hasMore by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(0) }
    val pageN = 100

    LaunchedEffect(page) {
        withContext(Dispatchers.IO) {
            val batch = PoemsDb.get(context).listByPopularity(pageN, page * pageN)
            poems = if (page == 0) batch else poems + batch
            hasMore = batch.size == pageN
        }
    }

    BrowseScaffold(title = "名篇榜", subtitle = "千古名篇 · 按热度序", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
            itemsIndexed(poems) { index, p ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(p.id) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${page * pageN + index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(32.dp),
                        )
                        Text(
                            text = "《${p.title}》",
                            style = PoemTitleStyle,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "${p.author}（${p.dynasty}）· " +
                            ellipsize(p.paragraphs.firstOrNull().orEmpty(), 30),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
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
