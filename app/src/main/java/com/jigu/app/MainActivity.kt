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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jigu.app.data.DailyPush
import com.jigu.app.data.JiguCalendar
import com.jigu.app.data.PoemsDb
import com.jigu.app.ui.library.AuthorIndexScreen
import com.jigu.app.ui.library.AuthorScreen
import com.jigu.app.ui.library.EraAuthorsScreen
import com.jigu.app.ui.library.LibraryScreen
import com.jigu.app.ui.library.ModuleScreen
import com.jigu.app.ui.library.TypeScreen
import com.jigu.app.ui.mine.MineScreen
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
                // 每日推送（默认 07:30）+ 通知权限（Android 13+ 运行时申请）
                DailyPush.schedule(this@MainActivity)
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

    Scaffold(
        bottomBar = {
            NavigationBar(modifier = Modifier.height(64.dp)) {
                NavigationBarItem(
                    selected = currentRoute(nav) == "today",
                    onClick = { nav.navigate("today") { launchSingleTop = true; popUpTo("today") { inclusive = true } } },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("今日") },
                )
                NavigationBarItem(
                    selected = currentRoute(nav) == "library",
                    onClick = { nav.navigate("library") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("书库") },
                )
                NavigationBarItem(
                    selected = currentRoute(nav) == "mine",
                    onClick = { nav.navigate("mine") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    label = { Text("我的") },
                )
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding)) {
            composable("today") { TodayScreen(onOpen = openDetail) }
            composable("library") {
                LibraryScreen(
                    onOpenModule = { key -> nav.navigate("module/$key") },
                    onOpenEra = { era -> nav.navigate("era/$era") },
                    onOpenAuthors = { nav.navigate("authors") },
                    onOpenAuthor = { id -> nav.navigate("author/$id") },
                    onOpenType = { name -> nav.navigate("type/$name") },
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
            composable("mine") { MineScreen() }
            composable("detail/{id}") { back ->
                DetailScreen(
                    id = back.arguments?.getString("id")?.toIntOrNull() ?: 0,
                    onBack = { nav.popBackStack() },
                    onOpen = openDetail,
                )
            }
        }
    }
}

private fun currentRoute(nav: androidx.navigation.NavController): String? =
    nav.currentBackStackEntry?.destination?.route
