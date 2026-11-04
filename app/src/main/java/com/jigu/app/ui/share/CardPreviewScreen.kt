package com.jigu.app.ui.share

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jigu.app.data.ArtworkRepo
import com.jigu.app.data.JiguCalendar
import com.jigu.app.data.PoemsDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** 做卡片预览页（P0-1 附属）：先出图，用户看过再分享，不再直接弹系统分享面板 */
@Composable
fun CardPreviewScreen(id: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var cardFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            val db = PoemsDb.get(context)
            db.poemById(id)?.let { poem ->
                val art = ArtworkRepo.forPoem(context, PoemsDb.get(context).themesFor(poem.id), JiguCalendar.currentTerm(LocalDate.now()), poem.id)
                runCatching { PoemCard.render(context, poem, art) }.getOrNull()
            }?.also {
                cardFile = it
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "做卡片",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(
                onClick = { cardFile?.let { PoemCard.share(context, it) } },
                enabled = cardFile != null,
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp),
            ) {
                Text("分享", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val file = cardFile
            if (file == null) {
                CircularProgressIndicator()
            } else {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "分享诗卡预览",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 16.dp),
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    Text("卡片生成失败", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
