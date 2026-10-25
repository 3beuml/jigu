package com.jigu.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.jigu.app.data.PoemOfDay
import com.jigu.app.data.PoemsDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 桌面「今日一首」widget：名句 + 出处，点击进 App。
 *  注：Glance 的 TextStyle.fontFamily 只接受系统字族名（如 "serif"），
 *  文楷在 widget 暂用系统衬线替代； GlanceModifier.defaultWeight 代替 weight。 */
class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) {
            runCatching { PoemOfDay.pick(PoemsDb.get(context), LocalDate.now()) }.getOrNull()
        }
        provideContent {
            GlanceTheme {
                WidgetContent(
                    data?.let {
                        Triple(
                            it.poem.famousLine.ifBlank { it.poem.paragraphs.firstOrNull().orEmpty() },
                            it.poem.author,
                            it.badge,
                        )
                    },
                )
            }
        }
    }

    @Composable
    private fun WidgetContent(data: Triple<String, String, String?>?) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(2.dp)
                .cornerRadius(16.dp)
                .background(ColorProvider(Color(0xFFF7F3EA)))
                .clickable(actionRunCallback<OpenAppAction>(actionParametersOf())),
            contentAlignment = Alignment.Center,
        ) {
            if (data == null) {
                Text(
                    "汲古",
                    style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp),
                )
                return@Box
            }
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                data.third?.let { badge ->
                    Text(
                        "今日 · $badge",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF8A7A55)),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Serif,
                        ),
                    )
                    Spacer(GlanceModifier.height(4.dp))
                }
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = data.first,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF23211C)),
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Serif,
                    ),
                    maxLines = 4,
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = "—— ${data.second}",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF5C574C)),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Serif,
                    ),
                )
            }
        }
    }
}

class OpenAppAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.startActivity(
            Intent(context, com.jigu.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
