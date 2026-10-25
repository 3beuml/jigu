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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jigu.app.data.PoemsDb
import com.jigu.app.ui.theme.PoemTitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CATEGORIES = listOf(
    "tang_poem" to "唐诗三百首",
    "shijing" to "诗经",
    "chuci" to "楚辞",
    "guwen" to "古文观止",
    "qing_ci" to "纳兰词",
    "youmengying" to "幽梦影",
    "lunyu" to "论语",
    "sishu" to "四书",
    "han_wei" to "汉魏诗",
)

/** 书库：分类浏览 + 全文搜索（≥3 字走 FTS trigram，短词走 LIKE 兜底） */
@Composable
fun LibraryScreen(onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(CATEGORIES.first().first) }
    var poems by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }

    LaunchedEffect(query, category) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            poems = if (query.isNotBlank()) db.search(query)
            else db.listByCategory(category, 200, 0)
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
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CATEGORIES.forEach { (key, label) ->
                FilterChip(
                    selected = category == key && query.isBlank(),
                    onClick = { query = ""; category = key },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(poems.size) { index ->
                val p = poems[index]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(p.id) }
                        .padding(vertical = 12.dp),
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
            if (poems.isEmpty()) {
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
