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
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zoznam zapísaných záznamov, úpravy a export do Excelu. */
class RecordsActivity : Activity() {

    companion object {
        private const val REQ_EXPORT = 2
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
        actionBar?.setDisplayHomeAsUpEnabled(true)
        db = Db.get(this)
        list = findViewById(R.id.list)
        tvSummary = findViewById(R.id.tvSummary)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.tvEmpty)

        list.setOnItemClickListener { _, _, pos, _ -> editQty(records[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(records[pos]); true }

        findViewById<Button>(R.id.btnExport).setOnClickListener { startExport() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
        reload()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
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

    private fun startExport() {
        if (records.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        val name = "inventura_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".xlsx"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(intent, REQ_EXPORT)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { XlsxWriter.write(it, records) }
                ?: throw IllegalStateException("Súbor sa nedá zapísať.")
            AlertDialog.Builder(this)
                .setTitle(R.string.export_ok_title)
                .setMessage(getString(R.string.export_ok, records.size))
                .setPositiveButton(R.string.share) { _, _ -> share(uri) }
                .setNeutralButton(R.string.open) { _, _ -> open(uri) }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle(R.string.export_err_title)
                .setMessage(e.message ?: e.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private val xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = xlsxMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, getString(R.string.share))) } catch (_: Exception) {}
    }

    private fun open(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, xlsxMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_excel_app, Toast.LENGTH_LONG).show()
        }
    }
}
