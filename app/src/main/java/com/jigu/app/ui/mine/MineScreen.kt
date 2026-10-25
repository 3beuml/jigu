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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 我的：版本与数据信息、致谢、许可；设置项为阶段 4-5 占位 */
@Composable
fun MineScreen() {
    val context = LocalContext.current
    var dataInfo by remember { mutableStateOf("…") }
    var version by remember { mutableStateOf("…") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            val counts = db.meta("counts") ?: "{}"
            val ver = db.meta("data_version") ?: "?"
            dataInfo = "精选库数据版本 $ver · $counts"
            version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }.getOrDefault("?")
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
        Info("数据", dataInfo)
        Spacer(Modifier.height(20.dp))

        Setting("节气换肤", "阶段 4 上线")
        Setting("每日推送", "阶段 5 上线")
        Setting("桌面小部件", "阶段 5 上线")
        Spacer(Modifier.height(24.dp))

        Info("数据来源", "chinese-poetry（MIT）· 维基文库（CC BY-SA）· 台北故宫 Open Data · cnlunar")
        Spacer(Modifier.height(8.dp))
        Info("字体", "系统衬线（宋体系）；霞鹜文楷子集阶段 4 接入")
        Spacer(Modifier.height(8.dp))
        Info("许可", "代码 Apache-2.0；各来源署名见上方与数据包 meta")
        Spacer(Modifier.height(8.dp))
        Info("隐私", "全离线，不联网、不收集任何数据")
        Spacer(Modifier.height(32.dp))
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
