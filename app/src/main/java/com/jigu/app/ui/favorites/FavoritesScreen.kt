package com.jigu.app.ui.favorites

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import com.jigu.app.DOCK_CLEARANCE
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jigu.app.data.PoemsDb
import com.jigu.app.ui.library.PoemRow
import com.jigu.app.ui.theme.PoemBodySmallStyle
import com.jigu.app.ui.theme.PoemTitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 收藏列表：我的页入口进来，点 X 移除收藏 */
@Composable
fun FavoritesScreen(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var poems by remember { mutableStateOf<List<PoemsDb.Poem>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            poems = db.poemsByIds(db.favoriteIds())
        }
    }

    // 导出/导入状态行 4 秒后自清
    LaunchedEffect(status) {
        if (status != null) {
            kotlinx.coroutines.delay(4000)
            status = null
        }
    }

    // 收藏备份（坑9：换 release 签名须卸载、收藏将清空——先导出再换包）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching {
                        val db = PoemsDb.get(context)
                        val json = db.exportFavoritesJson()
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("无法写入所选文件")
                        "已导出 ${db.favoriteIds().size} 条收藏"
                    }.getOrElse { "导出失败：${it.message ?: it.javaClass.simpleName}" }
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("无法读取所选文件")
                        val r = PoemsDb.get(context).importFavoritesJson(text)
                        "导入完成：新增 ${r.added}，已在收藏 ${r.existed}，未找到 ${r.skipped}"
                    }.getOrElse { "导入失败：${it.message ?: it.javaClass.simpleName}" }
                }
                version += 1
            }
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
                text = "我的收藏",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                exportLauncher.launch("jigu-favorites-${java.time.LocalDate.now()}.json")
            }) { Text("导出", fontSize = 14.sp) }
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                Text("导入", fontSize = 14.sp)
            }
        }
        status?.let {
            Text(
                text = it,
                style = PoemBodySmallStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DOCK_CLEARANCE)) {
            items(poems.size, key = { poems[it].id }) { index ->
                val p = poems[index]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpen(p.id) },
                    ) {
                        Text(
                            text = p.title,
                            style = PoemTitleStyle,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 20.dp),
                        )
                        Text(
                            text = "${p.author} ｜ " + p.body.replace('\n', ' ').take(30),
                            style = PoemBodySmallStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp),
                        )
                    }
                    IconButton(onClick = {
                        PoemsDb.get(context).toggleFavorite(p.id)
                        version += 1
                    }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除收藏",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (poems.isEmpty()) {
                item {
                    Text(
                        text = "还没有收藏。今日页点「收藏」试试。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 32.dp, start = 20.dp),
                    )
                }
            }
        }
    }
}
