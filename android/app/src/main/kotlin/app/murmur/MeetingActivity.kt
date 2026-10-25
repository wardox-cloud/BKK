package app.murmur

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.murmur.core.MeetingStore
import app.murmur.core.MeetingStore.Status
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One meeting: its notes and transcript, the recording, and what you can do with them. */
class MeetingActivity : Activity() {
    private lateinit var store: MeetingStore
    private lateinit var id: String
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var resume: Button
    private lateinit var play: Button
    private lateinit var notesHeading: View
    private lateinit var notes: TextView
    private lateinit var transcriptHeading: View
    private lateinit var transcript: TextView
    private var player: MediaPlayer? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meeting)
        store = Meetings.store(this)
        id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        title = findViewById(R.id.meeting_title)
        meta = findViewById(R.id.meeting_meta)
        status = findViewById(R.id.meeting_status)
        progress = findViewById(R.id.meeting_progress)
        resume = findViewById(R.id.meeting_resume)
        play = findViewById(R.id.meeting_play)
        notesHeading = findViewById(R.id.notes_heading)
        notes = findViewById(R.id.meeting_notes)
        transcriptHeading = findViewById(R.id.transcript_heading)
        transcript = findViewById(R.id.meeting_transcript)

        title.setOnClickListener { rename() }
        resume.setOnClickListener { MeetingService.process(this, id) }
        play.setOnClickListener { togglePlayback() }
        findViewById<View>(R.id.meeting_copy).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Murmur", everything()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.meeting_share).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, store.get(id)?.title)
                .putExtra(Intent.EXTRA_TEXT, everything())
            startActivity(Intent.createChooser(send, null))
        }
        findViewById<View>(R.id.meeting_claude).setOnClickListener { openInClaude() }
        findViewById<View>(R.id.meeting_rename).setOnClickListener { rename() }
        findViewById<View>(R.id.meeting_delete).setOnClickListener { delete() }
    }

    override fun onStart() {
        super.onStart()
        Meetings.listen(listener)
        render()
    }

    override fun onStop() {
        Meetings.unlisten(listener)
        player?.release()
        player = null
        if (::play.isInitialized) play.setText(R.string.meeting_play)
        super.onStop()
    }

    private fun render() {
        val meeting = store.get(id) ?: return finish()
        title.text = meeting.title
        meta.text = listOf(
            WHEN.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(meeting.startedAt)),
            Meetings.length(meeting.durationMs).takeIf { meeting.durationMs > 0 },
        ).filterNotNull().joinToString(" · ")

        val live = Meetings.recording?.id == id
        status.text = when (meeting.status) {
            Status.RECORDING -> if (live) getString(R.string.status_recording) else getString(R.string.status_interrupted)
            Status.TRANSCRIBING -> if (meeting.durationMs > 0) {
                getString(R.string.status_writing_progress, Meetings.clock(meeting.doneMs), Meetings.clock(meeting.durationMs))
            } else {
                getString(R.string.status_writing)
            }
            Status.WAITING -> meeting.message ?: getString(R.string.status_interrupted)
            Status.DONE -> meeting.message.orEmpty()
        }
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        progress.visibility = if (meeting.status == Status.TRANSCRIBING || live) View.VISIBLE else View.GONE
        if (meeting.status == Status.TRANSCRIBING && meeting.durationMs > 0) {
            progress.isIndeterminate = false
            progress.max = (meeting.durationMs / 1000).toInt()
            progress.progress = (meeting.doneMs / 1000).toInt()
        } else {
            progress.isIndeterminate = true
        }
        resume.visibility = if (meeting.status == Status.WAITING) View.VISIBLE else View.GONE
        play.visibility = if (!live && audioFile(meeting)?.exists() == true) View.VISIBLE else View.GONE

        val notesText = store.notes(id)
        notesHeading.visibility = if (notesText != null) View.VISIBLE else View.GONE
        notes.visibility = notesHeading.visibility
        notes.text = notesText?.let { Markdown.render(it) }
        val transcriptText = store.transcript(id)
        transcriptHeading.visibility = if (transcriptText.isNotEmpty()) View.VISIBLE else View.GONE
        transcript.visibility = transcriptHeading.visibility
        transcript.text = transcriptText
    }

    private fun audioFile(meeting: MeetingStore.Meeting) = meeting.audio?.let { File(store.folder(meeting.id), it) }

    private fun togglePlayback() {
        player?.let {
            if (it.isPlaying) it.pause() else it.start()
            play.setText(if (it.isPlaying) R.string.meeting_pause_audio else R.string.meeting_play)
            return
        }
        val file = store.get(id)?.let { audioFile(it) } ?: return
        try {
            player = MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener {
                    release()
                    player = null
                    play.setText(R.string.meeting_play)
                }
                prepare()
                start()
            }
            play.setText(R.string.meeting_pause_audio)
        } catch (e: Exception) {
            player = null
            Toast.makeText(this, getString(R.string.meeting_cant_play, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun everything(): String {
        val meeting = store.get(id) ?: return ""
        return listOfNotNull(
            meeting.title,
            store.notes(id),
            store.transcript(id).takeIf { it.isNotEmpty() }?.let { getString(R.string.transcript) + "\n\n" + it },
        ).joinToString("\n\n")
    }

    /** Hands the transcript to the Claude app, where your subscription covers it. */
    private fun openInClaude() {
        val text = getString(R.string.claude_prompt) + "\n\n" + store.transcript(id)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent(send).setPackage(CLAUDE_APP))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent.createChooser(send, null))
        }
    }

    private fun rename() {
        val current = store.get(id)?.title
        val field = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.meeting_rename)
            .setView(field)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) store.update(id) { it.copy(title = name, renamed = true) }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun delete() {
        if (Meetings.recording?.id == id) {
            Toast.makeText(this, R.string.meeting_stop_first, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.meeting_delete_title)
            .setMessage(R.string.meeting_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                player?.release()
                player = null
                store.delete(id)
                Meetings.changed()
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val CLAUDE_APP = "com.anthropic.claude"
        private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH)

        fun intent(context: Context, id: String) = Intent(context, MeetingActivity::class.java).putExtra(EXTRA_ID, id)

        fun open(context: Context, id: String): PendingIntent = PendingIntent.getActivity(
            context,
            id.hashCode(),
            intent(context, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
