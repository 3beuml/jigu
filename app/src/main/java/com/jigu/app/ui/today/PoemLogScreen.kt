package com.jigu.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jigu.app.DOCK_CLEARANCE
import com.jigu.app.data.PoemOfDay
import com.jigu.app.data.PoemsDb
import com.jigu.app.ui.detail.paragraphToLines
import com.jigu.app.ui.signatureWithTitle
import com.jigu.app.ui.theme.PoemBodySmallStyle
import com.jigu.app.ui.theme.PoemTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/** 每日一句·诗历（R3 几枝化）：按天倒序回看推送过的句子，点开直达全诗。
 *  数据源 = PoemOfDay 日志（prefs），跨版本 id 漂移的条目自然跳过。 */
@Composable
fun PoemLogScreen(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<Pair<PoemOfDay.LogEntry, PoemsDb.Poem>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            entries = PoemOfDay.history(context).mapNotNull { e ->
                db.poemById(e.poemId)?.let { e to it }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    "每日一句",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${entries.size} 天",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = DOCK_CLEARANCE),
            ) {
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "还没有记录。\n每天打开汲古，当日的一句会留在这里。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                entries.forEach { (e, p) ->
                    item(key = "${e.date.toEpochDay()}_${p.id}") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(p.id) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    e.date.format(DateTimeFormatter.ofPattern("M月d日")),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                e.badge?.let {
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                MaterialTheme.shapes.small,
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            val line = p.famousLine.takeIf { it.length >= 6 }
                                ?: p.paragraphs.firstOrNull().takeIf { !it.isNullOrBlank() && it.length >= 6 }
                                ?: p.paragraphs.joinToString("").takeIf { it.isNotBlank() } ?: ""
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                paragraphToLines(line, maxSentence = 14).forEach { l ->
                                    Text(
                                        text = l,
                                        style = PoemTextStyle,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                signatureWithTitle(p),
                                style = PoemBodySmallStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    }
                }
            }
        }
    }
}
