package com.jigu.app.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jigu.app.R
import com.jigu.app.data.ArtworkRepo
import com.jigu.app.data.PoemsDb
import java.io.File

/** 分享卡片：Canvas 渲染宣纸界格 + 名句 + 朱印，走 FileProvider 分享 */
object PoemCard {

    private const val W = 1080
    private const val H = 1620

    fun render(context: Context, poem: PoemsDb.Poem, artwork: ArtworkRepo.Artwork? = null): File {
        val tf = Typeface.create(
            ResourcesCompat_typeface(context),
            Typeface.NORMAL,
        )
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 宣纸底
        val paper = Paint().apply { color = 0xFFF7F3EA.toInt() }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paper)

        // 配画：右下角，淡墨处理（低透明 + 纸色渐隐罩）
        val artBmp = artwork?.let { ArtworkRepo.decode(context, it) }
        if (artBmp != null) {
            val aw = 620f
            val ah = aw * 4f / 3f
            val dst = RectF(W - aw - 96f, H - ah - 190f, W - 96f, H - 190f)
            val artPaint = Paint().apply { alpha = 82 }
            canvas.drawBitmap(artBmp, null, dst, artPaint)
            val fade = Paint().apply {
                shader = android.graphics.LinearGradient(
                    0f, dst.top, 0f, dst.top + 220f,
                    0xF2F7F3EA.toInt(), 0x00F7F3EA, android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(dst.left, dst.top, dst.right, dst.top + 220f, fade)
            val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF8A8272.toInt()
                textSize = 28f
                typeface = tf
            }
            canvas.drawText(
                "赏画 · ${artwork.title}",
                dst.left + 8f, dst.bottom - 16f, caption,
            )
            artBmp.recycle()
        }

        // 界格双线
        val frame = Paint().apply {
            color = 0xFFC6BEAC.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(64f, 64f, W - 64f, H - 64f, frame)
        canvas.drawRect(80f, 80f, W - 80f, H - 80f, frame)

        // 名句（无名句取首句），按 14 字切行居中
        val line = poem.famousLine.ifBlank { poem.paragraphs.firstOrNull().orEmpty() }
            .replace('\n', ' ')
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF23211C.toInt()
            textSize = 72f
            typeface = tf
            textAlign = Paint.Align.CENTER
        }
        val chunks = line.chunked(12)
        var y = H / 2f - chunks.size * 60f
        for (c in chunks) {
            canvas.drawText(c, W / 2f, y, textPaint)
            y += 128f
        }

        // 出处
        val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5C574C.toInt()
            textSize = 40f
            typeface = tf
            textAlign = Paint.Align.CENTER
        }
        val era = poem.era.ifBlank { poem.dynasty }
        val who = poem.author.ifBlank { "佚名" }
        canvas.drawText("—— $era · $who《${poem.title}》", W / 2f, y + 60f, meta)

        // 朱印：圆角方 + 白字「汲古」竖排
        val seal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E2F26.toInt() }
        val sx = W - 260f
        val sy = H - 420f
        canvas.drawRoundRect(RectF(sx, sy, sx + 150f, sy + 300f), 18f, 18f, seal)
        val sealText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFF6F2.toInt()
            textSize = 96f
            typeface = tf
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("汲", sx + 75f, sy + 110f, sealText)
        canvas.drawText("古", sx + 75f, sy + 240f, sealText)

        // 底部落款
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA69E8C.toInt()
            textSize = 34f
            typeface = tf
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("汲古 · 一天一首诗", W / 2f, H - 110f, brand)

        val dir = File(context.cacheDir, "share")
        dir.mkdirs()
        val out = File(dir, "jigu-card-${poem.id}.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "com.jigu.app.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享诗卡"))
    }

    private fun ResourcesCompat_typeface(context: Context): Typeface? =
        androidx.core.content.res.ResourcesCompat.getFont(context, R.font.wenkai_regular)

    fun icon(context: Context): Int = ContextCompat.getColor(context, R.color.ic_launcher_background)
}
