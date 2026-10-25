package com.jigu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 宣纸与墨（D-004 素雅主调），朱红只做点睛
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
private val CinnabarNight = Color(0xFFCE7A6A)

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
    primary = CinnabarNight,
    onPrimary = Color(0xFF2A1310),
    background = NightGround,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurface,
    onSurfaceVariant = NightInkSoft,
    outline = Color(0xFF4E4A3E),
)

/** 诗文正文字体：系统衬线（宋体系），大字宽行距；霞鹜文楷子集在阶段 4 接入 */
val PoemTextStyle = TextStyle(
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.Normal,
    fontSize = 25.sp,
    lineHeight = 46.sp,
)

val PoemTitleStyle = TextStyle(
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.Medium,
    fontSize = 18.sp,
    lineHeight = 26.sp,
)

@Composable
fun JiguTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}
