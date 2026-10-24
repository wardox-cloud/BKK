package app.murmur

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.murmur.core.Dictation
import app.murmur.core.DictationException
import app.murmur.core.Prompts
import app.murmur.core.Wav
import java.util.concurrent.Executors

/**
 * The keyboard: a big mic button. Tap to start and tap to finish, or hold it
 * while you talk. The cleaned-up text is typed into whatever app is open.
 */
class MurmurKeyboard : InputMethodService() {
    private enum class State { IDLE, RECORDING, WORKING }

    private lateinit var prefs: Prefs
    private val prompts by lazy { Prompts.load() }
    private var dictationInstance: Dictation? = null

    /** Settable so tests can point it at a fake server. */
    internal var dictation: Dictation
        get() = dictationInstance ?: Dictation(prompts).also { dictationInstance = it }
        set(value) {
            dictationInstance = value
        }
    private val recorder = PcmRecorder()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var state = State.IDLE
    private var pressedAt = 0L
    private var pressStartedRecording = false
    private var recordingStartedAt = 0L

    /** Kept after a failed attempt so it can be retried without talking again. */
    private var failedAudio: ByteArray? = null

    /** The last dictation typed in, so a style tap can rewrite it in place. */
    private var last: Dictation.Result? = null
    private var lastInserted: String? = null

    private lateinit var status: TextView
    private lateinit var styles: LinearLayout
    private lateinit var mic: View
    private lateinit var micIcon: ImageView
    private lateinit var spinner: ProgressBar
    private lateinit var level: View

    private val tick = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            val elapsed = SystemClock.elapsedRealtime() - recordingStartedAt
            val seconds = elapsed / 1000
            setStatus("Listening… %d:%02d".format(seconds / 60, seconds % 60))
            if (elapsed >= MAX_RECORDING_MS) finishRecording() else main.postDelayed(this, 250)
        }
    }

    private val repeatDelete = object : Runnable {
        override fun run() {
            deleteOne()
            main.postDelayed(this, 50)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard, null)
        status = view.findViewById(R.id.status)
        styles = view.findViewById(R.id.styles)
        mic = view.findViewById(R.id.mic)
        micIcon = view.findViewById(R.id.mic_icon)
        spinner = view.findViewById(R.id.spinner)
        level = view.findViewById(R.id.level)

        mic.setOnTouchListener { button, event ->
            onMicTouch(button, event)
            true
        }
        // Screen readers activate the button with a click rather than a touch.
        mic.setOnClickListener { if (state == State.IDLE) startRecording() else if (state == State.RECORDING) finishRecording() }
        status.setOnClickListener { onStatusTap() }

        view.findViewById<View>(R.id.switch_keyboard).apply {
            setOnClickListener { switchToOtherKeyboard() }
            setOnLongClickListener {
                inputMethodManager().showInputMethodPicker()
                true
            }
        }
        view.findViewById<View>(R.id.undo).setOnClickListener { undoLast() }
        view.findViewById<View>(R.id.enter).setOnClickListener { pressEnter() }
        view.findViewById<View>(R.id.backspace).setOnTouchListener { key, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    key.isPressed = true
                    key.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    deleteOne()
                    main.postDelayed(repeatDelete, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    key.isPressed = false
                    main.removeCallbacks(repeatDelete)
                }
            }
            true
        }
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        renderStyles()
        render()
        if (state == State.IDLE) showReadiness()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (state == State.RECORDING) cancelRecording()
        main.removeCallbacks(repeatDelete)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        recorder.stop()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------- Dictation ----------

    private fun onMicTouch(button: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                button.isPressed = true
                button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                pressedAt = event.eventTime
                pressStartedRecording = state == State.IDLE
                if (pressStartedRecording) startRecording()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                button.isPressed = false
                if (state != State.RECORDING) return
                val held = event.eventTime - pressedAt >= HOLD_MS
                // A quick tap that started recording keeps listening until the next tap.
                if (!pressStartedRecording || held) finishRecording()
            }
        }
    }

    private fun setupProblem(): String? = when {
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            "Murmur needs the microphone. Tap here to allow it."
        prefs.key(prefs.provider).isBlank() -> "Add your API keys first. Tap here to open Murmur."
        else -> null
    }

    private fun showReadiness() {
        val problem = setupProblem()
        setStatus(problem ?: "Tap the mic and talk, or hold it while you speak.", error = problem != null)
    }

    private fun onStatusTap() {
        when {
            setupProblem() != null -> startActivity(
                Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            state == State.IDLE && failedAudio != null -> failedAudio?.let { process(it) }
        }
    }

    private fun startRecording() {
        setupProblem()?.let {
            setStatus(it, error = true)
            return
        }
        try {
            recorder.start { value -> main.post { showLevel(value) } }
        } catch (e: Exception) {
            setStatus("Couldn't start the microphone: ${e.message}", error = true)
            return
        }
        state = State.RECORDING
        recordingStartedAt = SystemClock.elapsedRealtime()
        render()
        main.post(tick)
    }

    private fun finishRecording() {
        main.removeCallbacks(tick)
        val pcm = recorder.stop()
        showLevel(0f)
        if (pcm.size < Wav.SAMPLE_RATE) { // under half a second
            state = State.IDLE
            render()
            setStatus("That was too short. Tap the mic, talk, then tap it again.", error = true)
            return
        }
        process(Wav.encode(pcm))
    }

    private fun cancelRecording() {
        main.removeCallbacks(tick)
        recorder.stop()
        showLevel(0f)
        state = State.IDLE
        render()
    }

    internal fun process(wav: ByteArray) {
        state = State.WORKING
        render()
        val settings = prefs.dictationSettings()
        val target = currentField()
        worker.execute {
            try {
                val result = dictation.run(wav, settings) { stage -> main.post { setStatus(describe(stage)) } }
                main.post {
                    failedAudio = null
                    if (currentField() != target || currentInputConnection == null) {
                        // You moved to another text box while it was working; don't type into the wrong one.
                        copyToClipboard(result.text)
                        state = State.IDLE
                        render()
                        setStatus("The text box changed, so it's copied instead. Long-press to paste.")
                        return@post
                    }
                    insert(result.text)
                    last = result
                    finish(result.note)
                }
            } catch (e: Exception) {
                val message = (e as? DictationException)?.message ?: "Something went wrong (${e.message})."
                main.post {
                    failedAudio = wav
                    state = State.IDLE
                    render()
                    setStatus("$message Tap here to try again.", error = true)
                }
            }
        }
    }

    private fun finish(note: String?) {
        state = State.IDLE
        render()
        setStatus(note ?: "Done. Tap a style to rewrite it.", error = note != null)
        if (prefs.switchBack && note == null) main.postDelayed({ if (state == State.IDLE) switchToOtherKeyboard() }, 600)
    }

    private fun describe(stage: Dictation.Stage) = when (stage) {
        Dictation.Stage.TRANSCRIBING -> "Transcribing…"
        Dictation.Stage.POLISHING -> "Polishing…"
        Dictation.Stage.TRANSCRIBING_AND_POLISHING -> "Transcribing and polishing…"
    }

    // ---------- Styles ----------

    private fun renderStyles() {
        if (!::styles.isInitialized) return
        styles.removeAllViews()
        for ((id, label) in prompts.styles) {
            val selected = id == prefs.style
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setTextColor(getColor(if (selected) R.color.on_accent else R.color.text))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    if (selected) setColor(getColor(R.color.accent)) else {
                        setColor(getColor(R.color.surface))
                        setStroke(dp(1), getColor(R.color.border))
                    }
                }
                setOnClickListener { onStyle(id) }
            }
            styles.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    /** Picks the style for the next dictation, and rewrites the last one in place if it's still there. */
    private fun onStyle(id: String) {
        prefs.style = id
        renderStyles()
        val previous = last ?: return
        val inserted = lastInserted ?: return
        if (state != State.IDLE || !cursorFollows(inserted)) return

        state = State.WORKING
        render()
        val settings = prefs.dictationSettings()
        worker.execute {
            val result = dictation.polish(previous.raw, settings) { stage -> main.post { setStatus(describe(stage)) } }
            main.post {
                if (cursorFollows(inserted)) {
                    currentInputConnection?.deleteSurroundingText(inserted.length, 0)
                    insert(result.text)
                }
                last = result
                finish(result.note)
            }
        }
    }

    // ---------- Editing ----------

    private fun insert(text: String) {
        val input = currentInputConnection ?: return
        val before = input.getTextBeforeCursor(1, 0)
        val needsSpace = !before.isNullOrEmpty() && !before.last().isWhitespace() && text.firstOrNull()?.isWhitespace() == false
        val output = if (needsSpace) " $text" else text
        input.commitText(output, 1)
        lastInserted = output
    }

    private fun currentField() = currentInputEditorInfo?.let { it.packageName to it.fieldId }

    private fun copyToClipboard(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Murmur", text))
        lastInserted = null
        last = null
    }

    private fun cursorFollows(inserted: String) =
        currentInputConnection?.getTextBeforeCursor(inserted.length, 0)?.toString() == inserted

    private fun undoLast() {
        val inserted = lastInserted
        if (inserted == null || !cursorFollows(inserted)) {
            setStatus("Nothing to undo.")
            return
        }
        currentInputConnection?.deleteSurroundingText(inserted.length, 0)
        lastInserted = null
        last = null
        setStatus("Removed the last dictation.")
    }

    private fun deleteOne() = sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)

    private fun pressEnter() {
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (info != null && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun switchToOtherKeyboard() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            @Suppress("DEPRECATION")
            inputMethodManager().switchToLastInputMethod(window.window?.attributes?.token)
        }
        if (!switched) inputMethodManager().showInputMethodPicker()
    }

    private fun inputMethodManager() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    // ---------- Display ----------

    private fun render() {
        if (!::mic.isInitialized) return
        micIcon.setImageResource(if (state == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
        micIcon.visibility = if (state == State.WORKING) View.GONE else View.VISIBLE
        spinner.visibility = if (state == State.WORKING) View.VISIBLE else View.GONE
        level.alpha = if (state == State.RECORDING) 0.3f else 0f
    }

    private fun showLevel(value: Float) {
        if (!::level.isInitialized) return
        val scale = 1f + value * 0.5f
        level.scaleX = scale
        level.scaleY = scale
    }

    private fun setStatus(message: String, error: Boolean = false) {
        if (!::status.isInitialized) return
        status.text = message
        status.setTextColor(getColor(if (error) R.color.error else R.color.muted))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val HOLD_MS = 350L
        const val MAX_RECORDING_MS = 6 * 60 * 1000L
    }
}
