package sk.skladnik.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
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

        fun fmt(d: Double?): String {
            if (d == null) return "–"
            return if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString()
            else d.toString().replace('.', ',')
        }
    }

    private lateinit var db: Db
    private lateinit var tvDb: TextView
    private lateinit var etCard: AutoCompleteTextView
    private lateinit var tvCardInfo: TextView
    private lateinit var etEan: EditText
    private lateinit var etQty: EditText
    private lateinit var tvStock: TextView
    private lateinit var btnList: Button
    private lateinit var tvRecent: TextView

    private var selected: Card? = null
    private var settingCardText = false
    private var nfc: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db.get(this)

        tvDb = findViewById(R.id.tvDb)
        etCard = findViewById(R.id.etCard)
        tvCardInfo = findViewById(R.id.tvCardInfo)
        etEan = findViewById(R.id.etEan)
        etQty = findViewById(R.id.etQty)
        tvStock = findViewById(R.id.tvStock)
        btnList = findViewById(R.id.btnList)
        tvRecent = findViewById(R.id.tvRecent)

        // Vyhľadávanie karty
        etCard.threshold = 1
        etCard.setAdapter(CardAdapter(this, db))
        etCard.setOnItemClickListener { parent, _, pos, _ ->
            (parent.getItemAtPosition(pos) as? Card)?.let { selectCard(it, askEan = true) }
        }
        etCard.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!settingCardText && selected != null && s.toString() != selected?.name) {
                    selected = null
                    showCardInfo()
                }
            }
        })
        findViewById<Button>(R.id.btnClearCard).setOnClickListener {
            clearCard()
            etCard.requestFocus()
            showKeyboard(etCard)
        }

        // EAN – ručne, skenerom (aj hardvérovým, ktorý posiela Enter) alebo cez NFC
        etEan.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                val e = etEan.text.toString().trim()
                if (e.isNotEmpty()) onEan(e, fromField = true) else etQty.requestFocus()
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
            val recs = db.recordCount()
            val recent = db.records().take(3)
            runOnUiThread {
                tvDb.text = if (cards == 0) getString(R.string.db_empty)
                else getString(R.string.db_info, cards, eans)
                btnList.text = getString(R.string.records_button, recs)
                tvRecent.text = if (recent.isEmpty()) "" else
                    getString(R.string.recent) + "\n" + recent.joinToString("\n") {
                        "• ${it.name} · ${it.ean.ifEmpty { "bez EAN" }} · ${fmt(it.qty)}"
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
                    "text/plain", "application/octet-stream"
                )
            )
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            toast(getString(R.string.no_file_picker))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            toast(getString(R.string.importing))
            Thread {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("Súbor sa nedá otvoriť.")
                    val rows = TableReader.read(bytes, uri.lastPathSegment)
                    val result = CardImporter.import(rows)
                    if (result.cards.isEmpty()) throw IllegalArgumentException("V súbore sa nenašli žiadne skladové karty.")
                    db.replaceCards(result.cards)
                    val eans = result.cards.sumOf { it.eans.size }
                    runOnUiThread {
                        clearCard()
                        refreshCounts()
                        AlertDialog.Builder(this)
                            .setTitle(R.string.import_ok_title)
                            .setMessage(getString(R.string.import_ok, result.cards.size, eans, result.info))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
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

    // ---------- Karta ----------

    private fun selectCard(card: Card, askEan: Boolean) {
        selected = card
        settingCardText = true
        etCard.setText(card.name, false)
        etCard.setSelection(etCard.text.length)
        settingCardText = false
        etCard.dismissDropDown()
        showCardInfo()

        val current = etEan.text.toString().trim()
        if (current.isNotEmpty() && card.eans.contains(current)) {
            focusQty()
            return
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
        settingCardText = true
        etCard.setText("", false)
        settingCardText = false
        etEan.setText("")
        etQty.setText("")
        showCardInfo()
    }

    private fun showCardInfo() {
        val c = selected
        if (c == null) {
            tvCardInfo.visibility = View.GONE
            tvStock.text = getString(R.string.stock, "–")
            return
        }
        tvCardInfo.visibility = View.VISIBLE
        val eans = if (c.eans.isEmpty()) "žiadny" else c.eans.joinToString(", ")
        tvCardInfo.text = getString(R.string.card_info, c.code, eans)
        tvStock.text = getString(R.string.stock, fmt(c.stock))
    }

    // ---------- EAN ----------

    private fun onEan(raw: String, fromField: Boolean) {
        val ean = raw.trim()
        if (ean.isEmpty()) return
        if (!fromField) etEan.setText(ean)
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
            sel != null -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.ean_new_title)
                    .setMessage(getString(R.string.ean_new_msg, ean, sel.name))
                    .setPositiveButton(R.string.ean_add) { _, _ ->
                        db.addEanToCard(ean, sel.code)
                        db.cardByCode(sel.code)?.let { selected = it; showCardInfo() }
                        focusQty()
                    }
                    .setNegativeButton(R.string.ean_only_now) { _, _ -> focusQty() }
                    .show()
            }
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
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val v = barcode.rawValue
                if (!v.isNullOrBlank()) onEan(v, fromField = false)
            }
            .addOnFailureListener { e ->
                toast(getString(R.string.scan_error, e.message ?: ""))
            }
    }

    private fun onNfcTag(tag: Tag) {
        val text = readNfc(tag)
        runOnUiThread {
            if (text.isNullOrBlank()) toast(getString(R.string.nfc_empty))
            else onEan(text, fromField = false)
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
        val name = card?.name ?: etCard.text.toString().trim()
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
        val code = card?.code ?: ""

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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
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
        v.findViewById<TextView>(android.R.id.text1).text = c.name
        v.findViewById<TextView>(android.R.id.text2).text =
            "Kód: ${c.code} · stav: ${MainActivity.fmt(c.stock)}" +
                if (c.eans.isNotEmpty()) " · EAN: ${c.eans.size}" else ""
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
