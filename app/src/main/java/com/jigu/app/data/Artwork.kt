package com.jigu.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 节气配画（assets/art/，由 tools/artwork/fetch_artworks.py 从克利夫兰艺术博物馆 CC0 抓取） */
object ArtworkRepo {

    data class Artwork(
        val term: String,
        val file: String,
        val title: String,
        val artist: String,
        val date: String,
        val credit: String,
        val titleZh: String? = null, // 草译（管线 --translate 合入，展示优先用）
        val artistZh: String? = null, // 「清 · 黎简」形（含时代），缺失时回退英文
    ) {
        fun displayTitle(): String = titleZh?.takeIf { it.isNotBlank() } ?: title
        fun displayArtist(): String = artistZh?.takeIf { it.isNotBlank() } ?: artist
    }

    @Volatile
    private var loaded = false
    private var byTerm: Map<String, List<Artwork>> = emptyMap()
    private var byFile: Map<String, Artwork> = emptyMap()

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val arr = org.json.JSONArray(
                    context.assets.open("art/manifest.json").bufferedReader().readText(),
                )
                val out = mutableMapOf<String, MutableList<Artwork>>()
                val files = mutableMapOf<String, Artwork>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val a = Artwork(
                        term = o.getString("term"),
                        file = o.getString("file"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        date = o.optString("date"),
                        credit = o.optString("credit"),
                        titleZh = o.optString("title_zh").takeIf { it.isNotEmpty() },
                        artistZh = o.optString("artist_zh").takeIf { it.isNotEmpty() },
                    )
                    out.getOrPut(a.term) { mutableListOf() }.add(a)
                    files[a.file] = a
                }
                byTerm = out
                byFile = files
            }
            loaded = true
        }
    }

    /** 节气画池按 seed 确定性轮换：同一首诗永远同一幅，不同诗、不同日不同幅 */
    fun forTerm(context: Context, term: String?, seed: Int): Artwork? {
        load(context)
        val pool = term?.let { byTerm[it] } ?: return null
        if (pool.isEmpty()) return null
        val i = ((seed % pool.size) + pool.size) % pool.size
        return pool[i]
    }

    fun decode(context: Context, artwork: Artwork): Bitmap? = runCatching {
        BitmapFactory.decodeStream(context.assets.open("art/${artwork.file}"))
    }.getOrNull()

    fun decodeScaled(context: Context, artwork: Artwork, sample: Int): Bitmap? = runCatching {
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeStream(context.assets.open("art/${artwork.file}"), null, opt)
    }.getOrNull()

    /** 蒸馏结果缓存：键 = 画文件 + 播种（同一首诗永远同一变体，不同诗不同变体） */
    private val distillCache = object : LruCache<String, Bitmap>(10) {}

    /** 画之蒸馏（D-019，travel-photo-abstraction 的确定性运行时适配）。
     *  解构：亮度三境（远/中/近）+ 饱和彩；蒸馏：栅格块面 + 元胞平滑 + 连通域挑选；
     *  重构：顶缘轮廓 RDP 棱面（疏体画用块面），映射进 38%×28% 母题包络，朱点 ≤2。
     *  seed 驱动包络位置/棱面简化度/朱点大小的确定性抖动——同一幅画在不同诗里不同构图。 */
    fun distill(context: Context, artwork: Artwork, seed: Int): Bitmap? {
        val key = "${artwork.file}#$seed"
        distillCache.get(key)?.let { return it }
        val src = decodeScaled(context, artwork, 4) ?: return null
        val bmp = Distiller.render(src, seed)
        if (bmp != null) distillCache.put(key, bmp)
        return bmp
    }
}

/** 「画之蒸馏」确定性渲染器（原型 C:\Work\scratch\jigu_art_mock\distill.py 的 Kotlin 移植） */
private object Distiller {

    private const val GW = 44
    private const val GH = 58
    private val STRATA = arrayOf(
        floatArrayOf(0.42f, 135f, 112f, 106f, 96f), // 远：占比阈值, alpha, 墨端
        floatArrayOf(0.22f, 195f, 72f, 66f, 56f), // 中
        floatArrayOf(0.09f, 240f, 35f, 33f, 28f), // 近
    )
    private const val SEAL = 0xFF9E2F26.toInt()

    fun render(src: Bitmap, seed: Int): Bitmap? {
        val w = 160
        val h = 213
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        fun lum(c: Int) = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        fun sat(c: Int) = max(Color.red(c), max(Color.green(c), Color.blue(c))) -
            min(Color.red(c), min(Color.green(c), Color.blue(c)))

        // 解构：纸/绢底 90 分位；非纸域；亮度分境阈值
        val allLum = px.map { lum(it) }.toFloatArray().also { it.sort() }
        val paperL = allLum[(allLum.size * 0.9f).toInt().coerceAtMost(allLum.size - 1)]
        val nonpaper = ArrayList<Int>(w * h)
        for (i in px.indices) if (paperL - lum(px[i]) > 30f) nonpaper.add(i)
        val total = w * h
        if (nonpaper.size < total * 0.008) return null
        val npl = nonpaper.map { lum(px[it]) }.toFloatArray().also { it.sort() }
        val accents = nonpaper.filter { sat(px[it]) > 46 }

        val frac = nonpaper.size.toFloat() / total
        val covThr = if (frac > 0.25f) 0.45f else if (frac > 0.10f) 0.30f else 0.18f
        val smoothRounds = if (frac > 0.20f) 2 else 1

        // 蒸馏：逐境栅格化 + 平滑 + 连通域挑选 + 境色采样
        data class Stratum(val cells: Set<Long>, val rgba: Int)
        val strata = ArrayList<Stratum>(3)
        val kept = HashSet<Long>()
        var prevThr = Float.MAX_VALUE
        for (st in STRATA) {
            val pct = st[0]
            val thr = npl[(npl.size * pct).toInt().coerceAtMost(npl.size - 1)]
            val cells = HashSet<Long>()
            var rs = 0f; var gs = 0f; var bs = 0f; var n = 0
            for (gy in 0 until GH) {
                val y0 = gy * h / GH; val y1 = (gy + 1) * h / GH
                for (gx in 0 until GW) {
                    val x0 = gx * w / GW; val x1 = (gx + 1) * w / GW
                    var cnt = 0; var tot = 0
                    for (yy in y0 until y1) {
                        for (xx in x0 until x1) {
                            val i = yy * w + xx
                            tot++
                            val l = lum(px[i])
                            if (l <= thr && paperL - l > 30f) {
                                cnt++
                                if (l > prevThr) { // 本境独占的亮度带，采色
                                    rs += Color.red(px[i]); gs += Color.green(px[i]); bs += Color.blue(px[i]); n++
                                }
                            }
                        }
                    }
                    if (tot > 0 && cnt.toFloat() / tot >= covThr) cells.add(gy.toLong() * GW + gx)
                }
            }
            val meanR = if (n > 0) rs / n else st[2]; val meanG = if (n > 0) gs / n else st[3]; val meanB = if (n > 0) bs / n else st[4]
            val toneR = (0.15f * meanR + 0.85f * st[2]).toInt()
            val toneG = (0.15f * meanG + 0.85f * st[3]).toInt()
            val toneB = (0.15f * meanB + 0.85f * st[4]).toInt()
            val alpha = st[1].toInt()
            cells.removeAll(kept)
            if (cells.size < 4) { prevThr = thr; continue }
            var grid = smooth(cells, smoothRounds)
            val comps = components(grid)
            val keep = HashSet<Long>()
            var taken = 0
            for ((area, comp) in comps) { // comps 已按面积降序，取前两个达门槛的域
                if (taken >= 2) break
                if (area >= 6) { keep.addAll(comp); taken++ }
            }
            if (keep.isNotEmpty()) {
                strata.add(Stratum(keep, Color.argb(alpha, toneR, toneG, toneB)))
                kept.addAll(keep)
            }
            prevThr = thr
        }
        if (strata.isEmpty()) return null

        // 重构：联合外框 → 38%×28% 包络（seed 抖动位置），中偏左下
        val rng = java.util.Random(seed.toLong() * 7919 + 13)
        val W = 390; val H = 520
        var bx0 = Int.MAX_VALUE; var bx1 = Int.MIN_VALUE; var by0 = Int.MAX_VALUE; var by1 = Int.MIN_VALUE
        for (s in strata) for (c in s.cells) {
            val gx = (c / GW).toInt(); val gy = (c % GW).toInt()
            bx0 = min(bx0, gx); bx1 = max(bx1, gx); by0 = min(by0, gy); by1 = max(by1, gy)
        }
        val bw = bx1 - bx0 + 1; val bh = by1 - by0 + 1
        val envW = W * 0.38f; val envH = H * 0.28f
        val envX = W * (0.40f + 0.08f * rng.nextFloat()) - envW / 2
        val envY = H * (0.58f + 0.08f * rng.nextFloat()) - envH / 2
        val scale = min(envW / bw, envH / bh)
        val offX = envX + (envW - bw * scale) / 2
        val offY = envY + (envH - bh * scale) / 2
        val eps = 1.1f + 0.5f * rng.nextFloat() // 棱面简化度抖动

        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val sparse = frac < 0.10f
        val base = by1 + 1
        for (s in strata) {
            paint.color = s.rgba
            if (sparse) {
                for (c in s.cells) {
                    val gx = (c / GW).toInt(); val gy = (c % GW).toInt()
                    canvas.drawRect(
                        offX + (gx - bx0) * scale, offY + (gy - by0) * scale,
                        offX + (gx - bx0 + 1) * scale - 0.6f, offY + (gy - by0 + 1) * scale - 0.6f,
                        paint,
                    )
                }
            } else {
                // 顶缘轮廓 → RDP 棱面 → 共底多边形（层峦）
                val topOf = HashMap<Int, Int>()
                for (c in s.cells) {
                    val gx = (c / GW).toInt(); val gy = (c % GW).toInt()
                    if (gy < topOf.getOrDefault(gx, GH)) topOf[gx] = gy
                }
                val xs = topOf.keys.sorted()
                if (xs.isEmpty()) continue
                val runs = ArrayList<List<Int>>()
                var run = mutableListOf(xs[0])
                for (k in 1 until xs.size) {
                    if (xs[k] == xs[k - 1] + 1) run.add(xs[k]) else { runs.add(run); run = mutableListOf(xs[k]) }
                }
                runs.add(run)
                for (r in runs) {
                    val top = r.map { Pair(it + 0.5f, (topOf[it] ?: 0) + 0.5f) }
                    val simp = rdp(top, eps)
                    if (simp.size < 2) continue
                    val path = Path()
                    path.moveTo(offX + (simp[0].first - bx0) * scale, offY + (base - by0) * scale)
                    for (p in simp) path.lineTo(offX + (p.first - bx0) * scale, offY + (p.second - by0) * scale)
                    path.lineTo(offX + (simp.last().first - bx0) * scale, offY + (base - by0) * scale)
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
        // 朱点 ≤2（accent 连通域质心，按相对位置映射）
        if (accents.isNotEmpty()) {
            val acc = accents.map { i -> (i / w).toLong() * GH / h * GW + (i % w).toLong() * GW / w }.toSet()
            val comps = components(acc)
            var drawn = 0
            for ((area, comp) in comps) {
                if (area < 2 || area > GW * GH * 0.08 || drawn >= 2) continue
                var sx = 0f; var sy = 0f
                for (c in comp) { sx += (c / GW).toInt(); sy += (c % GW).toInt() }
                val ex = offX + (sx / comp.size - bx0) * scale
                val ey = offY + (sy / comp.size - by0) * scale
                val r = (4f + min(13f, (area.toFloat() / (GW * GH)) * 260f * scale / 14f))
                    .coerceIn(4f, 13f) * (0.85f + 0.3f * rng.nextFloat())
                paint.color = SEAL
                canvas.drawCircle(ex, ey, r, paint)
                drawn++
            }
        }
        return bmp
    }

    private fun keepOf(area: Int) = area >= 6

    private fun smooth(cells: Set<Long>, rounds: Int): Set<Long> {
        var cur = cells
        repeat(rounds) {
            val out = HashSet<Long>()
            for (c in cur) {
                val gx = (c / GW).toInt(); val gy = (c % GW).toInt()
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (cur.contains((gy + dy).toLong() * GW + (gx + dx))) n++
                }
                if (n >= 4) out.add(c) // 保留：8 邻 ≥4
            }
            cur = out
        }
        return cur
    }

    private fun components(cells: Set<Long>): List<Pair<Int, List<Long>>> {
        val seen = HashSet<Long>()
        val comps = ArrayList<Pair<Int, List<Long>>>()
        for (start in cells) {
            if (seen.contains(start)) continue
            val stack = ArrayDeque<Long>()
            stack.addLast(start); seen.add(start)
            val comp = ArrayList<Long>()
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                comp.add(cur)
                val gx = (cur / GW).toInt(); val gy = (cur % GW).toInt()
                for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nxt = (gy + dy).toLong() * GW + (gx + dx)
                    if (cells.contains(nxt) && !seen.contains(nxt)) { seen.add(nxt); stack.addLast(nxt) }
                }
            }
            comps.add(Pair(comp.size, comp))
        }
        comps.sortByDescending { it.first }
        return comps
    }

    private fun rdp(pts: List<Pair<Float, Float>>, eps: Float): List<Pair<Float, Float>> {
        if (pts.size < 3) return pts
        val ax = pts.first().first; val ay = pts.first().second
        val bx = pts.last().first; val by = pts.last().second
        val dx = bx - ax; val dy = by - ay
        val norm = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
        var dmax = 0f; var idx = 0
        for (i in 1 until pts.size - 1) {
            val p = pts[i]
            val d = abs(dy * p.first - dx * p.second + bx * ay - by * ax) / norm
            if (d > dmax) { dmax = d; idx = i }
        }
        return if (dmax > eps) {
            val left = rdp(pts.subList(0, idx + 1), eps)
            val right = rdp(pts.subList(idx, pts.size), eps)
            left.dropLast(1) + right
        } else {
            listOf(pts.first(), pts.last())
        }
    }
}

/** 轻量设置：画意开关、推送等用户偏好 */
object JiguSettings {

    private const val PREFS = "jigu_prefs"
    private const val KEY_ART = "art_enabled"

    fun artEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ART, true)

    fun setArtEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ART, value).apply()
    }

    private const val KEY_PUSH = "push_enabled"

    fun pushEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PUSH, true)

    fun setPushEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PUSH, value).apply()
    }

    fun pushHour(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("push_hour", 7)

    fun pushMinute(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("push_minute", 30)

    fun setPushTime(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("push_hour", hour).putInt("push_minute", minute).apply()
    }
}
