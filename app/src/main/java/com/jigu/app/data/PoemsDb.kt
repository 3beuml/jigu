package com.jigu.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.File

/**
 * 预构建库（assets/data/jigu-core.db，由 tools/datapack/build.py 产出）的只读访问层。
 * 首次启动把库拷到内部存储；收藏写回同一文件（favorites 表随库自带）。
 */
class PoemsDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, 1) {

    companion object {
        const val DB_NAME = "jigu.db"
        private const val CORE_COLUMNS =
            "p.id, p.category, p.title, IFNULL(a.name,'佚名') AS author, IFNULL(a.brief,'') AS brief, " +
                "p.rhythmic, p.chapter, p.section, p.paragraphs, p.tags, p.notes, p.body, IFNULL(a.dynasty,'') AS dynasty"

        private const val IMAGERY_SUB =
            "(SELECT GROUP_CONCAT(i.name, '、') FROM poem_imagery pi " +
                "JOIN imagery i ON i.id = pi.imagery_id WHERE pi.poem_id = p.id)"

        private const val FAMOUS_SUB =
            "(SELECT f.line FROM famous_lines f WHERE f.poem_id = p.id) AS famous_line"

        @Volatile
        private var instance: PoemsDb? = null

        fun get(context: Context): PoemsDb =
            instance ?: synchronized(this) { instance ?: PoemsDb(context).also { instance = it } }
    }

    private val appContext = context.applicationContext

    init {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) copyFromAssets(dbFile)
    }

    private fun copyFromAssets(dbFile: File) {
        dbFile.parentFile?.mkdirs()
        // 资产名是构建产物 jigu-core.db；落盘为运行库名 jigu.db
        appContext.assets.open("data/jigu-core.db").use { input ->
            dbFile.outputStream().use { output -> input.copyTo(output) }
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
    )

    // ---------- 基础查询 ----------

    fun count(): Int =
        readableDatabase.rawQuery("SELECT count(*) FROM poems", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun categoryCounts(): Map<String, Int> =
        readableDatabase.rawQuery(
            "SELECT category, count(*) FROM poems GROUP BY category", null,
        ).use { c ->
            val out = mutableMapOf<String, Int>()
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            out
        }

    private fun queryPoem(sql: String, args: Array<String>): Poem? =
        readableDatabase.rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) c.toPoem() else null
        }

    private fun queryPoems(sql: String, args: Array<String>): List<Poem> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = mutableListOf<Poem>()
            while (c.moveToNext()) out += c.toPoem()
            out
        }

    private fun android.database.Cursor.toPoem(): PoemsDb.Poem {
        val i = this
        return Poem(
            id = i.getInt(0),
            category = i.getString(1),
            title = i.getString(2),
            author = i.getString(3),
            brief = i.getString(4),
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
        )
    }

    private fun String.toJsonList(): List<String> = runCatching {
        val arr = JSONArray(this)
        List(arr.length()) { k -> arr.optString(k) }
    }.getOrDefault(emptyList())

    // ---------- 业务查询 ----------

    fun poemById(id: Int): Poem? = queryPoem(
        "SELECT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line FROM poems p " +
            "LEFT JOIN authors a ON a.id = p.author_id WHERE p.id = ?",
        arrayOf(id.toString()),
    )

    fun poemAtOffset(offset: Int): Poem? = queryPoem(
        "SELECT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line FROM poems p " +
            "LEFT JOIN authors a ON a.id = p.author_id LIMIT 1 OFFSET ?",
        arrayOf(offset.toString()),
    )

    fun calendarPoemIds(kind: String, key: String): List<Int> =
        readableDatabase.rawQuery(
            "SELECT poem_id FROM calendar_map WHERE kind = ? AND key = ? ORDER BY id",
            arrayOf(kind, key),
        ).use { c ->
            val out = mutableListOf<Int>()
            while (c.moveToNext()) out += c.getInt(0)
            out
        }

    fun listByCategory(category: String, limit: Int, offset: Int): List<Poem> = queryPoems(
        "SELECT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line FROM poems p " +
            "LEFT JOIN authors a ON a.id = p.author_id WHERE p.category = ? " +
            "ORDER BY p.id LIMIT ? OFFSET ?",
        arrayOf(category, limit.toString(), offset.toString()),
    )

    fun search(query: String, limit: Int = 30): List<Poem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return if (q.length >= 3) {
            runCatching {
                queryPoems(
                    "SELECT $CORE_COLUMNS, $IMAGERY_SUB AS imagery FROM poems_fts " +
                        "JOIN poems p ON p.id = poems_fts.rowid " +
                        "LEFT JOIN authors a ON a.id = p.author_id " +
                        "WHERE poems_fts MATCH ? LIMIT ?",
                    arrayOf("\"$q\"", limit.toString()),
                )
            }.getOrDefault(searchByLike(q, limit)) // 设备 FTS5/trigram 不可用或无结果时兜底
        } else {
            searchByLike(q, limit)
        }
    }

    private fun searchByLike(q: String, limit: Int): List<Poem> = queryPoems(
        "SELECT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line FROM poems p " +
            "LEFT JOIN authors a ON a.id = p.author_id " +
            "WHERE p.body LIKE ? OR p.title LIKE ? LIMIT ?",
        arrayOf("%$q%", "%$q%", limit.toString()),
    )

    /** 相关诗词：先同意象，再同作者，最后同分类；排除自身 */
    fun related(poem: Poem, limit: Int = 6): List<Poem> {
        if (poem.imagery.isNotEmpty()) {
            val marks = poem.imagery.joinToString(",") { "?" }
            val rows = queryPoems(
                "SELECT DISTINCT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line FROM poems p " +
                    "LEFT JOIN authors a ON a.id = p.author_id " +
                    "JOIN poem_imagery pi ON pi.poem_id = p.id " +
                    "JOIN imagery i ON i.id = pi.imagery_id " +
                    "WHERE i.name IN ($marks) AND p.id != ? LIMIT ?",
                (poem.imagery + listOf(poem.id.toString(), limit.toString())).toTypedArray(),
            )
            if (rows.isNotEmpty()) return rows
        }
        return queryPoems(
            "SELECT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line FROM poems p " +
                "LEFT JOIN authors a ON a.id = p.author_id " +
                "WHERE p.id != ? AND (a.name = ? OR p.category = ?) LIMIT ?",
            arrayOf(poem.id.toString(), poem.author, poem.category, limit.toString()),
        ).ifEmpty {
            queryPoems(
                "SELECT $CORE_COLUMNS, $IMAGERY_SUB AS imagery, $FAMOUS_SUB AS famous_line FROM poems p " +
                    "LEFT JOIN authors a ON a.id = p.author_id WHERE p.id != ? LIMIT ?",
                arrayOf(poem.id.toString(), limit.toString()),
            )
        }
    }

    // ---------- 收藏 ----------

    fun isFavorite(poemId: Int): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM favorites WHERE poem_id = ?", arrayOf(poemId.toString()),
        ).use { it.moveToFirst() }

    /** @return 操作后的收藏状态 */
    fun toggleFavorite(poemId: Int, now: Long = System.currentTimeMillis()): Boolean {
        val was = isFavorite(poemId)
        writableDatabase.apply {
            if (was) delete("favorites", "poem_id = ?", arrayOf(poemId.toString()))
            else insert("favorites", null, android.content.ContentValues().apply {
                put("poem_id", poemId)
                put("created_at", now)
            })
        }
        return !was
    }

    fun favoriteIds(): List<Int> = readableDatabase.rawQuery(
        "SELECT poem_id FROM favorites ORDER BY created_at DESC", null,
    ).use { c ->
        val out = mutableListOf<Int>()
        while (c.moveToNext()) out += c.getInt(0)
        out
    }

    fun poemsByIds(ids: List<Int>): List<Poem> = ids.mapNotNull { poemById(it) }
}
