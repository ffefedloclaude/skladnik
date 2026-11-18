package sk.skladnik.app

/**
 * Rozpozná, čo prišlo zo skenera (čiarový kód, QR kód, NFC alebo ručné zadanie).
 *
 * - čisté 6-miestne číslo = číslo skladovej karty,
 * - iné čisté číslo (8, 12, 13, 14 číslic…) = EAN,
 * - text z QR kódu (napr. „Clinil 1 lt - 4028163018201“) – vyberie sa z neho EAN
 *   a prípadne aj 6-miestne číslo karty.
 */
object ScanParser {

    data class Result(val code: String?, val ean: String?, val raw: String)

    private val DIGIT_RUN = Regex("\\d+")

    /** Kontrolná číslica EAN-8 / UPC-A / EAN-13 / GTIN-14. */
    fun isValidEan(e: String): Boolean {
        if (e.length !in setOf(8, 12, 13, 14) || !e.all { it.isDigit() }) return false
        val d = e.map { it - '0' }
        var sum = 0
        d.dropLast(1).reversed().forEachIndexed { i, x -> sum += x * (if (i % 2 == 0) 3 else 1) }
        return (10 - sum % 10) % 10 == d.last()
    }

    fun isCardCode(s: String): Boolean = s.length == 6 && s.all { it.isDigit() }

    fun parse(input: String): Result {
        val raw = input.trim()
        if (raw.isEmpty()) return Result(null, null, raw)

        val compact = raw.replace(" ", "").replace(" ", "")
        if (compact.isNotEmpty() && compact.all { it.isDigit() }) {
            return if (compact.length == 6) Result(compact, null, raw) else Result(null, compact, raw)
        }

        // Text (typicky z QR kódu) – hľadáme v ňom čísla
        val runs = DIGIT_RUN.findAll(raw).map { it.value }.toList()
        val eanCandidates = runs.filter { it.length in 8..14 }
        val ean = eanCandidates.lastOrNull { isValidEan(it) } ?: eanCandidates.lastOrNull()
        val code = runs.lastOrNull { it.length == 6 }
        if (ean == null && code == null) {
            // Iný alfanumerický kód (napr. Code 128) – správa sa ako doteraz, ako EAN
            return Result(null, raw, raw)
        }
        return Result(code, ean, raw)
    }
}
