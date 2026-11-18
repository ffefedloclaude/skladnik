package sk.skladnik.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Čítanie tabuliek (.xlsx a .csv) bez externých knižníc. */
object TableReader {

    fun read(bytes: ByteArray, fileName: String?): List<List<String>> {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (isZip) return readXlsx(bytes)
        val isOldXls = bytes.size > 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()
        if (isOldXls) throw IllegalArgumentException(
            "Starý formát .xls nie je podporovaný. V Exceli ulož súbor ako .xlsx (Excel zošit) alebo .csv."
        )
        return readCsv(bytes)
    }

    // ---------- XLSX ----------

    private fun localName(p: XmlPullParser): String = p.name.substringAfter(':')

    private fun readXlsx(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name.trimStart('/')
                if (n.endsWith(".xml") || n.endsWith(".rels")) files[n] = zip.readBytes()
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetPath = firstSheetPath(files)
            ?: files.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.minOrNull()
            ?: throw IllegalArgumentException("V súbore sa nenašiel žiadny hárok.")
        val sheet = files[sheetPath] ?: throw IllegalArgumentException("Hárok sa nedá prečítať.")
        return parseSheet(sheet, shared)
    }

    private fun firstSheetPath(files: Map<String, ByteArray>): String? {
        val wb = files["xl/workbook.xml"] ?: return null
        val rels = files["xl/_rels/workbook.xml.rels"] ?: return null
        var rid: String? = null
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(wb), null)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && localName(p) == "sheet") {
                for (i in 0 until p.attributeCount) {
                    if (p.getAttributeName(i).endsWith("id") && p.getAttributeName(i) != "sheetId") {
                        rid = p.getAttributeValue(i)
                    }
                }
                break
            }
        }
        if (rid == null) return null
        val r = Xml.newPullParser()
        r.setInput(ByteArrayInputStream(rels), null)
        while (r.next() != XmlPullParser.END_DOCUMENT) {
            if (r.eventType == XmlPullParser.START_TAG && localName(r) == "Relationship" &&
                r.getAttributeValue(null, "Id") == rid
            ) {
                val target = r.getAttributeValue(null, "Target") ?: return null
                return if (target.startsWith("/")) target.trimStart('/') else "xl/" + target.removePrefix("./")
            }
        }
        return null
    }

    private fun parseSharedStrings(data: ByteArray): List<String> {
        val list = ArrayList<String>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), null)
        var sb: StringBuilder? = null
        var inT = false
        var inRph = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (localName(p)) {
                    "si" -> sb = StringBuilder()
                    "t" -> inT = true
                    "rPh" -> inRph = true
                }
                XmlPullParser.END_TAG -> when (localName(p)) {
                    "si" -> { list += sb?.toString() ?: ""; sb = null }
                    "t" -> inT = false
                    "rPh" -> inRph = false
                }
                XmlPullParser.TEXT -> if (inT && !inRph) sb?.append(p.text)
            }
        }
        return list
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
            else break
        }
        return n - 1
    }

    private fun parseSheet(data: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), null)
        var row: MutableList<String>? = null
        var cellType: String? = null
        var cellCol = 0
        var value: StringBuilder? = null
        var inV = false
        var inT = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (localName(p)) {
                    "row" -> row = ArrayList()
                    "c" -> {
                        cellType = p.getAttributeValue(null, "t")
                        val r = p.getAttributeValue(null, "r")
                        cellCol = if (r != null) colIndex(r) else (row?.size ?: 0)
                        value = StringBuilder()
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) value?.append(p.text)
                XmlPullParser.END_TAG -> when (localName(p)) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = value?.toString() ?: ""
                        val text = when (cellType) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "inlineStr", "str" -> raw
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            else -> numberToText(raw)
                        }
                        row?.let { r ->
                            if (cellCol >= 0) {
                                while (r.size < cellCol) r.add("")
                                if (r.size == cellCol) r.add(text) else r[cellCol] = text
                            }
                        }
                        value = null
                    }
                    "row" -> { row?.let { rows += it }; row = null }
                }
            }
        }
        return rows
    }

    /** Čísla z Excelu (napr. 8.586E12) prevedie na čistý text bez exponentu a ".0". */
    fun numberToText(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        return try {
            val bd = BigDecimal(s)
            val stripped = bd.stripTrailingZeros()
            if (stripped.scale() <= 0) stripped.toBigInteger().toString() else stripped.toPlainString()
        } catch (_: NumberFormatException) {
            s
        }
    }

    // ---------- CSV ----------

    private fun decode(bytes: ByteArray): String {
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            b = b.copyOfRange(3, b.size)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) {
            String(b, Charset.forName("windows-1250"))
        }
    }

    private fun readCsv(bytes: ByteArray): List<List<String>> {
        val text = decode(bytes)
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        val delim = listOf(';', '\t', ',').maxByOrNull { d -> firstLine.count { it == d } } ?: ';'
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false
                } else cell.append(ch)
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    delim -> { row.add(cell.toString()); cell.setLength(0) }
                    '\r' -> {}
                    '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = ArrayList() }
                    else -> cell.append(ch)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }
}

/** Prevod načítanej tabuľky na skladové karty. */
object CardImporter {

    data class Result(val cards: List<Card>, val info: String)

    private fun norm(s: String) = Db.normalize(s)

    fun parseNumber(s: String?): Double? {
        if (s == null) return null
        val t = s.trim().replace(" ", "").replace(" ", "").replace(',', '.')
        return t.toDoubleOrNull()
    }

    fun import(rows: List<List<String>>): Result {
        val nonEmpty = rows.filter { r -> r.any { it.isNotBlank() } }
        if (nonEmpty.isEmpty()) throw IllegalArgumentException("Súbor je prázdny.")

        // Nájdeme riadok s hlavičkou (prvých 10 riadkov)
        var headerIdx = -1
        var colCode = -1; var colName = -1; var colEan = -1; var colQty = -1; var colQr = -1
        for ((idx, r) in nonEmpty.take(10).withIndex()) {
            var c = -1; var n = -1; var e = -1; var q = -1; var qr = -1
            r.forEachIndexed { i, cell ->
                val h = norm(cell)
                when {
                    qr < 0 && (h == "qr" || h.startsWith("qr ") || h.contains("qr kod") || h.contains("qrcode") || h.contains("qr-kod")) -> qr = i
                    e < 0 && (h.contains("ean") || h.contains("ciarov") || h.contains("barcode")) -> e = i
                    n < 0 && (h.contains("nazov") || h.contains("nazev") || h.contains("popis") || h.contains("tovar") || h.contains("polozka")) -> n = i
                    q < 0 && (h.contains("mnoz") || h.contains("stav") || h.contains("pocet") || h.contains("ks") || h.contains("qty") || h.contains("zasob")) -> q = i
                    c < 0 && (h.contains("kod") || h.contains("cislo") || h.contains("karta") || h == "id" || h.contains("plu")) -> c = i
                }
            }
            if (n >= 0 && (e >= 0 || c >= 0 || qr >= 0)) {
                headerIdx = idx; colCode = c; colName = n; colEan = e; colQty = q; colQr = qr
                break
            }
        }
        val data: List<List<String>>
        if (headerIdx < 0) {
            // Bez hlavičky: A = kód, B = názov, C = EAN, D = množstvo
            colCode = 0; colName = 1; colEan = 2; colQty = 3
            data = nonEmpty
        } else {
            data = nonEmpty.drop(headerIdx + 1)
        }

        val map = LinkedHashMap<String, Triple<String, Double?, LinkedHashSet<String>>>()
        for (r in data) {
            fun cell(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
            val name = cell(colName)
            var code = cell(colCode)
            // QR kód (napr. „Názov - EAN“ alebo číslo karty) – vyberieme z neho EAN a číslo karty
            val qr = if (colQr >= 0) ScanParser.parse(cell(colQr)) else null
            if (code.isEmpty() && qr?.code != null) code = qr.code
            if (name.isEmpty() && code.isEmpty()) continue
            if (code.isEmpty()) code = name
            val eans = (cell(colEan).split(Regex("[,;/|\\s]+")) + listOfNotNull(qr?.ean?.takeIf { qr.code != null || it.all { ch -> ch.isDigit() } }))
                .map { TableReader.numberToText(it).trim() }
                .filter { it.isNotEmpty() && !it.equals("nema", true) && !it.equals("ean", true) && it.any { ch -> ch.isDigit() } }
            val qty = parseNumber(cell(colQty))
            val existing = map[code]
            if (existing == null) {
                map[code] = Triple(name.ifEmpty { code }, qty, LinkedHashSet(eans))
            } else {
                existing.third.addAll(eans)
                if (existing.second == null && qty != null) map[code] = Triple(existing.first, qty, existing.third)
            }
        }
        val cards = map.map { (code, t) -> Card(code, t.first, t.second, t.third.toList()) }
        val cols = buildList {
            if (colCode >= 0) add("kód=${colLetter(colCode)}")
            add("názov=${colLetter(colName)}")
            if (colEan >= 0) add("EAN=${colLetter(colEan)}")
            if (colQr >= 0) add("QR=${colLetter(colQr)}")
            if (colQty >= 0) add("množstvo=${colLetter(colQty)}")
        }.joinToString(", ")
        return Result(cards, "Stĺpce: $cols")
    }

    private fun colLetter(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
        return sb.toString()
    }
}

/** Zápis tabuliek do súboru .xlsx (bez externých knižníc). */
object XlsxWriter {

    private fun esc(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> if (ch.code >= 0x20 || ch == '\t' || ch == '\n') append(ch)
            }
        }
    }

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
        return sb.toString()
    }

    /** Zoznam inventúry – rovnaké stĺpce ako doteraz. */
    fun write(out: OutputStream, records: List<Record>) {
        val df = SimpleDateFormat("d.M.yyyy HH:mm:ss", Locale("sk"))
        val header = listOf("Kód", "Názov skladovej karty", "EAN", "Napočítané množstvo", "Stav v databáze", "Rozdiel", "Dátum a čas")
        val rows = records.sortedBy { it.id }.map { rec ->
            listOf<Any?>(
                rec.code, rec.name, rec.ean, rec.qty, rec.stock,
                rec.stock?.let { rec.qty - it }, df.format(Date(rec.time))
            )
        }
        writeSheet(out, "Inventúra", header, rows, listOf(14, 40, 18, 20, 16, 12, 20))
    }

    /** Všetky skladové karty s EAN kódmi (aj s novými, pridanými v telefóne). */
    fun writeCards(out: OutputStream, cards: List<Card>) {
        val header = listOf("Kód", "Názov skladovej karty", "EAN", "JM", "Zdroj EAN")
        val rows = ArrayList<List<Any?>>()
        for (c in cards.sortedBy { it.code }) {
            if (c.eans.isEmpty()) rows += listOf<Any?>(c.code, c.name, "", c.unit, "chýba EAN")
            for (e in c.eans) rows += listOf<Any?>(c.code, c.name, e, c.unit, if (e in c.newEans) "nový (telefón)" else "databáza")
        }
        writeSheet(out, "Karty", header, rows, listOf(12, 44, 18, 6, 16))
    }

    /** Všeobecný zápis jedného hárku. Čísla (Double/Int/Long) sa zapíšu ako čísla, ostatné ako text. */
    fun writeSheet(out: OutputStream, sheetName: String, header: List<String>, rows: List<List<Any?>>, widths: List<Int>) {
        val lastCol = col(header.size - 1)
        val sheet = StringBuilder()
        sheet.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sheet.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sheet.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sheet.append("<cols>")
        widths.forEachIndexed { i, w ->
            sheet.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
        }
        sheet.append("</cols><sheetData>")

        fun str(r: Int, c: Int, v: String, style: Int = 0) =
            """<c r="${col(c)}$r" t="inlineStr"${if (style > 0) " s=\"$style\"" else ""}><is><t xml:space="preserve">${esc(v)}</t></is></c>"""
        fun num(r: Int, c: Int, v: Double) = """<c r="${col(c)}$r"><v>${TableReader.numberToText(BigDecimal.valueOf(v).toPlainString())}</v></c>"""

        sheet.append("""<row r="1">""")
        header.forEachIndexed { i, h -> sheet.append(str(1, i, h, 1)) }
        sheet.append("</row>")
        rows.forEachIndexed { idx, row ->
            val r = idx + 2
            sheet.append("""<row r="$r">""")
            row.forEachIndexed { c, v ->
                when (v) {
                    null -> {}
                    is Double -> sheet.append(num(r, c, v))
                    is Int -> sheet.append(num(r, c, v.toDouble()))
                    is Long -> sheet.append(num(r, c, v.toDouble()))
                    else -> sheet.append(str(r, c, v.toString()))
                }
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData>")
        if (rows.isNotEmpty()) sheet.append("""<autoFilter ref="A1:$lastCol${rows.size + 1}"/>""")
        sheet.append("</worksheet>")

        val sheetEsc = esc(sheetName)
        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="$sheetEsc" sheetId="1" r:id="rId1"/></sheets>${if (rows.isNotEmpty()) """<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">'$sheetEsc'!${'$'}A${'$'}1:${'$'}$lastCol${'$'}${rows.size + 1}</definedName></definedNames>""" else ""}</workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDDF3EC"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""",
            "xl/worksheets/sheet1.xml" to sheet.toString()
        )
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}

/** Zápis inventúry do jednoduchého XML (UTF-8). */
object InventoryXmlWriter {

    private fun esc(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
                else -> if (ch.code >= 0x20 || ch == '\t' || ch == '\n') append(ch)
            }
        }
    }

    private fun n(d: Double): String = TableReader.numberToText(BigDecimal.valueOf(d).toPlainString())

    fun write(out: OutputStream, records: List<Record>) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<inventura datum_cas_vyhotovenia=\"${iso.format(Date())}\" pocet=\"${records.size}\">\n")
        for (r in records.sortedBy { it.id }) {
            sb.append("    <polozka>\n")
            sb.append("        <kod>${esc(r.code)}</kod>\n")
            sb.append("        <nazov>${esc(r.name)}</nazov>\n")
            sb.append("        <ean>${esc(r.ean)}</ean>\n")
            sb.append("        <mnozstvo>${n(r.qty)}</mnozstvo>\n")
            if (r.stock != null) {
                sb.append("        <stavVDatabaze>${n(r.stock)}</stavVDatabaze>\n")
                sb.append("        <rozdiel>${n(r.qty - r.stock)}</rozdiel>\n")
            }
            sb.append("        <datumCas>${iso.format(Date(r.time))}</datumCas>\n")
            sb.append("    </polozka>\n")
        }
        sb.append("</inventura>\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }
}
