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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/** 书库模块（京墨式）：一个模块一个门类，点击进入浏览；全局搜索常驻页首 */
private val MODULES = listOf(
    Triple("tang_poem", "唐诗三百首", "家塾课本 · 熟读成诵"),
    Triple("shijing", "诗经", "风雅颂 · 先秦歌谣"),
    Triple("chuci", "楚辞", "骚体 · 浪漫之源"),
    Triple("guwen", "古文观止", "散文正宗 · 十二卷"),
    Triple("qing_ci", "纳兰词", "清词第一"),
    Triple("youmengying", "幽梦影", "清言小品 · 带评点"),
    Triple("lunyu", "论语", "语录即文章"),
    Triple("sishu", "孟子", "雄辩滔滔"),
    Triple("han_wei", "曹操诗集", "建安风骨"),
)

@Composable
fun LibraryScreen(onOpenModule: (String) -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var results by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { counts = PoemsDb.get(context).categoryCounts() }
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
            // 模块宫格
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(MODULES, key = { it.first }) { (key, title, brief) ->
                    ModuleCard(
                        title = title,
                        brief = brief,
                        count = counts[key] ?: 0,
                        onClick = { onOpenModule(key) },
                    )
                }
            }
        } else {
            // 搜索结果
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results.size, key = { results[it].id }) { index ->
                    val p = results[index]
                    PoemRow(p, onClick = { onOpen(p.id) })
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

/** 模块浏览页：单分类列表 */
@Composable
fun ModuleScreen(key: String, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val label = MODULES.firstOrNull { it.first == key }?.second ?: key
    var poems by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }

    LaunchedEffect(key) {
        withContext(Dispatchers.IO) { poems = PoemsDb.get(context).listByCategory(key, 1000, 0) }
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
