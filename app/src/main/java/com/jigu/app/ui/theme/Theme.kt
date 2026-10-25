package com.jigu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jigu.app.R

// 诗文正文字体：霞鹜文楷子集（tools/fonts/subset_font.py 按语料裁剪），缺字走系统衬线兜底
val Wenkai = FontFamily(Font(R.font.wenkai_regular))

val PoemTextStyle = TextStyle(
    fontFamily = Wenkai,
    fontWeight = FontWeight.Normal,
    fontSize = 25.sp,
    lineHeight = 46.sp,
)

val PoemTitleStyle = TextStyle(
    fontFamily = Wenkai,
    fontWeight = FontWeight.Medium,
    fontSize = 18.sp,
    lineHeight = 26.sp,
)

// 宣纸与墨（D-004 素雅主调），主色随节气换传统色（D-004 节气换肤），印章朱红保持品牌不变
private val XuanPaper = Color(0xFFF7F3EA)
private val XuanPaperDim = Color(0xFFEFE9DC)
private val Ink = Color(0xFF23211C)
private val InkSoft = Color(0xFF5C574C)
private val Cinnabar = Color(0xFF9E2F26)
private val OnCinnabar = Color(0xFFFFF6F2)

private val NightGround = Color(0xFF16140F)
private val NightSurface = Color(0xFF201D16)
private val NightInk = Color(0xFFE9E2D2)
private val NightInkSoft = Color(0xFFA69E8C)

private val LightScheme = lightColorScheme(
    primary = Cinnabar,
    onPrimary = OnCinnabar,
    background = XuanPaper,
    onBackground = Ink,
    surface = XuanPaper,
    onSurface = Ink,
    surfaceVariant = XuanPaperDim,
    onSurfaceVariant = InkSoft,
    outline = Color(0xFFC6BEAC),
)

private val DarkScheme = darkColorScheme(
    primary = Cinnabar,
    onPrimary = Color(0xFF2A1310),
    background = NightGround,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurface,
    onSurfaceVariant = NightInkSoft,
    outline = Color(0xFF4E4A3E),
)

/** 节气 -> (亮色主色, 暗色主色)，两节气一组共十二组，皆为低饱和传统色 */
private val TERM_TONES: Map<String, Pair<Color, Color>> = mapOf(
    "立春" to (Color(0xFF4E8172) to Color(0xFF82B39F)),   // 苍筤
    "雨水" to (Color(0xFF4E8172) to Color(0xFF82B39F)),
    "惊蛰" to (Color(0xFFB4637A) to Color(0xFFE39AAB)),   // 桃夭
    "春分" to (Color(0xFFB4637A) to Color(0xFFE39AAB)),
    "清明" to (Color(0xFF5E8C4A) to Color(0xFF93BD7E)),   // 柳绿
    "谷雨" to (Color(0xFF5E8C4A) to Color(0xFF93BD7E)),
    "立夏" to (Color(0xFF4A8A6E) to Color(0xFF7CB8A0)),   // 梅子青
    "小满" to (Color(0xFF4A8A6E) to Color(0xFF7CB8A0)),
    "芒种" to (Color(0xFF38688C) to Color(0xFF6F9CBE)),   // 石青
    "夏至" to (Color(0xFF38688C) to Color(0xFF6F9CBE)),
    "小暑" to (Color(0xFFB04F3E) to Color(0xFFDE8A74)),   // 朱明
    "大暑" to (Color(0xFFB04F3E) to Color(0xFFDE8A74)),
    "立秋" to (Color(0xFF998A3D) to Color(0xFFC4B56E)),   // 秋香
    "处暑" to (Color(0xFF998A3D) to Color(0xFFC4B56E)),
    "白露" to (Color(0xFF5C7C99) to Color(0xFF93AFC7)),   // 月白
    "秋分" to (Color(0xFF5C7C99) to Color(0xFF93AFC7)),
    "寒露" to (Color(0xFF8A7A55) to Color(0xFFB8A87E)),   // 缃叶
    "霜降" to (Color(0xFF8A7A55) to Color(0xFFB8A87E)),
    "立冬" to (Color(0xFF4A5A78) to Color(0xFF7D8FAE)),   // 黛蓝
    "小雪" to (Color(0xFF4A5A78) to Color(0xFF7D8FAE)),
    "大雪" to (Color(0xFF3C4A66) to Color(0xFF6F7FA0)),   // 玄青
    "冬至" to (Color(0xFF3C4A66) to Color(0xFF6F7FA0)),
    "小寒" to (Color(0xFF6E7B8E) to Color(0xFF9FACC0)),   // 素采
    "大寒" to (Color(0xFF6E7B8E) to Color(0xFF9FACC0)),
)

@Composable
fun JiguTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    term: String? = null,
    content: @Composable () -> Unit,
) {
    val tone = term?.let { TERM_TONES[it] }
    val primary = tone?.let { if (darkTheme) it.second else it.first } ?: Cinnabar
    val base = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = base.copy(
            primary = primary,
            onPrimary = if (darkTheme) Color(0xFF14110C) else OnCinnabar,
        ),
        typography = Typography(),
        content = content,
    )
}
