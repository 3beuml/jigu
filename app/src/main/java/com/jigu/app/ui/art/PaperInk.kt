package com.jigu.app.ui.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

/** 纸墨融合运行时工具（D-019）。
 *  纸墨映射：out = 宣纸 + (墨-宣纸)/255 × 亮度(R,G,B)，仿射 → 一个 ColorMatrix；
 *  边缘羽化：四边线性 alpha 遮罩，Compose 走 DstIn、Canvas 走 DST_OUT。 */
object PaperInk {

    const val PAPER = 0xFFF7F3EA.toInt() // 宣纸底（与 PoemCard 同源）
    const val INK = 0xFF23211C.toInt() // 浓墨（卡片画心/正文）
    const val INK_SOFT = 0xFF565044.toInt() // 淡墨（今日角隅，让位文字）
    const val SEAL = 0xFF9E2F26.toInt() // 朱砂

    /** 纸墨映射矩阵系数（Compose 与 android.graphics 共用布局：4 行 × [R G B A offset]） */
    private fun matrix(ink: Int): FloatArray {
        val pr = (PAPER shr 16) and 0xFF
        val pg = (PAPER shr 8) and 0xFF
        val pb = PAPER and 0xFF
        val ir = (ink shr 16) and 0xFF
        val ig = (ink shr 8) and 0xFF
        val ib = ink and 0xFF
        val kr = (ir - pr) / 255f
        val kg = (ig - pg) / 255f
        val kb = (ib - pb) / 255f
        return floatArrayOf(
            0.299f * kr, 0.587f * kr, 0.114f * kr, 0f, pr.toFloat(),
            0.299f * kg, 0.587f * kg, 0.114f * kg, 0f, pg.toFloat(),
            0.299f * kb, 0.587f * kb, 0.114f * kb, 0f, pb.toFloat(),
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** 纸墨映射滤镜（Canvas Paint 用）。ink 为墨端色，纸端恒为宣纸色。 */
    fun colorFilter(ink: Int): ColorMatrixColorFilter =
        ColorMatrixColorFilter(ColorMatrix(matrix(ink)))

    /** 纸墨映射滤镜（Compose Image 用） */
    fun composeColorFilter(ink: Int): ColorFilter =
        ColorFilter.colorMatrix(ComposeColorMatrix(matrix(ink)))

    private val masks = object : LruCache<String, Bitmap>(8) {}

    /** 四边线性羽化 alpha 遮罩：中心不透明，边缘 fadePx 内线性淡出（Compose DstIn 用） */
    fun featherMask(w: Int, h: Int, fadePx: Int): Bitmap {
        val key = "${w}x${h}x$fadePx"
        masks.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.drawColor(0xFF000000.toInt(), PorterDuff.Mode.SRC) // 先填满不透明
        val fade = fadePx.coerceAtMost(minOf(w, h) / 3).toFloat()
        val fw = w.toFloat()
        val fh = h.toFloat()
        val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, rx0: Float, ry0: Float, rx1: Float, ry1: Float) {
            eraser.shader = LinearGradient(x0, y0, x1, y1, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(rx0, ry0, rx1, ry1, eraser)
        }
        edge(0f, 0f, 0f, fade, 0f, 0f, fw, fade) // 左
        edge(fw, 0f, fw, fade, fw - fade, 0f, fw, fade) // 右
        edge(0f, 0f, fade, 0f, 0f, 0f, fw, fade) // 上
        edge(0f, fh, fade, fh, 0f, fh - fade, fw, fh) // 下
        masks.put(key, bmp)
        return bmp
    }

    private val featheredCache = object : LruCache<String, Bitmap>(6) {}

    /** Canvas 用：返回四边羽化后的位图副本（淡墨映射 + 羽化一次完成） */
    fun featheredInk(src: Bitmap, ink: Int, fadePx: Int, key: String): Bitmap {
        featheredCache.get(key)?.let { return it }
        // 1) 纸墨映射到临时位图
        val tmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(tmp).drawBitmap(
            src, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = colorFilter(ink)
            },
        )
        // 2) 四边 DST_OUT 渐变擦出羽化
        val out = tmp
        val c = Canvas(out)
        val fade = fadePx.coerceAtMost(minOf(out.width, out.height) / 3).toFloat()
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, rx0: Float, ry0: Float, rx1: Float, ry1: Float) {
            eraser.shader = LinearGradient(x0, y0, x1, y1, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(rx0, ry0, rx1, ry1, eraser)
        }
        edge(0f, 0f, 0f, fade, 0f, 0f, w, fade) // 左
        edge(w, 0f, w, fade, w - fade, 0f, w, fade) // 右
        edge(0f, 0f, fade, 0f, 0f, 0f, w, fade) // 上
        edge(0f, h, fade, h, 0f, h - fade, w, h) // 下
        featheredCache.put(key, out)
        return out
    }
}

/** 画心羽化：内容先画入离屏层，再以四边透明渐变 DstIn 收边（D-019 画融纸面） */
fun Modifier.paperFeather(fadeFraction: Float = 0.14f): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val w = size.width
        val h = size.height
        if (w < 8f || h < 8f) return@drawWithContent
        val fx = (w * fadeFraction).coerceAtMost(w / 3f)
        val fy = (h * fadeFraction).coerceAtMost(h / 3f)
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, (fx / w) to Color.Black, 1f to Color.Black),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.horizontalGradient(0f to Color.Black, ((w - fx) / w) to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, (fy / h) to Color.Black, 1f to Color.Black),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.verticalGradient(0f to Color.Black, ((h - fy) / h) to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
