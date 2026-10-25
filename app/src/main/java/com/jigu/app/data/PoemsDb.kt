package com.jigu.app.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * 预构建库（assets/data/jigu.zip 内 jigu.db，由 tools/datapack/build.py 产出）的访问层。
 * 首启/升级在后台线程解压拷库（保留收藏），所有查询经 awaitData() 等待就绪。
 */
class PoemsDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, HELPER_DB_VERSION) {

    companion object {
        const val DB_NAME = "jigu.db"

        /** 随数据包版本升级（tools/datapack/build.py 的 DATA_VERSION）；不一致则重拷库 */
        const val REQUIRED_DATA_VERSION = 6

        /** 必须与 build.py 的 SCHEMA_VERSION 一致（即库文件 PRAGMA user_version） */
        const val HELPER_DB_VERSION = 4

        private const val CORE_COLUMNS =
            "p.id, p.category, p.title, IFNULL(a.name,'佚名') AS author, IFNULL(a.brief,'') AS brief, " +
                "p.rhythmic, p.chapter, p.section, p.paragraphs, p.tags, p.notes, p.body, " +
                "IFNULL(a.dynasty,'') AS dynasty, p.type AS type, p.era AS era"

        private const val IMAGERY_SUB =
            "(SELECT GROUP_CONCAT(i.name, '、') FROM poem_imagery pi " +
                "JOIN imagery i ON i.id = pi.imagery_id WHERE pi.poem_id = p.id)"

        private const val FAMOUS_SUB =
            "(SELECT f.line FROM famous_lines f WHERE f.poem_id = p.id)"

        private const val AUTHOR_COLUMNS =
            "a.id, a.name, a.dynasty, a.brief, a.birth_year, a.death_year, count(p.id) AS cnt"

        /** 精选池（日推普通日子）：公版经典 + 三百首 + 名句 */
        private const val CURATED_WHERE =
            "category IN ('shijing','chuci','qing_ci','youmengying','guwen','lunyu','sishu','han_wei') " +
                "OR tags LIKE '%\"唐诗三百首\"%' " +
                "OR EXISTS (SELECT 1 FROM famous_lines f WHERE f.poem_id = p.id)"

        @Volatile
        private var instance: PoemsDb? = null

        fun get(context: Context): PoemsDb =
            instance ?: synchronized(this) { instance ?: PoemsDb(context).also { instance = it } }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("jigu_data", Context.MODE_PRIVATE)
    private val readyLatch = CountDownLatch(1)

    init {
        Thread {
            try {
                val dbFile = appContext.getDatabasePath(DB_NAME)
                val installed = prefs.getInt("data_version", -1)
                if (!dbFile.exists() || installed != REQUIRED_DATA_VERSION) {
                    val kept = if (dbFile.exists()) readFavoritesRaw(dbFile) else emptyList()
                    copyFromAssets(dbFile)
                    if (kept.isNotEmpty()) restoreFavorites(dbFile, kept)
                    prefs.edit().putInt("data_version", REQUIRED_DATA_VERSION).apply()
                }
            } catch (t: Throwable) {
                android.util.Log.e("PoemsDb", "数据拷库失败", t)
            } finally {
                readyLatch.countDown()
            }
        }.start()
    }

    private fun awaitData() {
        readyLatch.await()
    }

    private fun rdb(): SQLiteDatabase {
        awaitData()
        return readableDatabase
    }

    private fun wdb(): SQLiteDatabase {
        awaitData()
        return writableDatabase
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

    override fun onCreate(db: SQLiteDatabase?) = Unit
    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) = Unit

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

    // ---------- 行映射 ----------

    private fun android.database.Cursor.toPoem(): Poem {
        val i = this
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
            imagery = (if (i.isNull(13)) "" else i.getString(13))
                .split('、').filter { it.isNotBlank() },
            famousLine = if (i.isNull(14)) "" else i.getString(14) ?: "",
            type = i.getString(15) ?: "",
            era = i.getString(16) ?: "",
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

    fun tagCount(tag: String): Int {
        awaitData()
        return rdb().rawQuery(
            "SELECT count(*) FROM poems WHERE tags LIKE ?", arrayOf("%\"$tag\"%"),
        ).use { c -> c.moveToFirst(); c.getInt(0) }
    }

    fun curatedCount(): Int {
        awaitData()
        cachedCurated?.let { return it }
        val n = rdb().rawQuery(
            "SELECT count(*) FROM poems p WHERE $CURATED_WHERE", null,
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        cachedCurated = n
        return n
    }

    @Volatile
    private var cachedCurated: Int? = null

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

    fun curatedAtOffset(offset: Int): Poem? = queryPoem(
        "$POEM_SELECT WHERE $CURATED_WHERE LIMIT 1 OFFSET ?", arrayOf(offset.toString()),
    )

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

    @Volatile
    private var cachedThemes: Map<String, Int>? = null

    fun listByTheme(theme: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "$POEM_SELECT JOIN poem_theme pt ON pt.poem_id = p.id " +
            "JOIN themes t ON t.id = pt.theme_id WHERE t.name = ? " +
            "ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf(theme, limit.toString(), offset.toString()),
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
        return if (q.length >= 3) {
            runCatching {
                queryPoems(
                    "SELECT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line " +
                        "FROM poems_fts JOIN poems p ON p.id = poems_fts.rowid " +
                        "LEFT JOIN authors a ON a.id = p.author_id " +
                        "WHERE poems_fts MATCH ? LIMIT ?",
                    arrayOf("\"$q\"", limit.toString()),
                )
            }.getOrDefault(searchByLike(q, limit))
        } else {
            searchByLike(q, limit)
        }
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
