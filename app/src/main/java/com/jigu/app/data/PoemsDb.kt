package com.jigu.app.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * 预构建库（assets/data/jigu.zip 内 jigu.db，由 tools/datapack/build.py 产出）的访问层。
 *
 * 设计要点：
 * - 首启/升级在后台线程解压拷库（保留收藏），所有查询经 awaitData() 等待就绪
 * - 不继承 SQLiteOpenHelper：库是预构建整体替换的，升级=重拷，没有原地 schema 迁移；
 *   版本号从数据包自带的 data/version.json 读取，杜绝 Kotlin 侧硬编码版本与管线脱节
 */
class PoemsDb private constructor(context: Context) {

    companion object {
        const val DB_NAME = "jigu.db"

        /** 精选池（日推普通日子）：先秦经典整类 + 三百首 tag + 千家诗 + 名句/名段命中。
         *  清词/幽梦影/五代词/古文由此经名句种子精选进池（D-019 池配平：清 34%→2.4%），
         *  不再整类全收；guwen 不在分类白名单——古文只有收录了名段（famous_lines）才进日推，
         *  避免"整篇长文上今日页"（D-011）。 */
        private const val CURATED_WHERE =
            "category IN ('shijing','chuci','lunyu','sishu','han_wei','qianjiashi','gushi19') " +
                "OR tags LIKE '%\"唐诗三百首\"%' " +
                "OR tags LIKE '%\"宋词三百首\"%' " +
                "OR EXISTS (SELECT 1 FROM famous_lines f WHERE f.poem_id = p.id)"

        private const val CORE_COLUMNS =
            "p.id, p.category, p.title, IFNULL(a.name,'佚名') AS author, IFNULL(a.brief,'') AS brief, " +
                "p.rhythmic, p.chapter, p.section, p.paragraphs, p.tags, p.notes, p.body, " +
                "IFNULL(a.dynasty,'') AS dynasty, p.type AS type, p.era AS era, p.genre AS genre"

        private const val IMAGERY_SUB =
            "(SELECT GROUP_CONCAT(i.name, '、') FROM poem_imagery pi " +
                "JOIN imagery i ON i.id = pi.imagery_id WHERE pi.poem_id = p.id)"

        private const val FAMOUS_SUB =
            "(SELECT f.line FROM famous_lines f WHERE f.poem_id = p.id)"

        private const val AUTHOR_COLUMNS =
            "a.id, a.name, a.dynasty, a.brief, a.birth_year, a.death_year, count(p.id) AS cnt"

        @Volatile
        private var instance: PoemsDb? = null

        fun get(context: Context): PoemsDb =
            instance ?: synchronized(this) { instance ?: PoemsDb(context).also { instance = it } }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("jigu_data", Context.MODE_PRIVATE)
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var database: SQLiteDatabase? = null

    init {
        Thread {
            try {
                val dbFile = appContext.getDatabasePath(DB_NAME)
                val required = readAssetDataVersion(appContext)
                val installed = prefs.getInt("data_version", -1)
                if (!dbFile.exists() || installed != required) {
                    val kept = if (dbFile.exists()) readFavoritesRaw(dbFile) else emptyList()
                    copyFromAssets(dbFile)
                    if (kept.isNotEmpty()) restoreFavorites(dbFile, kept)
                    prefs.edit().putInt("data_version", required).apply()
                }
                database = SQLiteDatabase.openDatabase(
                    dbFile.path, null, SQLiteDatabase.OPEN_READWRITE,
                )
            } catch (t: Throwable) {
                android.util.Log.e("PoemsDb", "数据拷库失败", t)
            } finally {
                readyLatch.countDown()
            }
        }.start()
    }

    private fun readAssetDataVersion(context: Context): Int = runCatching {
        JSONObject(context.assets.open("data/version.json").bufferedReader().readText())
            .getInt("data_version")
    }.getOrDefault(-1)

    private fun awaitData() {
        readyLatch.await()
    }

    private fun rdb(): SQLiteDatabase {
        awaitData()
        return database ?: throw IllegalStateException("数据库未就绪（拷库失败？见 logcat PoemsDb）")
    }

    private fun wdb(): SQLiteDatabase {
        awaitData()
        return database ?: throw IllegalStateException("数据库未就绪（拷库失败？见 logcat PoemsDb）")
    }

    private fun copyFromAssets(dbFile: File) {
        dbFile.parentFile?.mkdirs()
        appContext.assets.open("data/jigu.zip").use { input ->
            java.util.zip.ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.name == "jigu.db") {
                        dbFile.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                }
            }
        }
    }

    private fun readFavoritesRaw(dbFile: File): List<Pair<Int, Long>> = runCatching {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val out = mutableListOf<Pair<Int, Long>>()
            db.rawQuery("SELECT poem_id, created_at FROM favorites", null).use { c ->
                while (c.moveToNext()) out += c.getInt(0) to c.getLong(1)
            }
            out
        }
    }.getOrDefault(emptyList())

    private fun restoreFavorites(dbFile: File, rows: List<Pair<Int, Long>>) {
        runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DELETE FROM favorites")
                rows.forEach { (id, ts) ->
                    db.execSQL("INSERT INTO favorites(poem_id, created_at) VALUES (?,?)", arrayOf(id, ts))
                }
            }
        }
    }

    // ---------- 模型 ----------

    data class Poem(
        val id: Int,
        val category: String,
        val title: String,
        val author: String,
        val brief: String,
        val rhythmic: String,
        val chapter: String,
        val section: String,
        val paragraphs: List<String>,
        val tags: List<String>,
        val notes: List<String>,
        val body: String,
        val dynasty: String,
        val imagery: List<String>,
        val famousLine: String,
        val type: String,
        val era: String,
        val genre: String,
    )

    data class Author(
        val id: Int,
        val name: String,
        val dynasty: String,
        val brief: String,
        val birthYear: Int?,
        val deathYear: Int?,
        val workCount: Int,
    )

    /** 名句条目（书库"名句"卡，批1）：名句行 + 出处（今日页同源数据，D-011） */
    data class FamousLine(
        val poemId: Int,
        val line: String,
        val title: String,
        val dynasty: String,
        val author: String,
    ) {
        /** 「朝代 · 作者《题》」（D-012 出口之外的第二处拼接近亲——名句模块数据非 Poem 对象） */
        val signature: String =
            listOfNotNull(dynasty.ifBlank { null }, author.ifBlank { "佚名" }).joinToString(" · ")
    }

    // ---------- 行映射 ----------

    private fun android.database.Cursor.toPoem(): Poem {
        val i = this
        // 列序与 POEM_SELECT 严格对应：CORE_COLUMNS 0..15，imagery/famous_line 为 16/17。
        // 此处曾整体错位一格（imagery 误读 type 列、era 误读 imagery 串等），务必对齐。
        return Poem(
            id = i.getInt(0),
            category = i.getString(1),
            title = i.getString(2),
            author = i.getString(3),
            brief = i.getString(4) ?: "",
            rhythmic = i.getString(5) ?: "",
            chapter = i.getString(6) ?: "",
            section = i.getString(7) ?: "",
            paragraphs = i.getString(8).toJsonList(),
            tags = i.getString(9).toJsonList(),
            notes = i.getString(10).toJsonList(),
            body = i.getString(11),
            dynasty = i.getString(12) ?: "",
            type = i.getString(13) ?: "",
            era = i.getString(14) ?: "",
            genre = i.getString(15) ?: "",
            imagery = (if (i.isNull(16)) "" else i.getString(16))
                .split('、').filter { it.isNotBlank() },
            famousLine = if (i.isNull(17)) "" else i.getString(17) ?: "",
        )
    }

    private fun android.database.Cursor.toAuthor(): Author {
        val c = this
        return Author(
            id = c.getInt(0),
            name = c.getString(1),
            dynasty = c.getString(2) ?: "",
            brief = c.getString(3) ?: "",
            birthYear = if (c.isNull(4)) null else c.getInt(4),
            deathYear = if (c.isNull(5)) null else c.getInt(5),
            workCount = c.getInt(6),
        )
    }

    private fun String.toJsonList(): List<String> = runCatching {
        val arr = JSONArray(this)
        List(arr.length()) { k -> arr.optString(k) }
    }.getOrDefault(emptyList())

    // ---------- 查询骨架 ----------

    private fun queryPoem(sql: String, args: Array<String>): Poem? =
        rdb().rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.toPoem() else null }

    private fun queryPoems(sql: String, args: Array<String>): List<Poem> =
        rdb().rawQuery(sql, args).use { c ->
            val out = mutableListOf<Poem>()
            while (c.moveToNext()) out += c.toPoem()
            out
        }

    private val POEM_SELECT =
        "SELECT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line FROM poems p " +
            "LEFT JOIN authors a ON a.id = p.author_id "

    // ---------- 元数据与计数 ----------

    fun count(): Int {
        awaitData()
        return rdb().rawQuery("SELECT count(*) FROM poems", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
    }

    fun meta(key: String): String? {
        awaitData()
        return rdb().rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun categoryCounts(): Map<String, Int> {
        awaitData()
        cachedCounts?.let { return it }
        val out = rdb().rawQuery(
            "SELECT category, count(*) FROM poems GROUP BY category", null,
        ).use { c ->
            val m = mutableMapOf<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
        cachedCounts = out
        return out
    }

    @Volatile
    private var cachedCounts: Map<String, Int>? = null

    fun eraCounts(): Map<String, Int> {
        awaitData()
        cachedEras?.let { return it }
        val out = rdb().rawQuery(
            "SELECT a.dynasty, count(*) FROM poems p " +
                "JOIN authors a ON a.id = p.author_id GROUP BY a.dynasty", null,
        ).use { c ->
            val m = mutableMapOf<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
        cachedEras = out
        return out
    }

    @Volatile
    private var cachedEras: Map<String, Int>? = null

    fun typeCounts(): Map<String, Int> {
        awaitData()
        cachedTypes?.let { return it }
        val out = rdb().rawQuery(
            "SELECT type, count(*) FROM poems WHERE type != '' GROUP BY type ORDER BY 2 DESC", null,
        ).use { c ->
            val m = mutableMapOf<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
        cachedTypes = out
        return out
    }

    @Volatile
    private var cachedTypes: Map<String, Int>? = null

    fun themeCounts(): Map<String, Int> {
        awaitData()
        cachedThemes?.let { return it }
        val out = rdb().rawQuery(
            "SELECT t.name, count(*) FROM poem_theme pt " +
                "JOIN themes t ON t.id = pt.theme_id GROUP BY t.name ORDER BY 2 DESC", null,
        ).use { c ->
            val m = mutableMapOf<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
        cachedThemes = out
        return out
    }

    /** 题材两级：四门（写景/抒怀/咏物/世情）→ 细类计数（schema v6 的 themes.grp） */
    fun themeGroups(): Map<String, Map<String, Int>> {
        awaitData()
        cachedThemeGroups?.let { return it }
        val out = rdb().rawQuery(
            "SELECT t.grp, t.name, count(*) FROM poem_theme pt " +
                "JOIN themes t ON t.id = pt.theme_id GROUP BY t.grp, t.name ORDER BY 3 DESC", null,
        ).use { c ->
            val m = mutableMapOf<String, MutableMap<String, Int>>()
            while (c.moveToNext()) {
                val grp = c.getString(0)
                val name = c.getString(1)
                val cnt = c.getInt(2)
                if (grp.isBlank()) continue
                m.getOrPut(grp) { mutableMapOf() }[name] = cnt
            }
            m
        }
        cachedThemeGroups = out
        return out
    }

    @Volatile
    private var cachedThemeGroups: Map<String, Map<String, Int>>? = null

    /** 文体→文：书卷（古文观止 12 卷 / 论语 20 篇 / 四书篇目），供卷次两级页 */
    fun chapterCounts(): Map<String, Map<String, Int>> {
        awaitData()
        cachedChapters?.let { return it }
        val out = rdb().rawQuery(
            "SELECT category, chapter, count(*) FROM poems " +
                "WHERE category IN ('guwen','lunyu','sishu') AND chapter != '' " +
                "GROUP BY category, chapter", null,
        ).use { c ->
            val m = linkedMapOf<String, MutableMap<String, Int>>()
            val order = mapOf("guwen" to 0, "lunyu" to 1, "sishu" to 2)
            while (c.moveToNext()) {
                val cat = c.getString(0)
                val ch = c.getString(1)
                m.getOrPut(cat) { linkedMapOf() }[ch] = c.getInt(2)
            }
            m.entries.sortedBy { order[it.key] ?: 9 }
                .associate { (cat, vol) ->
                    cat to vol.entries
                        .sortedWith(compareBy({ it.key.toVolumeKey().first }, { it.key.toVolumeKey().second }))
                        .associate { (ch, n) -> ch to n }
                }
        }
        cachedChapters = out
        return out
    }

    @Volatile
    private var cachedChapters: Map<String, Map<String, Int>>? = null

    /** "卷十二·明文" -> (0,12)；"为政篇" 等 -> (1,0)：先书类序，再卷序自然递增 */
    private fun String.toVolumeKey(): Pair<Int, Int> {
        val map = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
            "十一" to 11, "十二" to 12,
        )
        val match = Regex("卷([一二三四五六七八九十]+)").find(this)
        return if (match != null) 0 to (map[match.groupValues[1]] ?: 99) else 1 to 0
    }

    @Volatile
    private var cachedThemes: Map<String, Int>? = null

    fun tagCount(tag: String): Int {
        awaitData()
        return rdb().rawQuery(
            "SELECT count(*) FROM poems WHERE tags LIKE ?", arrayOf("%\"$tag\"%"),
        ).use { c -> c.moveToFirst(); c.getInt(0) }
    }

    /** 名句数（书架"名句"卡计数）：今日页同源，= famous_lines 行数 */
    fun famousCount(): Int {
        awaitData()
        return rdb().rawQuery("SELECT count(*) FROM famous_lines", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
    }

    /** 名句分页流（种子序即名气序，1 章 = 300 条天然按诗分组；批1 书库"名句"卡） */
    fun famousLines(limit: Int, offset: Int): List<FamousLine> =
        rdb().rawQuery(
            "SELECT f.poem_id, f.line, p.title, IFNULL(a.dynasty,''), IFNULL(a.name,'') " +
                "FROM famous_lines f JOIN poems p ON p.id = f.poem_id " +
                "LEFT JOIN authors a ON a.id = p.author_id ORDER BY f.id LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { c ->
            val out = mutableListOf<FamousLine>()
            while (c.moveToNext()) out += FamousLine(
                poemId = c.getInt(0),
                line = c.getString(1) ?: "",
                title = c.getString(2) ?: "",
                dynasty = c.getString(3) ?: "",
                author = c.getString(4) ?: "",
            )
            out
        }

    // ---------- 精选池（日推普通日）：固定序时代打散（D-018，P1-8） ----------

    @Volatile
    private var cachedCuratedOrder: List<Int>? = null

    /**
     * 精选池日推顺序：固定种子整体洗牌 + 同时代连片修复。
     * 旧实现 LIMIT/OFFSET 无 ORDER BY，轮转指针按 rowid 爬数据源连片
     * （「清」477 首一片，连续十天/换一首十次全是清）。洗牌序确定可复现，
     * 进程内与跨重开一致；PoemOfDay 与「换一首」的 (day+顺位) 指针语义不变。
     */
    private fun curatedOrder(): List<Int> {
        awaitData()
        cachedCuratedOrder?.let { return it }
        val seq = mutableListOf<Pair<Int, String>>()
        rdb().rawQuery("SELECT id, IFNULL(era,'') FROM poems p WHERE $CURATED_WHERE", null).use { c ->
            while (c.moveToNext()) seq.add(c.getInt(0) to c.getString(1))
        }
        seq.shuffle(java.util.Random(0x4A1C55L))
        breakEraRuns(seq)
        val order = seq.map { it.first }
        cachedCuratedOrder = order
        return order
    }

    fun curatedCount(): Int = curatedOrder().size

    fun curatedAtOffset(offset: Int): Poem? {
        val order = curatedOrder()
        if (order.isEmpty()) return null
        return poemById(order[Math.floorMod(offset, order.size)])
    }

    // ---------- 作品查询 ----------

    fun poemById(id: Int): Poem? = queryPoem(
        "$POEM_SELECT WHERE p.id = ?", arrayOf(id.toString()),
    )

    fun poemAtOffset(offset: Int): Poem? = queryPoem(
        "${POEM_SELECT}LIMIT 1 OFFSET ?", arrayOf(offset.toString()),
    )

    fun calendarPoemIds(kind: String, key: String): List<Int> =
        rdb().rawQuery(
            "SELECT poem_id FROM calendar_map WHERE kind = ? AND key = ? ORDER BY id",
            arrayOf(kind, key),
        ).use { c ->
            val out = mutableListOf<Int>()
            while (c.moveToNext()) out += c.getInt(0)
            out
        }

    fun listByCategory(category: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.category = ? ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf(category, limit.toString(), offset.toString()),
    )

    fun listByTag(tag: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.tags LIKE ? ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf("%\"$tag\"%", limit.toString(), offset.toString()),
    )

    fun listByType(type: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.type = ? ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf(type, limit.toString(), offset.toString()),
    )

    fun listByTheme(theme: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT JOIN poem_theme pt ON pt.poem_id = p.id " +
            "JOIN themes t ON t.id = pt.theme_id WHERE t.name = ? " +
            "ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf(theme, limit.toString(), offset.toString()),
    )

    /** 词牌/曲牌索引（批3）：同一体裁下按调名分组计数，篇数降序。
     *  词的数据源自带 rhythmic；曲的曲牌由 title 解析回填（build.py it_yuanqu/批 2）。
     *  纳兰题形"牌名·题"亦在批 2 拆出。 */
    fun rhythmicCounts(type: String, limit: Int, offset: Int): List<Pair<String, Int>> =
        rdb().rawQuery(
            "SELECT rhythmic, COUNT(*) FROM poems WHERE type = ? AND rhythmic != '' " +
                "GROUP BY rhythmic ORDER BY 2 DESC, rhythmic LIMIT ? OFFSET ?",
            arrayOf(type, limit.toString(), offset.toString()),
        ).use { c ->
            val out = mutableListOf<Pair<String, Int>>()
            while (c.moveToNext()) out += c.getString(0) to c.getInt(1)
            out
        }

    fun listByRhythmic(type: String, rhythmic: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.type = ? AND p.rhythmic = ? ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf(type, rhythmic, limit.toString(), offset.toString()),
    )

    fun listByChapter(chapter: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.chapter = ? ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf(chapter, limit.toString(), offset.toString()),
    )

    fun listByGenres(categories: List<String>, limit: Int, offset: Int): List<Poem> {
        val marks = categories.joinToString(",") { "?" }
        return queryPoems(
            "$POEM_SELECT WHERE p.category IN ($marks) ORDER BY p.id LIMIT ? OFFSET ?",
            (categories + listOf(limit.toString(), offset.toString())).toTypedArray(),
        )
    }

    fun search(query: String, limit: Int = 30): List<Poem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // 抓三倍候选再去重，避免去重后结果偏少（D-015）
        val fetch = limit * 3
        val raw = if (q.length >= 3) {
            runCatching {
                queryPoems(
                    "SELECT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line " +
                        "FROM poems_fts JOIN poems p ON p.id = poems_fts.rowid " +
                        "LEFT JOIN authors a ON a.id = p.author_id " +
                        "WHERE poems_fts MATCH ? LIMIT ?",
                    arrayOf("\"$q\"", fetch.toString()),
                )
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }.ifEmpty { searchByLike(q, fetch) }
        return dedupeSearchResults(raw).take(limit)
    }

    /** 陈列层（书架/选本与名句收录的类），同名同文时优先保留（D-015） */
    private val CURATED_CATS =
        setOf("shijing", "chuci", "qing_ci", "youmengying", "lunyu", "sishu", "han_wei",
            "qianjiashi", "wudai_ci", "guwen", "laozi", "gushi19", "mengxue")

    /** 保留优先序：家塾课本 > 陈列层 > 题短者 > id 小者 */
    private fun compareSearchRank(a: Poem, b: Poem): Int {
        val ac = if (a.tags.contains("唐诗三百首")) 0 else 1
        val bc = if (b.tags.contains("唐诗三百首")) 0 else 1
        if (ac != bc) return ac - bc
        val al = if (a.category in CURATED_CATS) 0 else 1
        val bl = if (b.category in CURATED_CATS) 0 else 1
        if (al != bl) return al - bl
        if (a.title.length != b.title.length) return a.title.length - b.title.length
        return a.id - b.id
    }

    /** 同作者 + 同正文视为同篇（跨越全唐诗/杂曲歌辞等异名行），胜者优先保留（D-015） */
    private fun dedupeSearchResults(list: List<Poem>): List<Poem> {
        val best = HashMap<String, Poem>()
        for (p in list) {
            val key = p.author + "|" + p.body.replace(Regex("\\s"), "")
            val prev = best[key]
            if (prev == null || compareSearchRank(p, prev) < 0) best[key] = p
        }
        val out = mutableListOf<Poem>()
        val done = HashSet<String>()
        for (p in list) {
            val key = p.author + "|" + p.body.replace(Regex("\\s"), "")
            if (done.add(key)) best[key]?.let { out += it }
        }
        return out
    }

    private fun searchByLike(q: String, limit: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.body LIKE ? OR p.title LIKE ? LIMIT ?",
        arrayOf("%$q%", "%$q%", limit.toString()),
    )

    fun related(poem: Poem, limit: Int = 6): List<Poem> {
        if (poem.imagery.isNotEmpty()) {
            val marks = poem.imagery.joinToString(",") { "?" }
            val rows = queryPoems(
                "SELECT DISTINCT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line " +
                    "FROM poems p LEFT JOIN authors a ON a.id = p.author_id " +
                    "JOIN poem_imagery pi ON pi.poem_id = p.id " +
                    "JOIN imagery i ON i.id = pi.imagery_id " +
                    "WHERE i.name IN ($marks) AND p.id != ? LIMIT ?",
                (poem.imagery + listOf(poem.id.toString(), limit.toString())).toTypedArray(),
            )
            if (rows.isNotEmpty()) return rows
        }
        return queryPoems(
            "$POEM_SELECT WHERE p.id != ? AND (a.name = ? OR p.category = ?) LIMIT ?",
            arrayOf(poem.id.toString(), poem.author, poem.category, limit.toString()),
        ).ifEmpty {
            queryPoems(
                "$POEM_SELECT WHERE p.id != ? LIMIT ?",
                arrayOf(poem.id.toString(), limit.toString()),
            )
        }
    }

    // ---------- 作者 ----------

    fun authorsByEra(era: String, limit: Int, offset: Int): List<Author> =
        rdb().rawQuery(
            "SELECT $AUTHOR_COLUMNS FROM authors a LEFT JOIN poems p ON p.author_id = a.id " +
                "WHERE a.dynasty = ? GROUP BY a.id " +
                "ORDER BY (a.birth_year IS NULL) ASC, a.birth_year ASC, cnt DESC, a.name ASC " +
                "LIMIT ? OFFSET ?",
            arrayOf(era, limit.toString(), offset.toString()),
        ).use { c ->
            val out = mutableListOf<Author>()
            while (c.moveToNext()) out += c.toAuthor()
            out
        }

    fun authorIndex(limit: Int, offset: Int): List<Author> =
        rdb().rawQuery(
            "SELECT $AUTHOR_COLUMNS FROM authors a LEFT JOIN poems p ON p.author_id = a.id " +
                "GROUP BY a.id ORDER BY cnt DESC, a.name ASC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { c ->
            val out = mutableListOf<Author>()
            while (c.moveToNext()) out += c.toAuthor()
            out
        }

    fun authorById(id: Int): Author? =
        rdb().rawQuery(
            "SELECT $AUTHOR_COLUMNS FROM authors a LEFT JOIN poems p ON p.author_id = a.id " +
                "WHERE a.id = ? GROUP BY a.id",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.toAuthor() else null }

    fun poemsByAuthor(authorId: Int, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.author_id = ? ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf(authorId.toString(), limit.toString(), offset.toString()),
    )

    // ---------- 收藏 ----------

    fun isFavorite(poemId: Int): Boolean {
        awaitData()
        return rdb().rawQuery(
            "SELECT 1 FROM favorites WHERE poem_id = ?", arrayOf(poemId.toString()),
        ).use { it.moveToFirst() }
    }

    /** @return 操作后的收藏状态 */
    fun toggleFavorite(poemId: Int, now: Long = System.currentTimeMillis()): Boolean {
        val was = isFavorite(poemId)
        val values = ContentValues().apply {
            put("poem_id", poemId)
            put("created_at", now)
        }
        if (was) {
            wdb().delete("favorites", "poem_id = ?", arrayOf(poemId.toString()))
        } else {
            wdb().insert("favorites", null, values)
        }
        return !was
    }

    fun favoriteIds(): List<Int> {
        awaitData()
        return rdb().rawQuery(
            "SELECT poem_id FROM favorites ORDER BY created_at DESC", null,
        ).use { c ->
            val out = mutableListOf<Int>()
            while (c.moveToNext()) out += c.getInt(0)
            out
        }
    }

    fun poemsByIds(ids: List<Int>): List<Poem> = ids.mapNotNull { poemById(it) }
}

/** 同时代连片 >2 修复：连片第 3 位与后方最近异时代位交换（纯函数便于单测，D-018）。 */
internal fun breakEraRuns(seq: MutableList<Pair<Int, String>>) {
    var i = 0
    while (i < seq.size - 2) {
        if (seq[i].second == seq[i + 1].second && seq[i].second == seq[i + 2].second) {
            val j = (i + 3 until seq.size).firstOrNull { seq[it].second != seq[i].second }
                ?: return  // 剩余全是同时代（池内 era 多样，实际到不了）
            val tmp = seq[i + 2]
            seq[i + 2] = seq[j]
            seq[j] = tmp
        }
        i++
    }
}
