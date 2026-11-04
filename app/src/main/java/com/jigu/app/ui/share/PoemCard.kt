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
import com.jigu.app.ui.art.PaperInk
import com.jigu.app.ui.signatureWithTitle
import java.io.File

/** 分享卡片：Canvas 渲染宣纸界格 + 名句 + 朱印，走 FileProvider 分享。
 *  版式（D-019「课本插页」案，用户选型）：名句标点断行居上、出处紧随，
 *  画心淡墨羽化居中放大（无框融纸，如课本插页），题注汉译居中，
 *  朱印固定右下、底部落款。 */
object PoemCard {

    private const val W = 1080
    private const val H = 1620

    fun render(context: Context, poem: PoemsDb.Poem, artwork: ArtworkRepo.Artwork? = null): File {
        val tf = Typeface.create(ResourcesCompat_typeface(context), Typeface.NORMAL)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 宣纸底
        val paper = Paint().apply { color = PaperInk.PAPER }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paper)

        // 界格双线
        val frame = Paint().apply {
            color = 0xFFC6BEAC.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(64f, 64f, W - 64f, H - 64f, frame)
        canvas.drawRect(80f, 80f, W - 80f, H - 80f, frame)

        // 名句（无名句取首段）：按标点断行，≤3 行；超限回退硬切两行 + 省略
        val line = poem.famousLine.ifBlank {
            poem.paragraphs.firstOrNull().orEmpty()
        }
        val lines = wrapFamousLine(line)
        val maxLen = lines.maxOf { it.length }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF23211C.toInt()
            textSize = if (maxLen > 12) 56f else 72f
            typeface = tf
            textAlign = Paint.Align.CENTER
        }
        val lh = textPaint.textSize * 1.75f
        val textTop = 240f
        var y = textTop
        for (c in lines) {
            canvas.drawText(c, W / 2f, y, textPaint)
            y += lh
        }

        // 出处（D-012「朝代 · 作者《题》」），固定在文字区底，不随行数压到画上
        val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5C574C.toInt()
            textSize = 40f
            typeface = tf
            textAlign = Paint.Align.CENTER
        }
        val sigY = textTop + (lines.size - 1).coerceAtLeast(0) * lh + 110f
        canvas.drawText("——  ${signatureWithTitle(poem)}", W / 2f, sigY, meta)

        // 画心：居中放大，淡墨映射 + 四边羽化（融纸，无渐隐罩硬边）
        val artBmp = artwork?.let { ArtworkRepo.decode(context, it) }
        if (artBmp != null) {
            val dst = RectF((W - 480f) / 2f, 690f, (W + 480f) / 2f, 690f + 640f)
            val blended = PaperInk.featheredInk(artBmp, PaperInk.INK, 52, key = "card-${artwork!!.file}")
            canvas.drawBitmap(blended, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF8A8272.toInt()
                textSize = 30f
                typeface = tf
                textAlign = Paint.Align.CENTER
            }
            val capText = Canvas_trunc(
                "赏画 · 《${artwork!!.displayTitle()}》 ${artwork.displayArtist()}",
                caption,
                W - 260f,
            )
            canvas.drawText(capText, W / 2f, dst.bottom + 62f, caption)
        }

        // 朱印：圆角方 + 白字「汲古」竖排，固定右下留白
        val seal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E2F26.toInt() }
        val sx = W - 250f
        val sy = H - 460f
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
        canvas.drawText("汲古 · 一天一首诗", W / 2f, H - 92f, brand)

        val dir = File(context.cacheDir, "share")
        dir.mkdirs()
        val out = File(dir, "jigu-card-${poem.id}.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }

    /** 名句按标点断行（D-019）：子句贪心打包 ≤maxPer 字、≤maxLines 行；
     *  单句超长者硬切；整体超 maxLines 回退两行 + 省略。 */
    internal fun wrapFamousLine(
        raw: String,
        maxPer: Int = 12,
        maxLines: Int = 3,
    ): List<String> {
        val text = raw.replace('\n', ' ').trim()
        if (text.isEmpty()) return listOf("")
        if (text.length <= maxPer) return listOf(text)
        val clauses = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch in "，。！？；、：…—,.;:!?") {
                clauses.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) clauses.add(sb.toString())
        // 单句超长者硬切到 maxPer
        val pieces = mutableListOf<String>()
        for (c in clauses) {
            if (c.length <= maxPer) pieces.add(c)
            else c.chunked(maxPer).forEach { pieces.add(it) }
        }
        val packed = mutableListOf<String>()
        var cur = StringBuilder()
        for (p in pieces) {
            if (cur.isEmpty() || cur.length + p.length <= maxPer) cur.append(p)
            else {
                packed.add(cur.toString())
                cur = StringBuilder().append(p)
            }
        }
        if (cur.isNotEmpty()) packed.add(cur.toString())
        if (packed.size <= maxLines) return packed
        val joined = packed.joinToString("")
        return listOf(joined.take(maxPer), joined.drop(maxPer).take(maxPer - 1) + "…")
    }

    private fun Canvas_trunc(text: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        var s = text
        while (s.isNotEmpty() && paint.measureText("$s…") > maxW) {
            s = s.dropLast(1)
        }
        return "$s…"
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
