package app.murmur

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView

/**
 * A small "Listening…" panel at the bottom of the screen. Android always lets
 * a visible screen use the mic, so the bubble uses this on phones that
 * silence it otherwise.
 */
class MicPanelActivity : Activity() {
    private val recorder = PcmRecorder()
    private val main = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var done = false
    private lateinit var status: TextView
    private lateinit var level: View

    private val tick = object : Runnable {
        override fun run() {
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            status.text = getString(R.string.listening, seconds / 60, seconds % 60)
            if (seconds * 1000 >= MAX_RECORDING_MS) finishRecording() else main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.mic_panel)
        setFinishOnTouchOutside(false)
        status = findViewById(R.id.panel_status)
        level = findViewById(R.id.panel_level)
        findViewById<View>(R.id.panel_stop).setOnClickListener { finishRecording() }
        findViewById<View>(R.id.panel_cancel).setOnClickListener { cancel() }
        current = this
        try {
            recorder.start { value ->
                main.post {
                    val scale = 1f + value * 0.5f
                    level.scaleX = scale
                    level.scaleY = scale
                }
            }
        } catch (e: Exception) {
            status.text = getString(R.string.mic_failed, e.message)
            return
        }
        startedAt = SystemClock.elapsedRealtime()
        main.post(tick)
    }

    /** Stops listening and hands the recording to the bubble, which types it in. */
    fun finishRecording() {
        if (done) return
        done = true
        main.removeCallbacks(tick)
        val pcm = recorder.stop()
        finish()
        BubbleService.instance?.onPanelFinished(pcm)
    }

    private fun cancel() {
        if (done) return
        done = true
        main.removeCallbacks(tick)
        recorder.stop()
        finish()
        BubbleService.instance?.onPanelFinished(null)
    }

    override fun onPause() {
        // Leaving the panel (home button, notification) keeps what was said so far.
        if (!isFinishing) finishRecording()
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        if (!done) cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        internal var current: MicPanelActivity? = null

        private const val MAX_RECORDING_MS = 6 * 60 * 1000L
    }
}
