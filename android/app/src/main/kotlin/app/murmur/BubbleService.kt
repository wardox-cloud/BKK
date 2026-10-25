package app.murmur

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import app.murmur.core.Dictation
import app.murmur.core.DictationException
import app.murmur.core.Insertion
import app.murmur.core.Prompts
import app.murmur.core.Wav
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * The floating mic: a small bubble over every app, shown while you're typing.
 * Tap it, talk, tap again, and the text goes into the box you're typing in.
 *
 * It's an accessibility service because that's what lets an app draw over
 * other apps and type into their text boxes.
 */
class BubbleService : AccessibilityService() {
    private enum class State { IDLE, RECORDING, WORKING }

    private lateinit var prefs: Prefs
    private val prompts by lazy { Prompts.load() }
    private var dictationInstance: Dictation? = null

    /** Settable so tests can point it at a fake server. */
    internal var dictation: Dictation
        get() = dictationInstance ?: Dictation(prompts, quota = prefs.geminiQuota).also { dictationInstance = it }
        set(value) {
            dictationInstance = value
        }

    private val recorder = PcmRecorder()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var windows: WindowManager? = null
    private var bubble: FrameLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var icon: ImageView
    private lateinit var spinner: ProgressBar

    private var state = State.IDLE
    private var typing = false

    internal val bubbleView: View? get() = bubble

    /** Android silences the mic for apps it considers in the background; check soon after starting. */
    private val silenceCheck = Runnable {
        if (state == State.RECORDING && recorder.isSilenced()) {
            recorder.stop()
            prefs.micPanel = true
            state = State.IDLE
            render()
            toast("This phone needs the mic panel. Talk now.")
            openPanel()
        }
    }

    private val tooLong = Runnable { if (state == State.RECORDING) finishRecording() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        attach()
    }

    /** Sets up the bubble; separate from onServiceConnected so tests can call it. */
    internal fun attach() {
        prefs = Prefs(this)
        instance = this
        addBubble()
        refreshVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName == packageName) return
        typing = focusedTextBox() != null
        refreshVisibility()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        recorder.stop()
        worker.shutdownNow()
        bubble?.let { runCatching { windows?.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    // ---------- Bubble ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windows = manager
        val size = dp(56)
        icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.on_accent))
            visibility = View.GONE
        }
        val view = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.mic_button)
            elevation = dp(6).toFloat()
            contentDescription = getString(R.string.mic)
            addView(icon, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            addView(spinner, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            setOnClickListener { onBubbleTap() } // screen readers
            setOnTouchListener(DragOrTap())
        }
        val screen = resources.displayMetrics
        val (savedX, savedY) = prefs.bubblePosition
        params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Never take focus, so the text box you're typing in keeps it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX else screen.widthPixels - size - dp(8)
            y = if (savedY >= 0) savedY else (screen.heightPixels * 0.4).toInt()
        }
        manager.addView(view, params)
        bubble = view
    }

    /** Drags the bubble around (snapping to the nearest edge), or treats a still touch as a tap. */
    private inner class DragOrTap : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windows?.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val width = resources.displayMetrics.widthPixels
                        params.x = if (params.x + view.width / 2 < width / 2) dp(8) else width - view.width - dp(8)
                        windows?.updateViewLayout(view, params)
                        prefs.bubblePosition = params.x to params.y
                    } else {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onBubbleTap()
                    }
                }
            }
            return true
        }
    }

    private fun refreshVisibility() {
        val show = state != State.IDLE || typing || prefs.bubbleAlways
        bubble?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun render() {
        if (bubble == null) return
        icon.setImageResource(if (state == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
        icon.visibility = if (state == State.WORKING) View.GONE else View.VISIBLE
        spinner.visibility = if (state == State.WORKING) View.VISIBLE else View.GONE
        if (state != State.RECORDING) showLevel(0f)
        refreshVisibility()
    }

    private fun showLevel(level: Float) {
        val scale = 1f + level * 0.25f
        bubble?.scaleX = scale
        bubble?.scaleY = scale
    }

    // ---------- Dictation ----------

    private fun onBubbleTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> MicPanelActivity.current?.finishRecording() ?: finishRecording()
            State.WORKING -> Unit
        }
    }

    private fun setupProblem(): String? = when {
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            "Murmur needs the microphone. Opening its settings."
        prefs.key(prefs.provider).isBlank() -> "Add your Gemini key in Murmur first. Opening its settings."
        else -> null
    }

    private fun startRecording() {
        setupProblem()?.let {
            toast(it)
            startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (prefs.micPanel) {
            openPanel()
            return
        }
        try {
            recorder.start { level -> main.post { if (state == State.RECORDING) showLevel(level) } }
        } catch (e: Exception) {
            toast("Couldn't start the microphone: ${e.message}")
            return
        }
        state = State.RECORDING
        render()
        main.postDelayed(silenceCheck, 900)
        main.postDelayed(tooLong, MAX_RECORDING_MS)
    }

    private fun finishRecording() {
        main.removeCallbacks(silenceCheck)
        main.removeCallbacks(tooLong)
        process(recorder.stop())
    }

    /** The mic panel records in the foreground when Android won't let the bubble do it. */
    private fun openPanel() {
        state = State.RECORDING
        render()
        startActivity(
            Intent(this, MicPanelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    /** Called with the panel's recording, or null if you cancelled it. */
    internal fun onPanelFinished(pcm: ByteArray?) {
        if (pcm == null) {
            state = State.IDLE
            render()
        } else {
            process(pcm)
        }
    }

    /** Turns a recording into text and puts it where you're typing. */
    internal fun process(pcm: ByteArray) {
        if (pcm.size < Wav.SAMPLE_RATE) { // under half a second
            state = State.IDLE
            render()
            toast("That was too short. Tap the mic, talk, then tap it again.")
            return
        }
        state = State.WORKING
        render()
        val settings = prefs.dictationSettings()
        val wav = Wav.encode(pcm)
        worker.execute {
            try {
                val result = dictation.run(wav, settings)
                main.post { deliver(result.text, result.note) }
            } catch (e: Exception) {
                val message = (e as? DictationException)?.message ?: "Something went wrong (${e.message})."
                main.post {
                    state = State.IDLE
                    render()
                    toast(message)
                }
            }
        }
    }

    private fun deliver(text: String, note: String?, attempt: Int = 0) {
        val box = focusedTextBox()
        // After the mic panel closes, the text box takes a moment to get focus back.
        if (box == null && attempt < 5) {
            main.postDelayed({ deliver(text, note, attempt + 1) }, 200)
            return
        }
        val inserted = box != null && insertInto(box, text)
        if (!inserted) copyToClipboard(text)
        state = State.IDLE
        render()
        val message = note ?: if (inserted) null else "Copied. Long-press a text box to paste."
        message?.let { toast(it) }
    }

    /** Finds the text box you're typing in; replaceable in tests, which have no real screen to search. */
    internal var findTextBox: () -> AccessibilityNodeInfo? = {
        runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }
    }

    private fun focusedTextBox() = findTextBox()

    /** Types [text] at the cursor in [box]: replaces its text if the app allows, otherwise pastes. */
    internal fun insertInto(box: AccessibilityNodeInfo, text: String): Boolean {
        val existing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && box.isShowingHintText) "" else box.text?.toString().orEmpty()
        val result = Insertion.splice(existing, box.textSelectionStart, box.textSelectionEnd, text)
        val replaced = box.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result.text) },
        )
        if (replaced) {
            box.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, result.cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, result.cursor)
                },
            )
            return true
        }
        // Some apps (often web views) ignore set-text but accept a paste at the cursor.
        val start = box.textSelectionStart.takeIf { it in 0..existing.length } ?: existing.length
        copyToClipboard(Insertion.spaced(text, existing.getOrNull(start - 1)))
        return box.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Murmur", text))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** The running service, so the mic panel can hand its recording back. */
        @Volatile
        internal var instance: BubbleService? = null

        private const val MAX_RECORDING_MS = 6 * 60 * 1000L
    }
}
