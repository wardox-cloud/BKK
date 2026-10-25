package app.murmur

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.murmur.core.MeetingStore
import app.murmur.core.MeetingStore.Status
import app.murmur.core.SttProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The home screen: record a meeting, find past ones, and get to history and settings. */
class MainActivity : Activity() {
    private lateinit var store: MeetingStore
    private lateinit var prefs: Prefs
    private lateinit var banner: TextView
    private lateinit var recordTitle: TextView
    private lateinit var recordHint: TextView
    private lateinit var record: Button
    private lateinit var pause: Button
    private lateinit var stop: Button
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private val adapter = MeetingAdapter()
    private var recordWhenAllowed = false
    private var shownQuery: String? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Meetings.store(this)
        prefs = Prefs(this)
        banner = findViewById(R.id.setup_banner)
        recordTitle = findViewById(R.id.record_title)
        recordHint = findViewById(R.id.record_hint)
        record = findViewById(R.id.record)
        pause = findViewById(R.id.pause)
        stop = findViewById(R.id.stop)
        search = findViewById(R.id.search)
        empty = findViewById(R.id.empty)

        val list = findViewById<ListView>(R.id.meetings)
        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, position, _ -> startActivity(MeetingActivity.intent(this, adapter.getItem(position).id)) }

        banner.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        record.setOnClickListener { startMeeting() }
        pause.setOnClickListener { MeetingService.pause(this, paused = Meetings.recording?.paused != true) }
        stop.setOnClickListener { MeetingService.stop(this) }
        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        findViewById<View>(R.id.history).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) = refreshList()
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        if (intent.getBooleanExtra(EXTRA_RECORD, false)) startMeeting()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RECORD, false)) startMeeting()
    }

    override fun onStart() {
        super.onStart()
        Meetings.listen(listener)
        Meetings.recover(this)
        shownQuery = null
        render()
    }

    override fun onStop() {
        Meetings.unlisten(listener)
        super.onStop()
    }

    private fun startMeeting() {
        if (Meetings.recording != null) return
        if (prefs.key(SttProvider.GEMINI).isBlank()) {
            Toast.makeText(this, R.string.meeting_needs_key, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Manifest.permission.RECORD_AUDIO in missing) {
            recordWhenAllowed = true
            requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST)
            return
        }
        // Notifications are nice to have (they show the timer and the Stop button); don't wait for them.
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST)
        MeetingService.record(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val micAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (recordWhenAllowed && micAllowed) MeetingService.record(this)
        recordWhenAllowed = false
        render()
    }

    private fun render() {
        val problem = setupProblem()
        banner.visibility = if (problem != null) View.VISIBLE else View.GONE
        banner.text = problem

        val recording = Meetings.recording
        if (recording == null) {
            recordTitle.setText(R.string.record_title)
            recordHint.setText(R.string.record_hint)
            record.visibility = View.VISIBLE
            pause.visibility = View.GONE
            stop.visibility = View.GONE
        } else {
            recordTitle.text = getString(if (recording.paused) R.string.record_paused else R.string.record_live, Meetings.clock(recording.elapsedMs))
            recordHint.text = store.get(recording.id)?.message ?: getString(R.string.record_live_hint)
            record.visibility = View.GONE
            pause.visibility = View.VISIBLE
            pause.setText(if (recording.paused) R.string.meeting_resume else R.string.meeting_pause)
            stop.visibility = View.VISIBLE
        }
        refreshList()
    }

    /** Searching reads every transcript, so it only runs again when the words change. */
    private fun refreshList() {
        val query = search.text.toString().trim()
        if (query.isNotEmpty() && query == shownQuery) {
            adapter.notifyDataSetChanged()
            return
        }
        shownQuery = query
        adapter.meetings = if (query.isEmpty()) store.list() else store.search(query)
        empty.setText(if (query.isEmpty()) R.string.meetings_empty else R.string.meetings_none_found)
        adapter.notifyDataSetChanged()
    }

    private fun setupProblem(): String? = when {
        prefs.key(SttProvider.GEMINI).isBlank() -> getString(R.string.banner_key)
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> getString(R.string.banner_mic)
        else -> null
    }

    private inner class MeetingAdapter : BaseAdapter() {
        var meetings: List<MeetingStore.Meeting> = emptyList()

        override fun getCount() = meetings.size

        override fun getItem(position: Int) = meetings[position]

        override fun getItemId(position: Int) = meetings[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_meeting, parent, false)
            val meeting = meetings[position]
            view.findViewById<TextView>(R.id.item_title).text = meeting.title
            view.findViewById<TextView>(R.id.item_subtitle).text = describe(this@MainActivity, meeting)
            return view
        }
    }

    companion object {
        const val EXTRA_RECORD = "record"
        private const val PERMISSIONS_REQUEST = 1

        fun intent(context: Context) = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun open(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 0, intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

        /** "Mon 26 Oct · 45 min · Notes ready" */
        fun describe(context: Context, meeting: MeetingStore.Meeting): String {
            val day = DAY.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(meeting.startedAt))
            val state = when (meeting.status) {
                Status.RECORDING -> context.getString(R.string.state_recording)
                Status.TRANSCRIBING -> if (meeting.durationMs > 0) {
                    context.getString(R.string.state_writing_progress, Meetings.length(meeting.doneMs), Meetings.length(meeting.durationMs))
                } else {
                    context.getString(R.string.state_writing)
                }
                Status.WAITING -> context.getString(R.string.state_waiting)
                Status.DONE -> meeting.message ?: context.getString(R.string.state_done)
            }
            return listOf(day, Meetings.length(meeting.durationMs).takeIf { meeting.durationMs > 0 }, state).filterNotNull().joinToString(" · ")
        }
    }
}
