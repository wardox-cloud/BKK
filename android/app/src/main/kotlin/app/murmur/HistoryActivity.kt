package app.murmur

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.murmur.core.HistoryStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything you've dictated, newest first: tap to copy it again. */
class HistoryActivity : Activity() {
    private lateinit var history: HistoryStore
    private lateinit var search: EditText
    private val adapter = EntryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        history = Meetings.history(this)
        search = findViewById(R.id.history_search)
        val list = findViewById<ListView>(R.id.history_list)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.history_empty)
        list.setOnItemClickListener { _, _, position, _ ->
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Murmur", adapter.getItem(position).text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position)
            AlertDialog.Builder(this)
                .setMessage(R.string.history_delete_one)
                .setPositiveButton(R.string.delete) { _, _ ->
                    history.remove(entry)
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        findViewById<View>(R.id.history_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.history_clear_all)
                .setPositiveButton(R.string.delete) { _, _ ->
                    history.clear()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) = refresh()
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.entries = history.entries(search.text.toString())
        adapter.notifyDataSetChanged()
    }

    private inner class EntryAdapter : BaseAdapter() {
        var entries: List<HistoryStore.Entry> = emptyList()

        override fun getCount() = entries.size

        override fun getItem(position: Int) = entries[position]

        override fun getItemId(position: Int) = entries[position].time

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_meeting, parent, false)
            val entry = entries[position]
            view.findViewById<TextView>(R.id.item_title).apply {
                text = entry.text
                maxLines = 4
            }
            view.findViewById<TextView>(R.id.item_subtitle).text = listOfNotNull(
                WHEN.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.time)),
                entry.app?.let { appName(this@HistoryActivity, it) },
            ).joinToString(" · ")
            return view
        }
    }

    companion object {
        private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)

        /** "WhatsApp" for com.whatsapp, or a tidy guess when the phone won't say. */
        fun appName(context: Context, app: String): String = try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(app, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            app.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }
}
