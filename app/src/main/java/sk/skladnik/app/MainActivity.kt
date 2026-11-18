package sk.skladnik.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.nio.charset.Charset

class MainActivity : Activity() {

    companion object {
        private const val REQ_IMPORT = 1
        private const val REQ_MISSING = 3

        fun fmt(d: Double?): String {
            if (d == null) return "–"
            return if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString()
            else d.toString().replace('.', ',')
        }
    }

    private lateinit var db: Db
    private lateinit var tvDb: TextView
    private lateinit var etCard: AutoCompleteTextView
    private lateinit var etCode: EditText
    private lateinit var tvCardInfo: TextView
    private lateinit var etEan: EditText
    private lateinit var etQty: EditText
    private lateinit var tvStock: TextView
    private lateinit var btnList: Button
    private lateinit var btnMissing: Button
    private lateinit var tvRecent: TextView

    private var selected: Card? = null
    private var settingText = false
    private var nfc: NfcAdapter? = null

    /** Karta bola vybraná zo zoznamu „Bez EAN“ – naskenovaný EAN sa k nej pridá hneď. */
    private var addMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db.get(this)

        tvDb = findViewById(R.id.tvDb)
        etCard = findViewById(R.id.etCard)
        etCode = findViewById(R.id.etCode)
        tvCardInfo = findViewById(R.id.tvCardInfo)
        etEan = findViewById(R.id.etEan)
        etQty = findViewById(R.id.etQty)
        tvStock = findViewById(R.id.tvStock)
        btnList = findViewById(R.id.btnList)
        btnMissing = findViewById(R.id.btnMissing)
        tvRecent = findViewById(R.id.tvRecent)

        // Vyhľadávanie karty podľa názvu
        etCard.threshold = 1
        etCard.setAdapter(CardAdapter(this, db))
        etCard.setOnItemClickListener { parent, _, pos, _ ->
            (parent.getItemAtPosition(pos) as? Card)?.let { selectCard(it, askEan = true) }
        }
        etCard.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!settingText && selected != null && s.toString() != selected?.name) {
                    selected = null
                    addMode = false
                    setCodeText("")
                    showCardInfo()
                }
            }
        })
        findViewById<Button>(R.id.btnClearCard).setOnClickListener {
            clearCard()
            etCard.requestFocus()
            showKeyboard(etCard)
        }

        // Číslo skladovej karty – ručne alebo skenom
        etCode.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT || enter
            ) {
                val c = etCode.text.toString().trim()
                if (c.isNotEmpty()) onCardCode(c) else etEan.requestFocus()
                true
            } else false
        }
        etCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!settingText && selected != null && s.toString().trim() != selected?.code) {
                    selected = null
                    addMode = false
                    settingText = true
                    etCard.setText("", false)
                    settingText = false
                    showCardInfo()
                }
            }
        })

        // EAN / QR – ručne, skenerom (aj hardvérovým, ktorý posiela Enter) alebo cez NFC
        etEan.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                val e = etEan.text.toString().trim()
                if (e.isNotEmpty()) onScanned(e, fromField = true) else etQty.requestFocus()
                true
            } else false
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }

        // Množstvo
        findViewById<Button>(R.id.btnMinus).setOnClickListener { stepQty(-1.0) }
        findViewById<Button>(R.id.btnPlus).setOnClickListener { stepQty(1.0) }
        etQty.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { addRecord(); true } else false
        }

        findViewById<Button>(R.id.btnAdd).setOnClickListener { addRecord() }
        btnList.setOnClickListener { startActivity(Intent(this, RecordsActivity::class.java)) }
        findViewById<Button>(R.id.btnImport).setOnClickListener { pickImportFile() }
        btnMissing.setOnClickListener { openMissing() }

        nfc = NfcAdapter.getDefaultAdapter(this)
        showCardInfo()
    }

    override fun onResume() {
        super.onResume()
        refreshCounts()
        nfc?.let { adapter ->
            if (adapter.isEnabled) {
                val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                adapter.enableReaderMode(this, { tag -> onNfcTag(tag) }, flags, null)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try { nfc?.disableReaderMode(this) } catch (_: Exception) {}
    }

    // ---------- Databáza ----------

    private fun refreshCounts() {
        Thread {
            val cards = db.cardCount()
            val eans = db.eanCount()
            val newEans = db.newEanCount()
            val missing = db.cardsWithoutEan().size
            val recs = db.recordCount()
            val recent = db.records().take(3)
            val source = db.sourceName
            runOnUiThread {
                tvDb.text = if (cards == 0) getString(R.string.db_empty) else {
                    val info = if (newEans > 0) getString(R.string.db_info_new, cards, eans, newEans)
                    else getString(R.string.db_info, cards, eans)
                    if (source != null) info + "\n" + getString(R.string.db_source, source) else info
                }
                btnMissing.visibility = if (cards == 0) View.GONE else View.VISIBLE
                btnMissing.text = getString(R.string.missing_button, missing)
                btnList.text = getString(R.string.records_button, recs)
                if (recent.isEmpty()) tvRecent.visibility = View.GONE else {
                    tvRecent.visibility = View.VISIBLE
                    tvRecent.text = getString(R.string.recent) + "\n" + recent.joinToString("\n") {
                        "• ${it.name} · ${it.ean.ifEmpty { "bez EAN" }} · ${fmt(it.qty)}"
                    }
                }
            }
        }.start()
    }

    private fun pickImportFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel", "text/csv", "text/comma-separated-values",
                    "text/xml", "application/xml", "text/plain", "application/octet-stream"
                )
            )
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            toast(getString(R.string.no_file_picker))
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MISSING && resultCode == RESULT_OK) {
            val code = data?.getStringExtra(MissingActivity.EXTRA_CODE) ?: return
            val card = db.cardByCode(code) ?: return
            clearCard()
            addMode = true
            selectCard(card, askEan = false)
            scan()
            return
        }
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            toast(getString(R.string.importing))
            Thread {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("Súbor sa nedá otvoriť.")
                    val name = displayName(uri) ?: uri.lastPathSegment ?: "súbor"
                    val cards: List<Card>
                    val info: String
                    if (BlueGastroXml.looksLikeXml(bytes)) {
                        cards = BlueGastroXml.parse(bytes).cards
                        info = getString(R.string.source_xml)
                        db.setSource(name, bytes)
                    } else {
                        val rows = TableReader.read(bytes, name)
                        val result = CardImporter.import(rows)
                        cards = result.cards
                        info = result.info
                        if (cards.isNotEmpty()) db.setSource(name, null)
                    }
                    if (cards.isEmpty()) throw IllegalArgumentException("V súbore sa nenašli žiadne skladové karty.")
                    val kept = db.replaceCards(cards)
                    val eans = cards.sumOf { it.eans.size }
                    val missing = cards.count { it.eans.isEmpty() }
                    val keptTxt = if (kept > 0) getString(R.string.import_kept, kept) else ""
                    runOnUiThread {
                        clearCard()
                        refreshCounts()
                        val dlg = AlertDialog.Builder(this)
                            .setTitle(R.string.import_ok_title)
                            .setMessage(getString(R.string.import_ok, cards.size, eans, missing, keptTxt, info))
                            .setPositiveButton(android.R.string.ok, null)
                        if (missing > 0) dlg.setNeutralButton(R.string.missing_title) { _, _ -> openMissing() }
                        dlg.show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.import_err_title)
                            .setMessage(e.message ?: e.toString())
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }.start()
        }
    }

    private fun openMissing() {
        startActivityForResult(Intent(this, MissingActivity::class.java), REQ_MISSING)
    }

    // ---------- Karta ----------

    private fun sameEan(a: String, b: String) = a == b || a.trimStart('0') == b.trimStart('0')

    private fun setCodeText(code: String) {
        settingText = true
        etCode.setText(code)
        settingText = false
    }

    private fun selectCard(card: Card, askEan: Boolean) {
        selected = card
        settingText = true
        etCard.setText(card.name, false)
        etCard.setSelection(etCard.text.length)
        etCode.setText(card.code)
        settingText = false
        etCard.dismissDropDown()
        showCardInfo()

        val current = etEan.text.toString().trim()
        if (current.isNotEmpty()) {
            if (card.eans.any { sameEan(it, current) }) { focusQty(); return }
            // Predtým naskenovaný neznámy EAN – ponúkneme ho pridať k práve vybranej karte
            if (askEan && db.cardsByEan(current).isEmpty()) { offerAddEan(current, card); return }
        }
        when {
            card.eans.size == 1 -> { etEan.setText(card.eans[0]); focusQty() }
            card.eans.size > 1 && askEan -> chooseEan(card)
            else -> { etEan.setText(""); etEan.requestFocus() }
        }
    }

    private fun chooseEan(card: Card) {
        val items = (card.eans + getString(R.string.other_ean)).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_ean, card.name))
            .setItems(items) { _, which ->
                if (which < card.eans.size) {
                    etEan.setText(card.eans[which])
                    focusQty()
                } else {
                    etEan.setText("")
                    etEan.requestFocus()
                }
            }
            .show()
    }

    private fun clearCard() {
        selected = null
        addMode = false
        settingText = true
        etCard.setText("", false)
        etCode.setText("")
        settingText = false
        etEan.setText("")
        etQty.setText("")
        showCardInfo()
    }

    private fun showCardInfo() {
        val c = selected
        if (c == null) {
            tvCardInfo.visibility = View.INVISIBLE
            tvStock.text = ""
            return
        }
        tvCardInfo.visibility = View.VISIBLE
        val eans = if (c.eans.isEmpty()) getString(R.string.card_no_ean) else "EAN " + c.eans.joinToString(", ")
        tvCardInfo.text = if (c.unit.isEmpty()) eans else "${c.unit} · $eans"
        tvStock.text = if (c.stock != null) getString(R.string.stock, fmt(c.stock)) else ""
    }

    /** Ručne zadané alebo naskenované číslo skladovej karty. */
    private fun onCardCode(code: String) {
        val card = db.cardByCode(code)
        if (card != null) selectCard(card, askEan = true)
        else {
            selected = null
            setCodeText(code)
            showCardInfo()
            toast(getString(R.string.code_not_found, code))
            etEan.requestFocus()
        }
    }

    // ---------- Sken: EAN, QR, číslo karty ----------

    /** Spracuje čokoľvek zo skenera / NFC / poľa EAN. */
    private fun onScanned(raw: String, fromField: Boolean) {
        val p = ScanParser.parse(raw)
        if (p.code != null && p.ean == null) {
            // Čisté číslo karty (napr. 6-miestny QR / čiarový kód) – do poľa EAN nepatrí
            if (fromField) etEan.setText("")
            onCardCode(p.code)
            return
        }
        if (p.code != null && p.ean != null) {
            // QR kód s číslom karty aj EAN
            val card = db.cardByCode(p.code)
            if (card != null && selected?.code != card.code) {
                etEan.setText("")
                selected = card
                settingText = true
                etCard.setText(card.name, false)
                etCode.setText(card.code)
                settingText = false
                showCardInfo()
            } else if (card == null && selected == null) {
                setCodeText(p.code)
            }
        }
        onEan(p.ean ?: return, fromField)
    }

    private fun offerAddEan(ean: String, card: Card) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ean_new_title)
            .setMessage(getString(R.string.ean_new_msg, ean, card.name))
            .setPositiveButton(R.string.ean_add) { _, _ -> addEanTo(ean, card, showDialog = false) }
            .setNegativeButton(R.string.ean_only_now) { _, _ -> focusQty() }
            .show()
    }

    private fun addEanTo(ean: String, card: Card, showDialog: Boolean) {
        db.addEanToCard(ean, card.code)
        db.cardByCode(card.code)?.let { selected = it; showCardInfo() }
        etEan.setText(ean)
        refreshCounts()
        if (showDialog) {
            AlertDialog.Builder(this)
                .setTitle(R.string.ean_added_title)
                .setMessage(getString(R.string.ean_added_msg, ean, card.name))
                .setPositiveButton(R.string.ean_added_next) { _, _ -> clearCard(); openMissing() }
                .setNegativeButton(R.string.ean_added_count) { _, _ -> addMode = false; focusQty() }
                .show()
        } else {
            toast(getString(R.string.ean_added_msg, ean, card.name))
            focusQty()
        }
    }

    private fun onEan(raw: String, fromField: Boolean) {
        val ean = raw.trim()
        if (ean.isEmpty()) return
        if (!fromField || etEan.text.toString().trim() != ean) etEan.setText(ean)
        val sel = selected
        val matches = db.cardsByEan(ean)

        if (sel != null && matches.any { it.code == sel.code }) {
            focusQty(); return
        }
        when {
            matches.size == 1 -> {
                selectCard(matches[0], askEan = false)
                etEan.setText(ean)
                focusQty()
            }
            matches.size > 1 -> {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ean_multiple, ean))
                    .setItems(matches.map { "${it.name} (${it.code})" }.toTypedArray()) { _, w ->
                        selectCard(matches[w], askEan = false)
                        etEan.setText(ean)
                        focusQty()
                    }
                    .show()
            }
            sel != null && addMode -> addEanTo(ean, sel, showDialog = true)
            sel != null -> offerAddEan(ean, sel)
            else -> {
                toast(getString(R.string.ean_not_found, ean))
                etCard.requestFocus()
                showKeyboard(etCard)
            }
        }
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX
            )
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val v = barcode.rawValue ?: barcode.displayValue
                if (!v.isNullOrBlank()) onScanned(v, fromField = false)
            }
            .addOnFailureListener { e ->
                toast(getString(R.string.scan_error, e.message ?: ""))
            }
    }

    private fun onNfcTag(tag: Tag) {
        val text = readNfc(tag)
        runOnUiThread {
            if (text.isNullOrBlank()) toast(getString(R.string.nfc_empty))
            else onScanned(text, fromField = false)
        }
    }

    private fun readNfc(tag: Tag): String? {
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                val msg = ndef.cachedNdefMessage ?: run {
                    ndef.connect()
                    try { ndef.ndefMessage } finally { try { ndef.close() } catch (_: Exception) {} }
                }
                msg?.records?.forEach { r -> parseNdefRecord(r)?.let { if (it.isNotBlank()) return it.trim() } }
            }
        } catch (_: Exception) {
        }
        // Štítok bez textu – použijeme jeho sériové číslo
        return tag.id?.joinToString("") { "%02X".format(it) }
    }

    private fun parseNdefRecord(r: NdefRecord): String? {
        val p = r.payload ?: return null
        if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) && p.isNotEmpty()) {
            val status = p[0].toInt()
            val utf16 = (status and 0x80) != 0
            val langLen = status and 0x3F
            return String(p, 1 + langLen, p.size - 1 - langLen, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
        }
        if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI)) {
            return r.toUri()?.toString()?.substringAfterLast('/')?.substringAfterLast('=')
        }
        if (r.tnf == NdefRecord.TNF_MIME_MEDIA || r.tnf == NdefRecord.TNF_EXTERNAL_TYPE) {
            return String(p, Charset.forName("UTF-8"))
        }
        return null
    }

    // ---------- Množstvo a pridanie ----------

    private fun stepQty(delta: Double) {
        val v = (CardImporter.parseNumber(etQty.text.toString()) ?: 0.0) + delta
        etQty.setText(fmt(if (v < 0) 0.0 else v))
        etQty.setSelection(etQty.text.length)
    }

    private fun addRecord() {
        val card = selected
        val code = card?.code ?: etCode.text.toString().trim()
        var name = card?.name ?: etCard.text.toString().trim()
        if (name.isEmpty() && code.isNotEmpty()) name = "Karta $code"
        if (name.isEmpty()) {
            etCard.error = getString(R.string.err_card)
            etCard.requestFocus()
            return
        }
        val qty = CardImporter.parseNumber(etQty.text.toString())
        if (qty == null) {
            etQty.error = getString(R.string.err_qty)
            etQty.requestFocus()
            return
        }
        val ean = etEan.text.toString().trim()

        val existing = db.records().firstOrNull { it.code == code && it.name == name && it.ean == ean }
        if (existing != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dup_title)
                .setMessage(getString(R.string.dup_msg, name, ean.ifEmpty { "–" }, fmt(existing.qty)))
                .setPositiveButton(getString(R.string.dup_sum, fmt(existing.qty + qty))) { _, _ ->
                    db.updateQty(existing.id, existing.qty + qty)
                    afterAdd(name, existing.qty + qty)
                }
                .setNeutralButton(R.string.dup_new_row) { _, _ ->
                    db.addRecord(code, name, ean, qty, card?.stock)
                    afterAdd(name, qty)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        db.addRecord(code, name, ean, qty, card?.stock)
        afterAdd(name, qty)
    }

    private fun afterAdd(name: String, qty: Double) {
        toast(getString(R.string.added, name, fmt(qty)))
        clearCard()
        refreshCounts()
        etCard.requestFocus()
    }

    // ---------- Pomocné ----------

    private fun focusQty() {
        etQty.requestFocus()
        etQty.selectAll()
        showKeyboard(etQty)
    }

    private fun showKeyboard(v: View) {
        v.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** Návrhy kariet pri písaní názvu (hľadá aj bez diakritiky a v kóde). */
class CardAdapter(private val context: Context, private val db: Db) : BaseAdapter(), Filterable {
    private var items: List<Card> = emptyList()

    override fun getCount() = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: android.view.LayoutInflater.from(context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        val c = items[position]
        v.findViewById<TextView>(android.R.id.text1).apply {
            text = c.name
            setTextColor(context.getColor(R.color.text))
        }
        v.findViewById<TextView>(android.R.id.text2).apply {
            text = "Kód ${c.code}" + (if (c.unit.isNotEmpty()) " · ${c.unit}" else "") +
                (if (c.stock != null) " · stav ${MainActivity.fmt(c.stock)}" else "") +
                (if (c.eans.isNotEmpty()) " · EAN: ${c.eans.size}" else " · bez EAN")
            setTextColor(context.getColor(R.color.text_secondary))
        }
        return v
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val list = if (constraint.isNullOrBlank()) emptyList() else db.searchCards(constraint.toString())
            return FilterResults().apply { values = list; count = list.size }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            items = (results?.values as? List<Card>) ?: emptyList()
            if (items.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? Card)?.name ?: ""
    }
}
