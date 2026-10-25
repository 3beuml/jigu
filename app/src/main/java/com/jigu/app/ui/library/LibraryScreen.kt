package com.jigu.app.ui.library

import androidx.compose.foundation.clickable
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

/** 书库模块（京墨式）：category = 按分类浏览；tag = 按标签筛选（如三百首） */
private data class Module(
    val key: String,
    val title: String,
    val brief: String,
    val kind: String,
    val tag: String? = null,
)

private val MODULES = listOf(
    Module("tang_poem", "全唐诗", "《全唐诗》· 补录全收", "category"),
    Module("song_poem", "全宋诗", "两宋诗集大成", "category"),
    Module("ci", "全宋词", "词的黄金时代", "category"),
    Module("yuan_qu", "元曲", "散曲小令 · 杂剧", "category"),
    Module("tang_poem", "唐诗三百首", "家塾课本 · 熟读成诵", "tag", "唐诗三百首"),
    Module("shijing", "诗经", "风雅颂 · 先秦歌谣", "category"),
    Module("chuci", "楚辞", "骚体 · 浪漫之源", "category"),
    Module("guwen", "古文观止", "散文正宗 · 十二卷", "category"),
    Module("qing_ci", "纳兰词", "清词第一", "category"),
    Module("youmengying", "幽梦影", "清言小品 · 带评点", "category"),
    Module("lunyu", "论语", "语录即文章", "category"),
    Module("sishu", "孟子", "雄辩滔滔", "category"),
    Module("han_wei", "曹操诗集", "建安风骨", "category"),
)

@Composable
fun LibraryScreen(onOpenModule: (String) -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var tagCount by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            counts = db.categoryCounts()
            tagCount = db.tagCount("唐诗三百首")
        }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
        } else {
            withContext(Dispatchers.IO) { results = PoemsDb.get(context).search(query) }
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
            placeholder = { Text("搜诗句、题目、作者（如：月落乌啼）") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(14.dp))

        if (query.isBlank()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(MODULES, key = { it.title }) { m ->
                    val count = if (m.kind == "tag") tagCount else counts[m.key] ?: 0
                    ModuleCard(
                        title = m.title,
                        brief = m.brief,
                        count = count,
                        onClick = {
                            onOpenModule(if (m.kind == "tag") "tag:${m.tag}" else m.key)
                        },
                    )
                }
            }
        } else {
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

/**
 * 模块浏览页：key 为 "分类名" 或 "tag:标签名"；大模块分页加载。
 * 路由约定：LibraryScreen 的 onOpenModule 传 "tang_poem" 或 "tag:唐诗三百首"。
 */
@Composable
fun ModuleScreen(key: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val tagMode = key.startsWith("tag:")
    val category = if (tagMode) "tang_poem" else key
    val tag = if (tagMode) key.removePrefix("tag:") else null
    val label = if (tagMode) tag ?: key else MODULES.firstOrNull { it.key == key }?.title ?: key

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
