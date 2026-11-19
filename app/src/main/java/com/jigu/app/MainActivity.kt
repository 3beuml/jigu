package com.jigu.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jigu.app.data.DailyPush
import com.jigu.app.data.JiguCalendar
import com.jigu.app.data.JiguSettings
import com.jigu.app.data.PoemsDb
import com.jigu.app.data.PrepState
import com.jigu.app.ui.library.AuthorIndexScreen
import com.jigu.app.ui.library.AuthorScreen
import com.jigu.app.ui.library.ChapterScreen
import com.jigu.app.ui.library.EraAuthorsScreen
import com.jigu.app.ui.library.FollowsScreen
import com.jigu.app.ui.library.FamousLinesScreen
import com.jigu.app.ui.library.GuwenSourceScreen
import com.jigu.app.ui.library.GuwenSourcesScreen
import com.jigu.app.ui.library.PopularListScreen
import com.jigu.app.ui.library.GenreScreen
import com.jigu.app.ui.favorites.FavoritesScreen
import com.jigu.app.ui.library.LibraryScreen
import com.jigu.app.ui.library.LibrarySection
import com.jigu.app.ui.library.ModuleScreen
import com.jigu.app.ui.library.RhythmicIndexScreen
import com.jigu.app.ui.library.RhythmicListScreen
import com.jigu.app.ui.library.ThemePoemsScreen
import com.jigu.app.ui.library.TypeScreen
import com.jigu.app.ui.mine.MineScreen
import com.jigu.app.ui.share.CardPreviewScreen
import com.jigu.app.ui.theme.JiguTheme
import com.jigu.app.ui.today.TodayScreen
import com.jigu.app.ui.detail.DetailScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var term by remember { mutableStateOf<String?>(null) }
            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(Unit) {
                JiguCalendar.load(this@MainActivity)
                PoemsDb.get(this@MainActivity) // 触发首启拷库
                term = JiguCalendar.currentTerm(java.time.LocalDate.now())
                // 每日推送（用户设定时刻，默认 07:30，可关）+ 通知权限（Android 13+ 运行时申请）
                if (JiguSettings.pushEnabled(this@MainActivity)) {
                    DailyPush.schedule(
                        this@MainActivity,
                        JiguSettings.pushHour(this@MainActivity),
                        JiguSettings.pushMinute(this@MainActivity),
                    )
                } else {
                    DailyPush.cancel(this@MainActivity)
                }
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            JiguTheme(term = term) {
                JiguApp()
            }
        }
    }
}

@Composable
fun JiguApp() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        JiguCalendar.load(context)
        PoemsDb.get(context) // 触发首启拷库
    }

    val nav = rememberNavController()
    val openDetail: (Int) -> Unit = { id -> nav.navigate("detail/$id") }
    // 名句直达（D-043 R1）：带高亮句进详情——hl 经 URL 编码走 query 参数
    val openDetailHighlight: (Int, String) -> Unit = { id, hl ->
        nav.navigate("detail/$id?hl=${android.net.Uri.encode(hl)}")
    }
    // D-038 修复：currentBackStackEntry 直读不具备组合可观察性（route 启动后定格，
    // dock 永显/今日恒选中）——改用 currentBackStackEntryAsState() 订阅导航状态
    val route = nav.currentBackStackEntryAsState().value?.destination?.route

    // 悬浮胶囊 dock（D-025，骨架移植自 ZZUPocket AppNav 0.33.0 方案）：
    // 不再吃内容区——内容全高滚动从胶囊底下穿过，各滚动容器挂 DOCK_CLEARANCE。
    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "today") {
            composable("today") {
                TodayScreen(
                    onOpen = openDetail,
                    onCard = { id -> nav.navigate("card/$id") },
                )
            }
            // 诗词 / 古文分栏（D-030）：原"书库"拆两个顶层 tab，各有专属维度
            composable("poetry") {
                LibraryScreen(
                    section = LibrarySection.POETRY,
                    onOpenModule = { key ->
                        if (key == "famous") nav.navigate("famous")
                        else if (key == "popular") nav.navigate("popular")
                        else nav.navigate("module/$key")
                    },
                    onOpenEra = { era -> nav.navigate("era/$era") },
                    onOpenAuthor = { id -> nav.navigate("author/$id") },
                    onOpenGenre = { name -> nav.navigate("genre/$name") },
                    onOpenType = { name ->
                        // 词/曲不再直开平列表（词 2.2 万/曲 1.1 万），先过词牌/曲牌索引（批3）
                        if (name == "词" || name == "曲") nav.navigate("rhythmic_index/$name")
                        else nav.navigate("type/$name")
                    },
                    onOpenTheme = { name -> nav.navigate("theme/$name") },
                    onOpenChapter = { chapter -> nav.navigate("chapter/$chapter") },
                    onOpen = openDetail,
                    onOpenHighlight = openDetailHighlight,
                )
            }
            composable("guwen") {
                LibraryScreen(
                    section = LibrarySection.PROSE,
                    onOpenModule = { key ->
                        when (key) {
                            "famous" -> nav.navigate("famous")
                            // 经史名段（D-032）：观止出处聚合，非 module 卡
                            "guwen_sources" -> nav.navigate("guwen_sources")
                            else -> nav.navigate("module/$key")
                        }
                    },
                    onOpenEra = { era -> nav.navigate("era/$era") },
                    onOpenAuthor = { id -> nav.navigate("author/$id") },
                    onOpenGenre = { name -> nav.navigate("genre/$name") },
                    onOpenType = { name ->
                        if (name == "词" || name == "曲") nav.navigate("rhythmic_index/$name")
                        else nav.navigate("type/$name")
                    },
                    onOpenTheme = { name -> nav.navigate("theme/$name") },
                    onOpenChapter = { chapter -> nav.navigate("chapter/$chapter") },
                    onOpen = openDetail,
                    onOpenHighlight = openDetailHighlight,
                )
            }
            composable("guwen_sources") {
                GuwenSourcesScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSource = { name -> nav.navigate("guwen_source/$name") },
                )
            }
            composable("guwen_source/{name}") { back ->
                GuwenSourceScreen(
                    source = back.arguments?.getString("name") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("chapter/{chapter}") { back ->
                ChapterScreen(
                    chapter = back.arguments?.getString("chapter") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("genre/{name}") { back ->
                GenreScreen(
                    genre = back.arguments?.getString("name") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("theme/{name}") { back ->
                ThemePoemsScreen(
                    theme = back.arguments?.getString("name") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("favorites") {
                FavoritesScreen(
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("module/{key}") { back ->
                ModuleScreen(
                    key = back.arguments?.getString("key") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("famous") {
                FamousLinesScreen(
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("popular") {
                PopularListScreen(
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("era/{era}") { back ->
                EraAuthorsScreen(
                    era = back.arguments?.getString("era") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpenAuthor = { id -> nav.navigate("author/$id") },
                )
            }
            composable("authors") {
                AuthorIndexScreen(
                    onBack = { nav.popBackStack() },
                    onOpenAuthor = { id -> nav.navigate("author/$id") },
                )
            }
            composable("author/{id}") { back ->
                AuthorScreen(
                    authorId = back.arguments?.getString("id")?.toIntOrNull() ?: 0,
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("type/{name}") { back ->
                TypeScreen(
                    type = back.arguments?.getString("name") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("rhythmic_index/{type}") { back ->
                RhythmicIndexScreen(
                    type = back.arguments?.getString("type") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpenRhythmic = { name ->
                        val type = back.arguments?.getString("type") ?: ""
                        nav.navigate("rhythmic_list/$type/$name")
                    },
                )
            }
            composable("rhythmic_list/{type}/{name}") { back ->
                RhythmicListScreen(
                    type = back.arguments?.getString("type") ?: "",
                    rhythmic = back.arguments?.getString("name") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
            composable("mine") {
                MineScreen(
                    onOpenFavorites = { nav.navigate("favorites") },
                    onOpenFamous = { nav.navigate("famous") },
                    onOpenFollows = { nav.navigate("follows") },
                )
            }
            // 关注诗人列表（D-043 R2）：独立页，作者名下直达诗人主页
            composable("follows") {
                FollowsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenAuthor = { id -> nav.navigate("author/$id") },
                )
            }
            composable("card/{id}") { back ->
                CardPreviewScreen(
                    id = back.arguments?.getString("id")?.toIntOrNull() ?: 0,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("detail/{id}?hl={hl}") { back ->
                DetailScreen(
                    id = back.arguments?.getString("id")?.toIntOrNull() ?: 0,
                    highlight = back.arguments?.getString("hl") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
        }

        // ② 悬浮纸签条 dock 覆盖层：宣纸底 + 细墨线描边 + 朱点选中态（D-004 彩色秩序：dock 零彩色）。
        // 签条以外的带子只吞 tap（不进语义树、不放行 move）——列表滑动穿透、留白不误触。
        // dock 只住四个顶层 tab（D-030：今日/诗词/古文/我的）：子页面有各自返回骨架，
        // 常驻签条反而占底部、碍返回视线；全屏体验页同理不显示。
        // 出入场动效：进子页滑出、返回滑入——"书签回来"而非"突然在"（D-025 终版）
        androidx.compose.animation.AnimatedVisibility(
            visible = route in setOf("today", "poetry", "guwen", "mine"),
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) +
                androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) +
                androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
        DockOverlay(
            currentRoute = route,
            onToday = { nav.navigate("today") { launchSingleTop = true; popUpTo("today") { inclusive = true } } },
            onPoetry = { nav.navigate("poetry") { launchSingleTop = true } },
            onGuwen = { nav.navigate("guwen") { launchSingleTop = true } },
            onMine = { nav.navigate("mine") { launchSingleTop = true } },
        )
        }

        // 书库准备中/失败覆盖层（D-034"空入口"误判的结构性修复）：首启/升级解压约 700MB 期间
        // 各页查询阻塞在 awaitData，列表会是空的——给明示而非空白。日常秒开（Checking→Ready
        // 快于 400ms）不显示，避免闪一下。
        val prep by PoemsDb.prepState.collectAsState()
        var showPrep by remember { mutableStateOf(false) }
        LaunchedEffect(prep) {
            when (prep) {
                PrepState.Copying -> {
                    kotlinx.coroutines.delay(400)
                    showPrep = true
                }
                PrepState.Failed -> showPrep = true
                else -> showPrep = false
            }
        }
        if (showPrep) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) { detectTapGestures { /* 准备期间吞掉全部点击 */ } },
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "汲古",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(24.dp))
                    if (prep == PrepState.Failed) {
                        Text(
                            text = "书库准备失败",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "请重启应用；若仍失败，请卸载重装",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = "正在整理书库…",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "首次启动需解压约 700MB 书库，请稍候",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 悬浮纸签条 dock：覆盖层对齐底部，滚动内容从签条底下穿过（D-025；四栏见 D-030）。 */
@Composable
private fun DockOverlay(
    modifier: Modifier = Modifier,
    currentRoute: String?,
    onToday: () -> Unit,
    onPoetry: () -> Unit,
    onGuwen: () -> Unit,
    onMine: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures { /* 消费 tap，不下传；move 放行给滚动 */ }
            },
    ) {
        // 贴底固定条（D-031，参照小红书）：全宽、无左右边距、宣纸底沉到手势条背后，
        // 图标行坐在导航手势区上方；顶部发丝分隔线替代原纸签条描边
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.75.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.9f)),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        // 手势区：图标行避开导航手势条，宣纸底延伸到屏底
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                        )
                        .height(58.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                listOf(
                    DockTab("今日", "today", onToday),
                    DockTab("诗词", "poetry", onPoetry),
                    DockTab("古文", "guwen", onGuwen),
                    DockTab("我的", "mine", onMine),
                ).forEach { tab ->
                    val selected = currentRoute == tab.routeKey
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(onClick = tab.onClick),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val iconColor = if (selected) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant
                        // 墨线 Canvas 直绘（D-025 终版）：选中=墨形加重
                        Canvas(Modifier.size(22.dp)) {
                            val sc = size.minDimension / 24f
                            withTransform({ scale(sc, sc, pivot = Offset.Zero) }) {
                                drawPath(
                                    path = inkPath(tab.routeKey),
                                    color = iconColor,
                                    style = Stroke(
                                        width = if (selected) 2.4f else 1.6f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                )
                            }
                        }
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 朱点：落款性质的用朱（3dp），只给选中项——D-004 彩色秩序内
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier
                                .size(3.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) Color(0xFF9E2F26) else Color.Transparent,
                                ),
                        )
                    }
                }
                }
            }
        }
    }
}

/** dock 项：label + 路由 + 点击（D-030 四栏）。 */
private data class DockTab(val label: String, val routeKey: String, val onClick: () -> Unit)

/** 墨线四形（D-030）：印（今日）/诗行（诗词）/韦编简册（古文）/人（我的），24 视窗单位坐标 */
private fun inkPath(routeKey: String): Path = Path().apply {
    when (routeKey) {
        "today" -> {
            moveTo(5f, 3.8f); lineTo(19f, 3.8f); lineTo(19f, 20.2f); lineTo(5f, 20.2f); close()
            moveTo(5f, 12f); lineTo(19f, 12f)
        }
        // 诗行：竖排诗句，长短参差（D-030）
        "poetry" -> {
            moveTo(7f, 5f); lineTo(7f, 19f)
            moveTo(12f, 8f); lineTo(12f, 16f)
            moveTo(17f, 4f); lineTo(17f, 14f)
        }
        // 韦编简册：四简 + 上下两道编绳——"册"字本形（D-030）
        "guwen" -> {
            moveTo(5f, 4.6f); lineTo(5f, 19.4f)
            moveTo(9.7f, 4.6f); lineTo(9.7f, 19.4f)
            moveTo(14.3f, 4.6f); lineTo(14.3f, 19.4f)
            moveTo(19f, 4.6f); lineTo(19f, 19.4f)
            moveTo(4f, 8.2f); lineTo(20f, 8.2f)
            moveTo(4f, 15.8f); lineTo(20f, 15.8f)
        }
        else -> {
            moveTo(12f, 4.6f); cubicTo(14f, 4.6f, 15.6f, 6.2f, 15.6f, 8.2f)
            cubicTo(15.6f, 10.2f, 14f, 11.8f, 12f, 11.8f)
            cubicTo(10f, 11.8f, 8.4f, 10.2f, 8.4f, 8.2f)
            cubicTo(8.4f, 6.2f, 10f, 4.6f, 12f, 4.6f); close()
            moveTo(4.6f, 20.4f); cubicTo(4.6f, 15.8f, 7.9f, 13.6f, 12f, 13.6f)
            cubicTo(16.1f, 13.6f, 19.4f, 15.8f, 19.4f, 20.4f)
        }
    }
}

val DOCK_CLEARANCE = 88.dp

