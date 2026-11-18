package sk.skladnik.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.text.Normalizer

data class Card(
    val code: String,
    val name: String,
    val stock: Double?,
    val eans: List<String>,
    val unit: String = "",
    /** EAN kódy pridané v telefóne skenovaním (ešte nie sú v BlueGastro). */
    val newEans: List<String> = emptyList()
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
    SQLiteOpenHelper(context.applicationContext, "skladnik.db", null, 2) {

    private val appContext = context.applicationContext

    companion object {
        @Volatile private var instance: Db? = null
        fun get(context: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(context).also { instance = it } }

        private val MARKS = Regex("\\p{M}+")
        fun normalize(s: String): String =
            MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase().trim()

        private const val PREFS = "skladnik"
        private const val XML_FILE = "bluegastro_export.xml"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cards (code TEXT PRIMARY KEY, name TEXT NOT NULL, stock REAL, unit TEXT)")
        db.execSQL("CREATE TABLE eans (ean TEXT NOT NULL, code TEXT NOT NULL, isNew INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (ean, code))")
        db.execSQL("CREATE INDEX eans_ean ON eans(ean)")
        db.execSQL(
            "CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT, name TEXT, " +
                "ean TEXT, qty REAL, stock REAL, time INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE cards ADD COLUMN unit TEXT")
            db.execSQL("ALTER TABLE eans ADD COLUMN isNew INTEGER NOT NULL DEFAULT 0")
        }
    }

    // ---------- Zdroj databázy (XML z BlueGastro alebo tabuľka) ----------

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Názov načítaného súboru, alebo null. */
    val sourceName: String? get() = prefs.getString("sourceName", null)

    /** Pôvodný XML export z BlueGastro (ak bola databáza načítaná z XML). */
    fun sourceXml(): ByteArray? {
        val f = File(appContext.filesDir, XML_FILE)
        return if (f.exists()) f.readBytes() else null
    }

    fun hasSourceXml(): Boolean = File(appContext.filesDir, XML_FILE).exists()

    fun setSource(name: String?, xml: ByteArray?) {
        val f = File(appContext.filesDir, XML_FILE)
        if (xml != null) f.writeBytes(xml) else f.delete()
        prefs.edit().putString("sourceName", name).apply()
    }

    // ---------- Skladové karty ----------

    @Volatile private var cache: List<Card>? = null

    /**
     * Nahradí databázu kariet. EAN kódy pridané v telefóne sa zachovajú pri kartách, ktoré
     * v novom súbore stále sú (a ak ich nový súbor už obsahuje, prestanú byť „nové“).
     * Vráti počet zachovaných nových EAN.
     */
    fun replaceCards(cards: Collection<Card>): Int {
        val db = writableDatabase
        val keptNew = HashMap<String, MutableList<String>>()
        db.rawQuery("SELECT ean, code FROM eans WHERE isNew=1", null).use { c ->
            while (c.moveToNext()) keptNew.getOrPut(c.getString(1)) { mutableListOf() }.add(c.getString(0))
        }
        var kept = 0
        db.beginTransaction()
        try {
            db.delete("eans", null, null)
            db.delete("cards", null, null)
            for (c in cards) {
                db.insertWithOnConflict("cards", null, ContentValues().apply {
                    put("code", c.code); put("name", c.name); put("unit", c.unit)
                    if (c.stock != null) put("stock", c.stock) else putNull("stock")
                }, SQLiteDatabase.CONFLICT_REPLACE)
                for (e in c.eans) {
                    db.insertWithOnConflict("eans", null, ContentValues().apply {
                        put("ean", e); put("code", c.code); put("isNew", 0)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                keptNew[c.code]?.forEach { e ->
                    if (e !in c.eans) {
                        val id = db.insertWithOnConflict("eans", null, ContentValues().apply {
                            put("ean", e); put("code", c.code); put("isNew", 1)
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                        if (id != -1L) kept++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cache = null
        return kept
    }

    fun addEanToCard(ean: String, code: String) {
        writableDatabase.insertWithOnConflict("eans", null, ContentValues().apply {
            put("ean", ean); put("code", code); put("isNew", 1)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        cache = null
    }

    fun removeNewEan(ean: String, code: String) {
        writableDatabase.delete("eans", "ean=? AND code=? AND isNew=1", arrayOf(ean, code))
        cache = null
    }

    fun cards(): List<Card> {
        cache?.let { return it }
        val eanMap = HashMap<String, MutableList<String>>()
        val newMap = HashMap<String, MutableList<String>>()
        readableDatabase.rawQuery("SELECT ean, code, isNew FROM eans ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) {
                eanMap.getOrPut(c.getString(1)) { mutableListOf() }.add(c.getString(0))
                if (c.getInt(2) == 1) newMap.getOrPut(c.getString(1)) { mutableListOf() }.add(c.getString(0))
            }
        }
        val list = ArrayList<Card>()
        readableDatabase.rawQuery("SELECT code, name, stock, unit FROM cards ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                val code = c.getString(0)
                list += Card(
                    code, c.getString(1), if (c.isNull(2)) null else c.getDouble(2),
                    eanMap[code] ?: emptyList(), c.getString(3) ?: "", newMap[code] ?: emptyList()
                )
            }
        }
        cache = list
        return list
    }

    fun cardCount(): Int = cards().size
    fun eanCount(): Int = cards().sumOf { it.eans.size }
    fun newEanCount(): Int = cards().sumOf { it.newEans.size }
    fun cardsWithoutEan(): List<Card> = cards().filter { it.eans.isEmpty() }
    fun cardsWithNewEan(): List<Card> = cards().filter { it.newEans.isNotEmpty() }

    /** Všetky karty, ku ktorým patrí daný EAN (môže ich byť aj viac). */
    fun cardsByEan(ean: String): List<Card> {
        val e = ean.trim()
        if (e.isEmpty()) return emptyList()
        // Toleruje aj chýbajúcu/prebytočnú úvodnú nulu (UPC-A vs EAN-13).
        val variants = setOf(e, e.trimStart('0'), "0$e")
        return cards().filter { card -> card.eans.any { it in variants || it.trimStart('0') == e.trimStart('0') } }
    }

    fun cardByCode(code: String): Card? = cards().firstOrNull { it.code == code.trim() }

    fun searchCards(query: String, limit: Int = 50, source: List<Card>? = null): List<Card> {
        val words = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val all = source ?: cards()
        if (words.isEmpty()) return if (source != null) all.take(limit) else emptyList()
        return all.asSequence()
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
