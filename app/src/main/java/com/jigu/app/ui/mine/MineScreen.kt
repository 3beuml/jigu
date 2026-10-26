package com.jigu.app.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row as LayoutRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import com.jigu.app.data.DailyPush
import com.jigu.app.data.JiguSettings
import com.jigu.app.data.PoemsDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 我的：收藏入口、真实设置开关、数据信息、致谢与许可 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(onOpenFavorites: () -> Unit) {
    val context = LocalContext.current
    var dataInfo by remember { mutableStateOf("…") }
    var version by remember { mutableStateOf("…") }
    var favCount by remember { mutableIntStateOf(0) }
    var pushOn by remember { mutableStateOf(JiguSettings.pushEnabled(context)) }
    var pushHour by remember { mutableIntStateOf(JiguSettings.pushHour(context)) }
    var pushMinute by remember { mutableIntStateOf(JiguSettings.pushMinute(context)) }
    var showTimePicker by remember { mutableStateOf(false) }

    fun reloadPush() {
        if (pushOn) DailyPush.schedule(context, pushHour, pushMinute)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            val counts = db.meta("counts") ?: "{}"
            val ver = db.meta("data_version") ?: "?"
            dataInfo = "数据版本 $ver · $counts"
            version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }.getOrDefault("?")
            favCount = db.favoriteIds().size
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("汲古", style = MaterialTheme.typography.headlineMedium)
        Text(
            "汲古得修绠 · v$version",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        // 收藏入口
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenFavorites),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("我的收藏", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "$favCount 篇",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        Info("数据", dataInfo)
        Spacer(Modifier.height(20.dp))

        // 真实开关：画意 / 每日推送 + 推送时间
        var artOn by remember { mutableStateOf(JiguSettings.artEnabled(context)) }
        ToggleRow(
            title = "画意",
            note = "界面淡入节气配画；关闭则为纯文字",
            checked = artOn,
            onChecked = {
                artOn = it
                JiguSettings.setArtEnabled(context, it)
            },
        )
        ToggleRow(
            title = "每日推送",
            note = if (pushOn) "每天 ${"%02d".format(pushHour)}:${"%02d".format(pushMinute)} 推送今日名句" else "已关闭",
            checked = pushOn,
            onChecked = {
                pushOn = it
                JiguSettings.setPushEnabled(context, it)
                reloadPush()
            },
        )
        if (pushOn) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("推送时间", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "%02d:%02d".format(pushHour, pushMinute),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = { showTimePicker = true }),
                )
            }
            Spacer(Modifier.height(14.dp))
        }
        Setting("桌面小部件", "桌面长按添加「汲古 · 今日一首」")
        Spacer(Modifier.height(24.dp))

        Info("数据来源", "chinese-poetry（MIT）· cnlunar（MIT）· 霞鹜文楷（OFL）· 节气配画：克利夫兰艺术博物馆（CC0）")
        Spacer(Modifier.height(8.dp))
        Info("字体", "霞鹜文楷子集（OFL）+ Jigmo 补生僻字（MIT）+ 系统衬线兜底")
        Spacer(Modifier.height(8.dp))
        Info("许可", "代码 Apache-2.0；各来源署名见上方与数据包 meta")
        Spacer(Modifier.height(8.dp))
        Info("隐私", "全离线，不联网、不收集任何数据")
        Spacer(Modifier.height(32.dp))
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(
            initialHour = pushHour, initialMinute = pushMinute, is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pushHour = state.hour
                    pushMinute = state.minute
                    JiguSettings.setPushTime(context, state.hour, state.minute)
                    reloadPush()
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
private fun Info(label: String, content: String) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }
}

@Composable
private fun Setting(title: String, note: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ToggleRow(title: String, note: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    LayoutRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
    Spacer(Modifier.height(14.dp))
}
