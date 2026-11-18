package sk.skladnik.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zoznam zapísaných záznamov, úpravy a export (Excel / XML). */
class RecordsActivity : Activity() {

    companion object {
        private const val REQ_INV_XLSX = 2
        private const val REQ_INV_XML = 4
        private const val REQ_BG_XML = 5
        private const val REQ_CARDS_XLSX = 6

        private const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val MIME_XML = "text/xml"
    }

    private lateinit var db: Db
    private lateinit var list: ListView
    private lateinit var tvSummary: TextView
    private var records: List<Record> = emptyList()
    private val timeFmt = SimpleDateFormat("d.M. HH:mm", Locale("sk"))

    private val adapter = object : BaseAdapter() {
        override fun getCount() = records.size
        override fun getItem(position: Int): Any = records[position]
        override fun getItemId(position: Int) = records[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_record, parent, false)
            val r = records[position]
            v.findViewById<TextView>(R.id.tvName).text = r.name
            v.findViewById<TextView>(R.id.tvQty).text = MainActivity.fmt(r.qty)
            val stock = if (r.stock != null) " · stav ${MainActivity.fmt(r.stock)}" else ""
            v.findViewById<TextView>(R.id.tvDetail).text =
                "${if (r.code.isNotEmpty()) "Kód ${r.code} · " else ""}EAN ${r.ean.ifEmpty { "–" }}$stock · ${timeFmt.format(Date(r.time))}"
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_records)
        db = Db.get(this)
        findViewById<TextView>(R.id.tvTitle).setText(R.string.records_title)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        list = findViewById(R.id.list)
        tvSummary = findViewById(R.id.tvSummary)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.tvEmpty)

        list.setOnItemClickListener { _, _, pos, _ -> editQty(records[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(records[pos]); true }

        findViewById<Button>(R.id.btnExport).setOnClickListener { chooseExport() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
        reload()
    }

    private fun reload() {
        records = db.records()
        adapter.notifyDataSetChanged()
        val total = records.sumOf { it.qty }
        tvSummary.text = getString(R.string.summary, records.size, MainActivity.fmt(total))
    }

    private fun editQty(r: Record) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(MainActivity.fmt(r.qty))
            selectAll()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setMessage(getString(R.string.edit_qty_msg, r.ean.ifEmpty { "–" }))
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val q = CardImporter.parseNumber(input.text.toString())
                if (q != null) { db.updateQty(r.id, q); reload() }
            }
            .setNeutralButton(R.string.delete) { _, _ -> confirmDelete(r) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun confirmDelete(r: Record) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage("${r.name}\nEAN ${r.ean.ifEmpty { "–" }} · ${MainActivity.fmt(r.qty)}")
            .setPositiveButton(R.string.delete) { _, _ -> db.deleteRecord(r.id); reload() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        if (records.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(getString(R.string.clear_msg, records.size))
            .setPositiveButton(R.string.clear_yes) { _, _ -> db.clearRecords(); reload() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Export ----------

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())

    private fun chooseExport() {
        val newEans = db.newEanCount()
        val items = arrayOf(
            getString(R.string.exp_inv_xlsx),
            getString(R.string.exp_inv_xml),
            getString(R.string.exp_bg_xml, newEans),
            getString(R.string.exp_cards_xlsx)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.export_choose)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (records.isEmpty()) toast(getString(R.string.nothing_to_export))
                         else create(REQ_INV_XLSX, MIME_XLSX, "inventura_${stamp()}.xlsx")
                    1 -> if (records.isEmpty()) toast(getString(R.string.nothing_to_export))
                         else create(REQ_INV_XML, MIME_XML, "inventura_${stamp()}.xml")
                    2 -> when {
                        !db.hasSourceXml() -> info(getString(R.string.exp_bg_no_xml))
                        newEans == 0 -> info(getString(R.string.exp_bg_none))
                        else -> {
                            val base = (db.sourceName ?: "ExportTovarJedlo").substringBeforeLast('.')
                            create(REQ_BG_XML, MIME_XML, "${base}_s_EAN_${stamp()}.xml")
                        }
                    }
                    3 -> if (db.cardCount() == 0) info(getString(R.string.db_empty))
                         else create(REQ_CARDS_XLSX, MIME_XLSX, "karty_EAN_${stamp()}.xlsx")
                }
            }
            .show()
    }

    private fun create(req: Int, mime: String, name: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(intent, req)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val mime = if (requestCode == REQ_INV_XLSX || requestCode == REQ_CARDS_XLSX) MIME_XLSX else MIME_XML
        Thread {
            try {
                val message: String = when (requestCode) {
                    REQ_INV_XLSX -> {
                        write(uri) { XlsxWriter.write(it, records) }
                        getString(R.string.export_ok, records.size)
                    }
                    REQ_INV_XML -> {
                        write(uri) { InventoryXmlWriter.write(it, records) }
                        getString(R.string.export_ok, records.size)
                    }
                    REQ_BG_XML -> {
                        val original = db.sourceXml() ?: throw IllegalStateException(getString(R.string.exp_bg_no_xml))
                        val add = db.cardsWithNewEan().associate { it.code to it.newEans }
                        val (bytes, changed) = BlueGastroXml.patch(original, add)
                        write(uri) { it.write(bytes) }
                        getString(R.string.export_bg_ok, changed)
                    }
                    REQ_CARDS_XLSX -> {
                        val cards = db.cards()
                        write(uri) { XlsxWriter.writeCards(it, cards) }
                        getString(R.string.export_cards_ok, cards.size)
                    }
                    else -> return@Thread
                }
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.export_ok_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.share) { _, _ -> share(uri, mime) }
                        .setNeutralButton(R.string.open) { _, _ -> open(uri, mime) }
                        .setNegativeButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.export_err_title)
                        .setMessage(e.message ?: e.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    private fun write(uri: Uri, block: (java.io.OutputStream) -> Unit) {
        contentResolver.openOutputStream(uri, "wt")?.use(block)
            ?: throw IllegalStateException("Súbor sa nedá zapísať.")
    }

    private fun info(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun share(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, getString(R.string.share))) } catch (_: Exception) {}
    }

    private fun open(uri: Uri, mime: String) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_excel_app, Toast.LENGTH_LONG).show()
        }
    }
}
