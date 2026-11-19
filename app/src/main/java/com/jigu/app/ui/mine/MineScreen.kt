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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedTextField
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
import com.jigu.app.data.MoodProfiles
import com.jigu.app.data.WeatherService
import com.jigu.app.data.PoemsDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 我的：收藏/名句入口、今日诗偏好、真实设置开关、数据信息、致谢与许可 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MineScreen(
    onOpenFavorites: () -> Unit,
    onOpenFamous: () -> Unit,
    onOpenFollows: () -> Unit = {},
) {
    val context = LocalContext.current
    var dataInfo by remember { mutableStateOf("…") }
    var version by remember { mutableStateOf("…") }
    var favCount by remember { mutableIntStateOf(0) }
    var pushOn by remember { mutableStateOf(JiguSettings.pushEnabled(context)) }
    var pushHour by remember { mutableIntStateOf(JiguSettings.pushHour(context)) }
    var pushMinute by remember { mutableIntStateOf(JiguSettings.pushMinute(context)) }
    var showTimePicker by remember { mutableStateOf(false) }
    // 今日诗偏好（D-030）：题材多选，pick 时优选偏好题材池
    var allThemes by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var preferred by remember { mutableStateOf(JiguSettings.preferredThemes(context)) }
    var moods by remember { mutableStateOf(JiguSettings.moodTags(context)) }
    var status by remember { mutableStateOf(JiguSettings.lifeStatus(context)) }
    var city by remember { mutableStateOf(JiguSettings.weatherCity(context)) }
    var weatherInfo by remember { mutableStateOf<String?>(null) }

    fun reloadPush() {
        if (pushOn) DailyPush.schedule(context, pushHour, pushMinute)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            val ver = db.meta("data_version") ?: "?"
            // 只显版本号与总篇数，不再裸显 counts JSON（P2-3）
            dataInfo = "数据版本 $ver · 收录 ${db.count()} 篇"
            version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }.getOrDefault("?")
            favCount = db.favoriteIds().size
            allThemes = db.themeCounts().entries.sortedByDescending { it.value }.map { it.key to it.value }
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

        // 收藏 / 名句入口（名句卡移自书架，D-030）；关注诗人（D-043 R2）
        EntryRow("我的收藏", "$favCount 篇", onOpenFavorites)
        EntryRow("名句", "千古名句 · 四百条", onOpenFamous)
        EntryRow("关注的诗人", "诗人主页点「关注」", onOpenFollows)
        Spacer(Modifier.height(20.dp))

        Info("数据", dataInfo)
        Spacer(Modifier.height(20.dp))

        // 真实开关：每日推送 + 推送时间（批11：画意/正文背景画随赏画体系拆除）
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

        // 今日诗偏好（D-030）：题材多选，换一首/推送/小部件同源生效
        Text("今日诗偏好", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            if (preferred.isEmpty()) "不勾选 = 随缘（默认全池轮转）" else "今日将优先在所选题材内轮转 · 已选 ${preferred.size} 类",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // 30 类按热度排布，FlowRow 多选 chips
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            allThemes.forEach { (name, count) ->
                val on = name in preferred
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    onClick = {
                        val next = if (on) preferred - name else preferred + name
                        preferred = next
                        JiguSettings.setPreferredThemes(context, next)
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$count",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // R4 情境（D-045）：心情多选 + 最近状态单选
        Text("心情 · 状态", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "心情并入今日题材池；最近状态为持续偏向（可随时改回默认）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MoodProfiles.MOODS.forEach { (label, _) ->
                val on = label in moods
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    onClick = {
                        val next = if (on) moods - label else moods + label
                        moods = next
                        JiguSettings.setMoodTags(context, next)
                    },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MoodProfiles.STATUS.forEach { (label, _) ->
                val on = label == status
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    onClick = {
                        status = label
                        JiguSettings.setLifeStatus(context, label)
                    },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // R4 天气（可选联网例外）：城市留空 = 纯离线
        Text("天气配诗（可选联网）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "填城市名后每日拉取当地天气，雨雪晴阴配不同诗；留空 = 纯离线、一次请求都不发",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = city,
                onValueChange = { city = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("如：杭州（留空关闭）") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = {
                JiguSettings.setWeatherCity(context, city)
                WeatherService.refreshAsync(context)
                weatherInfo = if (city.isBlank()) "已关闭天气配诗" else "已保存，正在拉取天气…"
            }) { Text("保存") }
        }
        weatherInfo?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))

        Info("数据来源", "chinese-poetry（MIT）· cnlunar（MIT）· 霞鹜文楷（OFL）")
        Spacer(Modifier.height(8.dp))
        Info("字体", "霞鹜文楷子集（OFL）+ Jigmo 补生僻字（MIT）+ 系统衬线兜底")
        Spacer(Modifier.height(8.dp))
        Info("许可", "代码 Apache-2.0；各来源署名见上方与数据包 meta")
        Spacer(Modifier.height(8.dp))
        Info("隐私", "默认全离线不收集数据；唯一联网例外 = 你主动填写天气城市（只请求天气）")
        Spacer(Modifier.height(com.jigu.app.DOCK_CLEARANCE))
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
private fun EntryRow(title: String, note: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
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
