package sk.skladnik.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.Normalizer

data class Card(
    val code: String,
    val name: String,
    val stock: Double?,
    val eans: List<String>
) {
    val search: String = Db.normalize("$name $code")
}

data class Record(
    val id: Long,
    val code: String,
    val name: String,
    val ean: String,
    val qty: Double,
    val stock: Double?,
    val time: Long
)

/** Lokálna databáza: skladové karty, ich EAN kódy a zapísané záznamy inventúry. */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "skladnik.db", null, 1) {

    companion object {
        @Volatile private var instance: Db? = null
        fun get(context: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(context).also { instance = it } }

        private val MARKS = Regex("\\p{M}+")
        fun normalize(s: String): String =
            MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase().trim()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cards (code TEXT PRIMARY KEY, name TEXT NOT NULL, stock REAL)")
        db.execSQL("CREATE TABLE eans (ean TEXT NOT NULL, code TEXT NOT NULL, PRIMARY KEY (ean, code))")
        db.execSQL("CREATE INDEX eans_ean ON eans(ean)")
        db.execSQL(
            "CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT, name TEXT, " +
                "ean TEXT, qty REAL, stock REAL, time INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- Skladové karty ----------

    @Volatile private var cache: List<Card>? = null

    fun replaceCards(cards: Collection<Card>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("eans", null, null)
            db.delete("cards", null, null)
            for (c in cards) {
                db.insertWithOnConflict("cards", null, ContentValues().apply {
                    put("code", c.code); put("name", c.name)
                    if (c.stock != null) put("stock", c.stock) else putNull("stock")
                }, SQLiteDatabase.CONFLICT_REPLACE)
                for (e in c.eans) {
                    db.insertWithOnConflict("eans", null, ContentValues().apply {
                        put("ean", e); put("code", c.code)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cache = null
    }

    fun addEanToCard(ean: String, code: String) {
        writableDatabase.insertWithOnConflict("eans", null, ContentValues().apply {
            put("ean", ean); put("code", code)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        cache = null
    }

    fun cards(): List<Card> {
        cache?.let { return it }
        val eanMap = HashMap<String, MutableList<String>>()
        readableDatabase.rawQuery("SELECT ean, code FROM eans ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) eanMap.getOrPut(c.getString(1)) { mutableListOf() }.add(c.getString(0))
        }
        val list = ArrayList<Card>()
        readableDatabase.rawQuery("SELECT code, name, stock FROM cards ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                val code = c.getString(0)
                list += Card(code, c.getString(1), if (c.isNull(2)) null else c.getDouble(2), eanMap[code] ?: emptyList())
            }
        }
        cache = list
        return list
    }

    fun cardCount(): Int = cards().size
    fun eanCount(): Int = cards().sumOf { it.eans.size }

    /** Všetky karty, ku ktorým patrí daný EAN (môže ich byť aj viac). */
    fun cardsByEan(ean: String): List<Card> {
        val e = ean.trim()
        if (e.isEmpty()) return emptyList()
        // Toleruje aj chýbajúcu/prebytočnú úvodnú nulu (UPC-A vs EAN-13).
        val variants = setOf(e, e.trimStart('0'), "0$e")
        return cards().filter { card -> card.eans.any { it in variants || it.trimStart('0') == e.trimStart('0') } }
    }

    fun cardByCode(code: String): Card? = cards().firstOrNull { it.code == code }

    fun searchCards(query: String, limit: Int = 50): List<Card> {
        val words = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return cards().asSequence()
            .filter { c -> words.all { c.search.contains(it) } || c.eans.any { it.contains(query.trim()) } }
            .take(limit).toList()
    }

    // ---------- Záznamy inventúry ----------

    fun addRecord(code: String, name: String, ean: String, qty: Double, stock: Double?): Long =
        writableDatabase.insert("records", null, ContentValues().apply {
            put("code", code); put("name", name); put("ean", ean); put("qty", qty)
            if (stock != null) put("stock", stock) else putNull("stock")
            put("time", System.currentTimeMillis())
        })

    fun updateQty(id: Long, qty: Double) {
        writableDatabase.update("records", ContentValues().apply { put("qty", qty) }, "id=?", arrayOf(id.toString()))
    }

    fun deleteRecord(id: Long) {
        writableDatabase.delete("records", "id=?", arrayOf(id.toString()))
    }

    fun clearRecords() {
        writableDatabase.delete("records", null, null)
    }

    fun recordCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM records", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun records(): List<Record> {
        val list = ArrayList<Record>()
        readableDatabase.rawQuery(
            "SELECT id, code, name, ean, qty, stock, time FROM records ORDER BY id DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                list += Record(
                    c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                    c.getDouble(4), if (c.isNull(5)) null else c.getDouble(5), c.getLong(6)
                )
            }
        }
        return list
    }
}
