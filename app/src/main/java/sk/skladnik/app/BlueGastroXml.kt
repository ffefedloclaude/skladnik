package sk.skladnik.app

import java.nio.charset.Charset

/**
 * Práca s XML exportom tovarov z BlueGastro (ExportTovarJedlo.xml, kódovanie WINDOWS-1250).
 *
 * - [parse] načíta skladové karty (číslo, názov, jednotku a EAN kódy) z časti <ciselnikTovary>.
 * - [patch] do pôvodného súboru dopíše nové EAN kódy. Všetko ostatné ostane bajt po bajte
 *   bez zmeny, takže súbor sa dá naimportovať späť do BlueGastro.
 */
object BlueGastroXml {

    data class Parsed(val cards: List<Card>, val withoutEan: Int)

    fun looksLikeXml(bytes: ByteArray): Boolean {
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < bytes.size && (bytes[i] == ' '.code.toByte() || bytes[i] == '\n'.code.toByte() ||
                bytes[i] == '\r'.code.toByte() || bytes[i] == '\t'.code.toByte())) i++
        return i < bytes.size && bytes[i] == '<'.code.toByte()
    }

    private fun charsetOf(bytes: ByteArray): Charset {
        val head = String(bytes, 0, minOf(400, bytes.size), Charsets.ISO_8859_1)
        val name = Regex("encoding=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        return try { Charset.forName(name ?: "UTF-8") } catch (_: Exception) { Charsets.UTF_8 }
    }

    /** Export z BlueGastro má v niektorých názvoch poškodenú diakritiku („ďż˝“) – nahradíme ju „?“. */
    fun cleanName(s: String): String = s.replace("ďż˝", "?").replace('\uFFFD', '?')

    private fun unescape(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '&') {
                val semi = s.indexOf(';', i)
                if (semi > i) {
                    val ent = s.substring(i + 1, semi)
                    val rep: String? = when {
                        ent == "amp" -> "&"; ent == "lt" -> "<"; ent == "gt" -> ">"
                        ent == "quot" -> "\""; ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(ch); i++
        }
        return sb.toString()
    }

    private fun tagText(block: String, tag: String, from: Int = 0): String? {
        val a = block.indexOf("<$tag>", from)
        if (a < 0) return null
        val b = block.indexOf("</$tag>", a)
        if (b < 0) return null
        return unescape(block.substring(a + tag.length + 2, b)).trim()
    }

    /** Nájde ďalší blok <tovar …>…</tovar> (nie <tovarJedlo>). Vráti [začiatok, koniec). */
    private fun nextTovar(s: String, from: Int): IntRange? {
        var st = s.indexOf("<tovar", from)
        while (st >= 0) {
            val ch = s.getOrNull(st + 6)
            if (ch == ' ' || ch == '>' || ch == '\t' || ch == '\n' || ch == '\r') break
            st = s.indexOf("<tovar", st + 6)
        }
        if (st < 0) return null
        val en = s.indexOf("</tovar>", st)
        if (en < 0) return null
        return st until en + 8
    }

    fun parse(bytes: ByteArray): Parsed {
        val text = String(bytes, charsetOf(bytes))
        val secStart = text.indexOf("<ciselnikTovary")
        if (secStart < 0) throw IllegalArgumentException(
            "V XML chýba časť <ciselnikTovary>. Je to export tovarov z BlueGastro (ExportTovarJedlo.xml)?"
        )
        val secEnd = text.indexOf("</ciselnikTovary>", secStart).let { if (it < 0) text.length else it }
        val cards = ArrayList<Card>()
        var pos = secStart
        while (true) {
            val r = nextTovar(text, pos) ?: break
            if (r.first > secEnd) break
            val block = text.substring(r.first, r.last + 1)
            pos = r.last + 1
            val code = tagText(block, "cislo") ?: continue
            if (code.isEmpty()) continue
            val name = cleanName(tagText(block, "nazov") ?: code)
            val openTag = block.substring(0, block.indexOf('>') + 1)
            val unit = Regex("\\sjm=\"([^\"]*)\"").find(openTag)?.groupValues?.get(1) ?: ""
            val eans = LinkedHashSet<String>()
            var p = 0
            while (true) {
                val a = block.indexOf("<ean>", p)
                if (a < 0) break
                val b = block.indexOf("</ean>", a)
                if (b < 0) break
                unescape(block.substring(a + 5, b)).trim().takeIf { it.isNotEmpty() }?.let { eans += it }
                p = b + 6
            }
            cards += Card(code, name.ifEmpty { code }, null, eans.toList(), unit)
        }
        if (cards.isEmpty()) throw IllegalArgumentException("V XML sa nenašli žiadne skladové karty.")
        return Parsed(cards, cards.count { it.eans.isEmpty() })
    }

    /**
     * Do pôvodného exportu dopíše nové EAN kódy (číslo karty → zoznam EAN).
     * Vráti nový obsah súboru a počet upravených kariet.
     */
    fun patch(original: ByteArray, add: Map<String, List<String>>): Pair<ByteArray, Int> {
        // ISO-8859-1 mapuje každý bajt na jeden znak, takže zvyšok súboru sa nezmení ani o bajt.
        val s = String(original, Charsets.ISO_8859_1)
        val out = StringBuilder(s.length + add.size * 96)
        var pos = 0
        var changed = 0
        while (true) {
            val r = nextTovar(s, pos) ?: break
            val block = s.substring(r.first, r.last + 1)
            out.append(s, pos, r.first)
            val code = tagText(block, "cislo")
            val list = code?.let { add[it] }
                ?.filter { it.matches(Regex("\\d{6,14}")) && !block.contains("<ean>$it</ean>") }
                ?.distinct()
            if (!list.isNullOrEmpty()) {
                out.append(patchBlock(block, list)); changed++
            } else out.append(block)
            pos = r.last + 1
        }
        out.append(s, pos, s.length)
        return out.toString().toByteArray(Charsets.ISO_8859_1) to changed
    }

    private fun patchBlock(b: String, list: List<String>): String {
        val ind = Regex("\\n([ \\t]*)<cislo>").find(b)?.groupValues?.get(1) ?: "            "
        val step = "    "
        val add = list.joinToString("") { "\n$ind$step<ean>$it</ean>" }
        val close = b.indexOf("</EANy>")
        if (close >= 0) {
            var ws = close
            while (ws > 0 && b[ws - 1].isWhitespace()) ws--
            return b.substring(0, ws) + add + b.substring(ws)
        }
        Regex("<EANy\\s*/>").find(b)?.let { m ->
            return b.replaceRange(m.range, "<EANy>$add\n$ind</EANy>")
        }
        val el = "\n$ind<EANy>$add\n$ind</EANy>"
        for (re in listOf(Regex("</poznamka>|<poznamka\\s*/>"), Regex("</nazov>"))) {
            val f = re.find(b) ?: continue
            val p = f.range.last + 1
            return b.substring(0, p) + el + b.substring(p)
        }
        return b
    }
}
