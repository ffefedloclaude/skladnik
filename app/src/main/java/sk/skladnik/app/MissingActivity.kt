package sk.skladnik.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView

/** Zoznam skladových kariet, ktoré ešte nemajú EAN. Ťuknutím sa karta vyberie a spustí sa skener. */
class MissingActivity : Activity() {

    companion object {
        const val EXTRA_CODE = "code"
    }

    private lateinit var db: Db
    private var all: List<Card> = emptyList()
    private var shown: List<Card> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_record, parent, false)
            val c = shown[position]
            v.findViewById<TextView>(R.id.tvName).text = c.name
            v.findViewById<TextView>(R.id.tvDetail).text =
                "Kód ${c.code}" + if (c.unit.isNotEmpty()) " · ${c.unit}" else ""
            v.findViewById<TextView>(R.id.tvQty).apply {
                text = getString(R.string.scan)
                textSize = 14f
            }
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_missing)
        db = Db.get(this)
        findViewById<TextView>(R.id.tvTitle).setText(R.string.missing_title)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, shown[pos].code))
            finish()
        }
        findViewById<EditText>(R.id.etFilter).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filter(s?.toString() ?: "") }
        })

        all = db.cardsWithoutEan()
        val withNew = db.cardsWithNewEan()
        findViewById<TextView>(R.id.tvSummary).text =
            if (all.isEmpty()) getString(R.string.missing_none) else getString(R.string.missing_summary, all.size)
        findViewById<TextView>(R.id.tvNew).apply {
            val n = withNew.sumOf { it.newEans.size }
            visibility = if (n > 0) View.VISIBLE else View.GONE
            text = getString(R.string.missing_new, n, withNew.size)
        }
        filter("")
    }

    private fun filter(q: String) {
        shown = if (q.isBlank()) all else db.searchCards(q, limit = 500, source = all)
        adapter.notifyDataSetChanged()
    }
}
