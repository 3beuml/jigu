package com.jigu.app.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                "p.rhythmic, p.chapter, p.section, p.paragraphs, p.tags, p.notes, " +
                "IFNULL(a.dynasty,'') AS dynasty, p.type AS type, p.era AS era, p.genre AS genre, " +
                "p.popularity AS popularity"

        private const val IMAGERY_SUB =
            "(SELECT GROUP_CONCAT(i.name, '、') FROM poem_imagery pi " +
                "JOIN imagery i ON i.id = pi.imagery_id WHERE pi.poem_id = p.id)"

        private const val FAMOUS_SUB =
            "(SELECT f.line FROM famous_lines f WHERE f.poem_id = p.id)"

        private const val AUTHOR_COLUMNS =
            "a.id, a.name, a.dynasty, a.brief, a.birth_year, a.death_year, count(p.id) AS cnt"

        @Volatile
        private var instance: PoemsDb? = null

        /** 书库准备状态：首启/升级解压拷库期间给 UI 明示（D-034"空入口"误判的结构性修复） */
        private val _prepState = MutableStateFlow(PrepState.Checking)
        val prepState: StateFlow<PrepState> = _prepState.asStateFlow()

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
                    _prepState.value = PrepState.Copying
                    val keptFav = if (dbFile.exists()) readFavoritesRaw(dbFile) else emptyList()
                    val keptFollows = if (dbFile.exists()) readFollowsRaw(dbFile) else emptyList()
                    copyFromAssets(dbFile)
                    if (keptFav.isNotEmpty()) restoreFavorites(dbFile, keptFav)
                    if (keptFollows.isNotEmpty()) restoreFollows(dbFile, keptFollows)
                    prefs.edit().putInt("data_version", required).apply()
                }
                database = SQLiteDatabase.openDatabase(
                    dbFile.path, null, SQLiteDatabase.OPEN_READWRITE,
                )
                _prepState.value = PrepState.Ready
            } catch (t: Throwable) {
                _prepState.value = PrepState.Failed
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

    /** 关注诗人跨版本迁移（D-043 R2）：follows 表自带 name/dynasty 快照，
     *  新库按 姓名+朝代 重解析 author_id（版本重建后 id 会漂移，id 直迁会指错人）。 */
    private data class FollowSnapshot(val name: String, val dynasty: String, val createdAt: Long)

    private fun readFollowsRaw(dbFile: File): List<FollowSnapshot> = runCatching {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val out = mutableListOf<FollowSnapshot>()
            db.rawQuery("SELECT name, dynasty, created_at FROM follows", null).use { c ->
                while (c.moveToNext()) out += FollowSnapshot(c.getString(0), c.getString(1) ?: "", c.getLong(2))
            }
            out
        }
    }.getOrDefault(emptyList())

    private fun restoreFollows(dbFile: File, rows: List<FollowSnapshot>) {
        runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DELETE FROM follows")
                rows.forEach { f ->
                    val id = db.rawQuery(
                        "SELECT id FROM authors WHERE name = ? AND dynasty = ? LIMIT 1",
                        arrayOf(f.name, f.dynasty),
                    ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
                    if (id != null) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO follows(author_id, name, dynasty, created_at) VALUES (?,?,?,?)",
                            arrayOf(id, f.name, f.dynasty, f.createdAt),
                        )
                    }
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
        val popularity: Long = 0,
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
        // 列序与 POEM_SELECT 严格对应：CORE_COLUMNS 0..15（popularity=15，SCHEMA 8 加），
        // imagery/famous_line 为 16/17。
        // 此处曾整体错位一格（imagery 误读 type 列、era 误读 imagery 串等），务必对齐。
        // 瘦身（SCHEMA 7）：body 列已删，正文 = paragraphs 逐行 \n 拼接（与建库端同构）。
        val paragraphs = i.getString(8).toJsonList()
        return Poem(
            id = i.getInt(0),
            category = i.getString(1),
            title = i.getString(2),
            author = i.getString(3),
            brief = i.getString(4) ?: "",
            rhythmic = i.getString(5) ?: "",
            chapter = i.getString(6) ?: "",
            section = i.getString(7) ?: "",
            paragraphs = paragraphs,
            tags = i.getString(9).toJsonList(),
            notes = i.getString(10).toJsonList(),
            body = paragraphs.joinToString("\n"),
            dynasty = i.getString(11) ?: "",
            type = i.getString(12) ?: "",
            era = i.getString(13) ?: "",
            genre = i.getString(14) ?: "",
            popularity = i.getLong(15),
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

    /** 朝代计数（D-030 分栏）：poetry=true 统计诗词曲类，false 统计古文类——两栏时间轴互不干扰 */
    fun eraCounts(poetry: Boolean): Map<String, Int> {
        awaitData()
        (if (poetry) cachedEraPoetry else cachedEraProse)?.let { return it }
        val poetryCats = "('tang_poem','song_poem','ci','qing_ci','wudai_ci','qianjiashi'," +
            "'shijing','chuci','han_wei','gushi19','gushiyuan','yuan_qu'," +
            // 批12/13 补全（D-035/D-037）与批15 乐府（D-042）：此前漏入清单会被当"古文"计数
            "'yuan_shi','qing_shi','tang_ci','jinyuan_ci','jinsanbai_ci','yuefu')"
        val where = if (poetry) "p.category IN $poetryCats" else "p.category NOT IN $poetryCats"
        val out = rdb().rawQuery(
            "SELECT a.dynasty, count(*) FROM poems p " +
                "JOIN authors a ON a.id = p.author_id WHERE $where GROUP BY a.dynasty", null,
        ).use { c ->
            val m = mutableMapOf<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
        if (poetry) cachedEraPoetry = out else cachedEraProse = out
        return out
    }

    @Volatile
    private var cachedEraPoetry: Map<String, Int>? = null

    @Volatile
    private var cachedEraProse: Map<String, Int>? = null

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

    /** 文体→文：书卷章目（体裁维"文·书卷"块）。白名单 = chapter 有值的六书；
     *  老子/幽梦影/蒙学章号在题内（chapter 全空），体裁维以书级 chip 直入（见 TypeFlatContent）。 */
    fun chapterCounts(): Map<String, Map<String, Int>> {
        awaitData()
        cachedChapters?.let { return it }
        val out = rdb().rawQuery(
            "SELECT category, chapter, count(*) FROM poems " +
                "WHERE category IN ('guwen','lunyu','sishu','zhuangzi','shiji','shishuo') AND chapter != '' " +
                "GROUP BY category, chapter", null,
        ).use { c ->
            val m = linkedMapOf<String, MutableMap<String, Int>>()
            val order = mapOf(
                "guwen" to 0, "lunyu" to 1, "sishu" to 2,
                "zhuangzi" to 3, "shiji" to 4, "shishuo" to 5,
            )
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

    /** 偏好题材池（D-030「今日诗偏好」）：精选池序内筛出命中题材的作品——
     *  保留洗牌/连片修复后的顺位，(day+顺位) 指针语义与全池一致；themes 空即全池。 */
    fun curatedThemeOrder(themes: List<String>): List<Int> {
        if (themes.isEmpty()) return curatedOrder()
        awaitData()
        val hit = mutableSetOf<Int>()
        val marks = themes.joinToString(",") { "?" }
        rdb().rawQuery(
            "SELECT pt.poem_id FROM poem_theme pt JOIN themes t ON t.id = pt.theme_id " +
                "WHERE t.name IN ($marks)", themes.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) hit.add(c.getInt(0))
        }
        return curatedOrder().filter { it in hit }
    }

    fun curatedThemeCount(themes: List<String>): Int = curatedThemeOrder(themes).size

    fun curatedThemeAtOffset(offset: Int, themes: List<String>): Poem? {
        val order = curatedThemeOrder(themes)
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

    /** 全藏类按热度名家优先（批7 书架"全唐诗/全宋诗/全宋词"卡；D-010"列表名家优先"落地） */
    fun listByCategoryPopular(category: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.category = ? ORDER BY p.popularity DESC, p.id LIMIT ? OFFSET ?",
        arrayOf(category, limit.toString(), offset.toString()),
    )

    /** 名篇榜（D-031）：全库按搜索热度序（rank/ 四引擎之和回填；残卷/摘句题负热度沉底、
     *  短题热度弃用归 0——只取正热度，即"可信热度"排序）。 */
    fun listByPopularity(limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.popularity > 0 ORDER BY p.popularity DESC, p.id LIMIT ? OFFSET ?",
        arrayOf(limit.toString(), offset.toString()),
    )

    fun popularCount(): Int {
        awaitData()
        return rdb().rawQuery("SELECT COUNT(*) FROM poems WHERE popularity > 0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 古文观止出处书聚合（D-032"经史名段"）：观止 222 篇内藏 左传 34/战国策 14/史记 14/
     *  国语 11/礼记·檀弓 6/公羊 3/谷梁 2——按出处浮出水面；无出处者归"历代文集"。
     *  返回 [(出处书, 篇数)]，传世顺序优先（左传→战国策→…），其余按篇数序。 */
    fun guwenSourceCounts(): List<Pair<String, Int>> {
        awaitData()
        val raw = rdb().rawQuery(
            "SELECT IFNULL(NULLIF(section,''),'历代文集') AS src, count(*) FROM poems " +
                "WHERE category='guwen' GROUP BY src", null,
        ).use { c ->
            val m = mutableMapOf<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
        val order = listOf("左传", "战国策", "史记", "国语", "礼记·檀弓", "公羊传", "谷梁传")
        return (order.mapNotNull { it to (raw[it] ?: 0) }.filter { it.second > 0 } +
            raw.entries.filter { it.key !in order }
                .sortedByDescending { it.value }.map { it.key to it.value })
    }

    /** 某出处书的观止篇目（D-032）：source="历代文集" 映射 section=''，按库序（原书卷次） */
    fun listByGuwenSection(source: String, limit: Int, offset: Int): List<Poem> {
        val sec = if (source == "历代文集") "" else source
        return queryPoems(
            "$POEM_SELECT WHERE p.category='guwen' AND IFNULL(p.section,'') = ? ORDER BY p.id LIMIT ? OFFSET ?",
            arrayOf(sec, limit.toString(), offset.toString()),
        )
    }

    /** 文体 13 类计数（批7 题材维"古文文体"块；范围=13 类已标注的散文类） */
    fun genreCounts(): Map<String, Int> {
        awaitData()
        val out = rdb().rawQuery(
            "SELECT genre, COUNT(*) FROM poems WHERE genre != '' " +
                "AND category IN ('guwen','laozi','zhuangzi','shiji') GROUP BY genre", null,
        ).use { c ->
            val m = mutableMapOf<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
        return out
    }

    private val GENRE_CATS = "('guwen','laozi','zhuangzi','shiji')"

    /** 某诗的题材标签（批8 配画按题材匹配用） */
    fun themesFor(poemId: Int): List<String> = rdb().rawQuery(
        "SELECT t.name FROM poem_theme pt JOIN themes t ON t.id = pt.theme_id " +
            "WHERE pt.poem_id = ? ORDER BY t.id",
        arrayOf(poemId.toString()),
    ).use { c ->
        val out = mutableListOf<String>()
        while (c.moveToNext()) out += c.getString(0)
        out
    }

    fun listByGenre(genre: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.genre = ? AND p.category IN $GENRE_CATS ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf(genre, limit.toString(), offset.toString()),
    )

    fun listByTag(tag: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.tags LIKE ? ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf("%\"$tag\"%", limit.toString(), offset.toString()),
    )

    /** 体裁列表（全藏层大池）：名家优先（D-010/D-028），长尾自然沉底；书卷/书架卡列表保持编者 id 序 */
    fun listByType(type: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.type = ? ORDER BY p.popularity DESC, p.id LIMIT ? OFFSET ?",
        arrayOf(type, limit.toString(), offset.toString()),
    )

    /** 题材列表：名家优先（D-010/D-028，与全藏卡 listByCategoryPopular 同口径） */
    fun listByTheme(theme: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT JOIN poem_theme pt ON pt.poem_id = p.id " +
            "JOIN themes t ON t.id = pt.theme_id WHERE t.name = ? " +
            "ORDER BY p.popularity DESC, p.id LIMIT ? OFFSET ?",
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

    /** 调名过滤（P1-12 词牌页筛选框）：1477 调长尾靠滚动难找，页内检索直达 */
    fun rhythmicCountsSearch(type: String, q: String, limit: Int = 100): List<Pair<String, Int>> =
        rdb().rawQuery(
            "SELECT rhythmic, COUNT(*) FROM poems WHERE type = ? AND rhythmic != '' AND rhythmic LIKE ? " +
                "GROUP BY rhythmic ORDER BY 2 DESC, rhythmic LIMIT ?",
            arrayOf(type, "%$q%", limit.toString()),
        ).use { c ->
            val out = mutableListOf<Pair<String, Int>>()
            while (c.moveToNext()) out += c.getString(0) to c.getInt(1)
            out
        }

    /** 同调列表：名家优先（D-010/D-028） */
    fun listByRhythmic(type: String, rhythmic: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.type = ? AND p.rhythmic = ? ORDER BY p.popularity DESC, p.id LIMIT ? OFFSET ?",
        arrayOf(type, rhythmic, limit.toString(), offset.toString()),
    )

    /** 词/曲调名数（体裁维"词牌/曲牌"chip 计数单位=调，非篇——篇数在索引页内看） */
    fun rhythmicTypeCounts(): Map<String, Int> {
        awaitData()
        return rdb().rawQuery(
            "SELECT type, count(DISTINCT rhythmic) FROM poems " +
                "WHERE type IN ('词','曲') AND rhythmic != '' GROUP BY type", null,
        ).use { c ->
            val m = mutableMapOf<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
    }

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

    /** 搜索作者命中（D-043 R1）：搜"柳永"出作者卡，修"搜得到词搜不到人"。
     *  精确名 > 前缀 > 包含，再按作品量序；只出有作品的作者。 */
    fun searchAuthors(query: String, limit: Int = 3): List<Author> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return rdb().rawQuery(
            "$AUTHOR_COLUMNS FROM authors a LEFT JOIN poems p ON p.author_id = a.id " +
                "WHERE a.name LIKE ? GROUP BY a.id HAVING cnt > 0 " +
                "ORDER BY (a.name = ?) DESC, (a.name LIKE ? || '%') DESC, cnt DESC, a.name LIMIT ?",
            arrayOf("%$q%", q, q, limit.toString()),
        ).use { c ->
            val out = mutableListOf<Author>()
            while (c.moveToNext()) out += c.toAuthor()
            out
        }
    }

    /** 名句命中（D-043 R1）：搜名句行 → 原诗（UI 直达详情并高亮该句）。 */
    fun searchFamousLines(query: String, limit: Int = 8): List<FamousLine> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return rdb().rawQuery(
            "SELECT f.poem_id, f.line, p.title, IFNULL(a.dynasty,''), IFNULL(a.name,'佚名') " +
                "FROM famous_lines f JOIN poems p ON p.id = f.poem_id " +
                "LEFT JOIN authors a ON a.id = p.author_id " +
                "WHERE f.line LIKE ? " +
                "ORDER BY (f.line LIKE ? || '%') DESC, f.source = 'manual' DESC, length(f.line) LIMIT ?",
            arrayOf("%$q%", q, limit.toString()),
        ).use { c ->
            val out = mutableListOf<FamousLine>()
            while (c.moveToNext()) {
                out += FamousLine(
                    poemId = c.getInt(0), line = c.getString(1), title = c.getString(2) ?: "",
                    dynasty = c.getString(3) ?: "", author = c.getString(4) ?: "",
                )
            }
            out
        }
    }

    fun search(query: String, limit: Int = 30): List<Poem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // 抓四倍候选：去重（D-015）+ 名气重排后结果不偏少
        val fetch = limit * 4
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
        // 匹配度×名气混合排序（D-043 R1）：题名/词牌/作者/名句命中升权，popularity 归一封顶
        return dedupeSearchResults(raw)
            .sortedWith(
                compareByDescending<Poem> { searchScore(it, q) }
                    .thenComparator { a, b -> compareSearchRank(a, b) },
            )
            .take(limit)
    }

    /** 搜索得分（匹配度 × 名气）：题名 10000 / 词牌 6000 / 作者 8000 / 名句 3000，
     *  popularity（3~5,000,000）归一为 0..40 的名气加分。 */
    private fun searchScore(p: Poem, q: String): Long {
        var s = 0L
        if (p.title.contains(q)) s += 10000
        if (p.rhythmic.contains(q)) s += 6000
        if (p.author.contains(q)) s += 8000
        if (p.famousLine.isNotBlank() && p.famousLine.contains(q)) s += 3000
        s += (p.popularity.coerceAtMost(5_000_000L) / 125_000L)  // 0..40
        return s
    }

    /** 陈列层（书架/选本与名句收录的类），同名同文时优先保留（D-015） */
    private val CURATED_CATS =
        setOf("shijing", "chuci", "qing_ci", "youmengying", "lunyu", "sishu", "han_wei",
            "qianjiashi", "wudai_ci", "guwen", "laozi", "gushi19",
            "zhuangzi", "shishuo", "shiji", "mengxue")

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
        "$POEM_SELECT WHERE p.paragraphs LIKE ? OR p.title LIKE ? LIMIT ?",
        arrayOf("%$q%", "%$q%", limit.toString()),
    )

    /** 相关诗词（P1-10 语义重排）：候选池 = 同作者 ∪ 共享意象 ∪ 同体裁，加权合分：
     *  同作者 +3、共享意象 每项 +2、同体裁 +1（有名言再 +1）。分数降序取前 N。
     *  旧实现单取意象交集（垓下之围→夜雨寄北的噪声源），兜底体裁又无排序——重排后
     *  同作者/同体裁名家先行，散文类（无意象）自然回到同书同作者。 */
    fun related(poem: Poem, limit: Int = 6): List<Poem> {
        val scored = linkedMapOf<Int, Pair<Poem, Int>>()
        fun add(rows: List<Poem>, score: (Poem) -> Int) {
            rows.forEach { r ->
                if (r.id == poem.id) return@forEach
                val cur = scored[r.id]
                scored[r.id] = r to ((cur?.second ?: 0) + score(r))
            }
        }
        if (poem.author.isNotBlank() && poem.author != "佚名") {
            add(
                queryPoems(
                    "$POEM_SELECT WHERE p.id != ? AND p.author_id IN (SELECT author_id FROM poems WHERE id = ?) " +
                        "AND p.author_id != 0 ORDER BY p.popularity DESC, p.id LIMIT 12",
                    arrayOf(poem.id.toString(), poem.id.toString()),
                ),
            ) { 3 }
        }
        if (poem.imagery.isNotEmpty()) {
            val marks = poem.imagery.joinToString(",") { "?" }
            add(
                queryPoems(
                    "SELECT DISTINCT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line " +
                        "FROM poems p LEFT JOIN authors a ON a.id = p.author_id " +
                        "JOIN poem_imagery pi ON pi.poem_id = p.id " +
                        "JOIN imagery i ON i.id = pi.imagery_id " +
                        "WHERE i.name IN ($marks) AND p.id != ? LIMIT 40",
                    (poem.imagery + listOf(poem.id.toString())).toTypedArray(),
                ),
            ) { r -> 2 * r.imagery.count { g -> poem.imagery.contains(g) } }
        }
        add(
            queryPoems(
                "$POEM_SELECT WHERE p.id != ? AND p.category = ? ORDER BY p.popularity DESC, p.id LIMIT 20",
                arrayOf(poem.id.toString(), poem.category),
            ),
        ) { if (it.famousLine.isNotBlank()) 2 else 1 }
        if (scored.isEmpty()) {
            return queryPoems(
                "$POEM_SELECT WHERE p.id != ? LIMIT ?",
                arrayOf(poem.id.toString(), limit.toString()),
            )
        }
        return scored.values.sortedByDescending { it.second }.take(limit).map { it.first }
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

    /** 作者索引（批7"作者"维直出）；kind: all | poet(有诗) | ci(有词曲) | wen(有文)，按该体裁作品量序。
     *  D-028 修订：原"词人=纯词人、古文大家=纯文作者"把苏轼/欧阳修/韩愈这类诗词文多栖大家
     *  挡在对应分组外（"词人"搜不到苏轼、"古文大家"没有唐宋八大家）；改为"有该体裁作品即入组"。
     *  D-030 修订：wenCats 补 youmengying（幽梦影漏出"文家"组）；ciCats 补 yuan_qu（元曲名家
     *  入"词曲"组，分组名同步）。
     *  体裁 tab 排除无名氏/佚名/空名防霸榜（全宋词无名氏 1568 首会压过辛弃疾）；"全部"保留。 */
    fun authorIndex(kind: String, limit: Int, offset: Int): List<Author> {
        val poemCats = "('tang_poem','song_poem','qianjiashi','shijing','chuci','han_wei','gushi19','gushiyuan','yuan_shi','qing_shi','yuefu')"
        val wenCats = "('guwen','laozi','zhuangzi','shishuo','shiji','lunyu','sishu','mengxue','youmengying'," +
            // 批11（D-033）经史子扩容：左丘明/墨翟/孙武等入"文家"组
            "'zuozhuan','guoyu','zhanguoce','mozi','xunzi','hanfeizi','liezi','sunzi'," +
            "'huainanzi','lvshi','guanzi','yanzi','shangjunshu')"
        val ciCats = "('ci','wudai_ci','qing_ci','yuan_qu','jinyuan_ci','jinsanbai_ci','tang_ci')"
        val kindSum = when (kind) {
            "poet" -> "SUM(CASE WHEN p.category IN $poemCats THEN 1 ELSE 0 END)"
            "ci" -> "SUM(CASE WHEN p.category IN $ciCats THEN 1 ELSE 0 END)"
            "wen" -> "SUM(CASE WHEN p.category IN $wenCats THEN 1 ELSE 0 END)"
            else -> "COUNT(*)"
        }
        val having = if (kind == "all") "" else
            "HAVING $kindSum > 0 AND a.name NOT IN ('', '无名氏', '佚名')"
        return rdb().rawQuery(
            "SELECT $AUTHOR_COLUMNS FROM authors a LEFT JOIN poems p ON p.author_id = a.id " +
                "GROUP BY a.id $having ORDER BY $kindSum DESC, a.name ASC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { c ->
            val out = mutableListOf<Author>()
            while (c.moveToNext()) out += c.toAuthor()
            out
        }
    }

    fun authorById(id: Int): Author? =
        rdb().rawQuery(
            "SELECT $AUTHOR_COLUMNS FROM authors a LEFT JOIN poems p ON p.author_id = a.id " +
                "WHERE a.id = ? GROUP BY a.id",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.toAuthor() else null }

    fun poemsByAuthor(
        authorId: Int, limit: Int, offset: Int,
        type: String? = null, excludeIds: Collection<Int> = emptyList(),
    ): List<Poem> {
        val where = buildString {
            append("p.author_id = ?")
            if (type != null) append(" AND p.type = ?")
            if (excludeIds.isNotEmpty()) append(" AND p.id NOT IN (${excludeIds.joinToString(",")})")
        }
        val args = mutableListOf(authorId.toString())
        type?.let { args += it }
        args += listOf(limit.toString(), offset.toString())
        return queryPoems("$POEM_SELECT WHERE $where ORDER BY p.id LIMIT ? OFFSET ?", args.toTypedArray())
    }

    /** 诗人主页名篇置顶（D-043 R2）：有名句或有人气者，按名气序取头部。 */
    fun poemsByAuthorTop(authorId: Int, limit: Int = 6): List<Poem> = queryPoems(
        "$POEM_SELECT WHERE p.author_id = ? AND (p.popularity > 0 " +
            "OR EXISTS (SELECT 1 FROM famous_lines f WHERE f.poem_id = p.id)) " +
            "ORDER BY p.popularity DESC LIMIT ?",
        arrayOf(authorId.toString(), limit.toString()),
    )

    /** 诗人主页体裁筛选 chips：type -> 作品数。 */
    fun authorTypeCounts(authorId: Int): Map<String, Int> = rdb().rawQuery(
        "SELECT p.type, count(*) FROM poems p WHERE p.author_id = ? GROUP BY p.type ORDER BY count(*) DESC",
        arrayOf(authorId.toString()),
    ).use { c ->
        val m = linkedMapOf<String, Int>()
        while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
        m
    }

    // ---------- 关注诗人（D-043 R2） ----------

    fun followAuthor(authorId: Int, name: String, dynasty: String) {
        wdb().insertWithOnConflict(
            "follows", null,
            ContentValues().apply {
                put("author_id", authorId)
                put("name", name)
                put("dynasty", dynasty)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun unfollowAuthor(authorId: Int) {
        wdb().delete("follows", "author_id = ?", arrayOf(authorId.toString()))
    }

    fun isFollowing(authorId: Int): Boolean = rdb().rawQuery(
        "SELECT 1 FROM follows WHERE author_id = ?", arrayOf(authorId.toString()),
    ).use { it.moveToFirst() }

    fun followedAuthorIds(): Set<Int> = rdb().rawQuery(
        "SELECT author_id FROM follows", null,
    ).use { c ->
        val out = mutableSetOf<Int>()
        while (c.moveToNext()) out += c.getInt(0)
        out
    }

    /** 关注列表（独立页）：按关注时间倒序，作者带作品量。 */
    fun followedAuthors(): List<Author> = rdb().rawQuery(
        "$AUTHOR_COLUMNS FROM follows fo JOIN authors a ON a.id = fo.author_id " +
            "LEFT JOIN poems p ON p.author_id = a.id " +
            "GROUP BY a.id ORDER BY fo.created_at DESC",
        null,
    ).use { c ->
        val out = mutableListOf<Author>()
        while (c.moveToNext()) out += c.toAuthor()
        out
    }

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

    // ---------- 收藏备份（导出/导入） ----------

    data class FavoriteRow(
        val id: Int,
        val title: String,
        val author: String,
        val category: String,
        val createdAt: Long,
    )

    data class ImportResult(val added: Int, val existed: Int, val skipped: Int)

    fun favoriteRows(): List<FavoriteRow> {
        awaitData()
        return rdb().rawQuery(
            "SELECT p.id, p.title, IFNULL(a.name,'佚名'), p.category, f.created_at " +
                "FROM favorites f JOIN poems p ON p.id = f.poem_id " +
                "LEFT JOIN authors a ON a.id = p.author_id ORDER BY f.created_at DESC", null,
        ).use { c ->
            val out = mutableListOf<FavoriteRow>()
            while (c.moveToNext()) {
                out += FavoriteRow(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))
            }
            out
        }
    }

    /** 收藏导出 JSON（format=1）：title/author/category 供人读，导回仅用 id（库版本变更后防串 id 可人工核对） */
    fun exportFavoritesJson(): String {
        val rows = favoriteRows()
        val items = JSONArray()
        rows.forEach { r ->
            items.put(JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("author", r.author)
                put("category", r.category)
                put("created_at", r.createdAt)
            })
        }
        return JSONObject().apply {
            put("app", "jigu")
            put("type", "favorites")
            put("format", 1)
            put("data_version", meta("data_version")?.toIntOrNull() ?: 0)
            put("exported_at", java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US,
            ).format(java.util.Date()))
            put("count", rows.size)
            put("items", items)
        }.toString(2)
    }

    /** 导入收藏：只补不删；不存在的 id 跳过，已收藏计入 existed。容错纯数组 [1,2,3] 格式。 */
    fun importFavoritesJson(text: String): ImportResult {
        val ids = runCatching {
            val arr = JSONObject(text).optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optInt("id", 0)?.takeIf { it > 0 } }
        }.getOrElse {
            runCatching {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { i -> arr.optInt(i, 0).takeIf { it > 0 } }
            }.getOrElse { emptyList() }
        }
        require(ids.isNotEmpty()) { "文件里没有可识别的收藏条目" }
        val db = wdb()
        var added = 0
        var existed = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            ids.distinct().forEachIndexed { index, id ->
                val poemExists = db.rawQuery(
                    "SELECT 1 FROM poems WHERE id = ?", arrayOf(id.toString()),
                ).use { it.moveToFirst() }
                if (!poemExists) { skipped++; return@forEachIndexed }
                val favExists = db.rawQuery(
                    "SELECT 1 FROM favorites WHERE poem_id = ?", arrayOf(id.toString()),
                ).use { it.moveToFirst() }
                if (favExists) { existed++; return@forEachIndexed }
                db.insert("favorites", null, ContentValues().apply {
                    put("poem_id", id)
                    put("created_at", now - index) // 保持导出顺序（首条最新）
                })
                added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ImportResult(added, existed, skipped)
    }
}

/** 书库准备状态：Checking=启动检查（秒开路径不打扰）；Copying=首启/升级解压拷库；Ready；Failed。 */
enum class PrepState { Checking, Copying, Ready, Failed }

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
