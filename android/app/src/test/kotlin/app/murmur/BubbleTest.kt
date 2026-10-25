package app.murmur

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.murmur.core.Dictation
import app.murmur.core.Prompts
import app.murmur.core.SttProvider
import java.time.Duration
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertNull
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/** The floating mic, driven end to end in a simulated phone against a fake Gemini. */
@RunWith(RobolectricTestRunner::class)
class BubbleTest {
    private val app = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private lateinit var bubble: BubbleService
    private val performed = mutableListOf<Pair<Int, Bundle?>>()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        Prefs(app).edit { putString("key_gemini", "AIza-test") }
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"yaar mujhe kal report bhejni hai"}]}}]}"""))
        bubble = Robolectric.buildService(BubbleService::class.java).create().get()
        bubble.attach()
        bubble.dictation = Dictation(Prompts.load(), baseUrls = SttProvider.entries.associateWith { server.url("/fake").toString() })
    }

    @After
    fun tearDown() {
        server.shutdown()
        OfflineRecognizer.available = { false }
    }

    private fun textBox(
        text: String,
        cursor: Int,
        selectionEnd: Int = cursor,
        app: String? = null,
        accept: (Int) -> Boolean = { true },
    ) = AccessibilityNodeInfo().apply {
        isEditable = true
        this.text = text
        packageName = app
        setTextSelection(cursor, selectionEnd)
        shadowOf(this).setOnPerformActionListener { action, args ->
            performed += action to args
            accept(action)
        }
    }

    private fun waitUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        assertTrue("timed out", done())
    }

    private fun clipboardText() =
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun `it shows while you type and hides otherwise`() {
        val focus = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED).apply { packageName = "com.whatsapp" }

        bubble.findTextBox = { null }
        bubble.onAccessibilityEvent(focus)
        assertEquals(View.GONE, bubble.bubbleView!!.visibility)

        bubble.findTextBox = { textBox("", 0) }
        bubble.onAccessibilityEvent(focus)
        assertEquals(View.VISIBLE, bubble.bubbleView!!.visibility)
    }

    @Test
    fun `it types the dictation at the cursor`() {
        bubble.findTextBox = { textBox("Hi", 2) }
        bubble.process(ByteArray(32_000))
        waitUntil { performed.size >= 2 }

        assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, performed[0].first)
        assertEquals(
            "Hi yaar mujhe kal report bhejni hai",
            performed[0].second!!.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString(),
        )
        assertEquals(AccessibilityNodeInfo.ACTION_SET_SELECTION, performed[1].first)
        assertEquals(35, performed[1].second!!.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT))
    }

    @Test
    fun `apps that ignore set-text get a paste instead`() {
        bubble.findTextBox = { textBox("Hi", 2, accept = { it != AccessibilityNodeInfo.ACTION_SET_TEXT }) }
        bubble.process(ByteArray(32_000))
        waitUntil { performed.any { it.first == AccessibilityNodeInfo.ACTION_PASTE } }

        assertEquals(" yaar mujhe kal report bhejni hai", clipboardText())
    }

    @Test
    fun `with no text box the words land on the clipboard`() {
        bubble.findTextBox = { null }
        bubble.process(ByteArray(32_000))
        waitUntil { ShadowToast.getTextOfLatestToast() != null }

        assertEquals("yaar mujhe kal report bhejni hai", clipboardText())
        assertEquals("Copied. Long-press a text box to paste.", ShadowToast.getTextOfLatestToast())
    }

    private fun sentText() =
        performed.first { it.first == AccessibilityNodeInfo.ACTION_SET_TEXT }.second!!
            .getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString()

    @Test
    fun `speaking with text selected rewrites that text`() {
        bubble.findTextBox = { textBox("Hi. Would it perhaps be possible to meet tomorrow?", 4, 50) }
        bubble.captureTarget()
        bubble.process(ByteArray(32_000))
        waitUntil { performed.size >= 2 }

        val request = server.takeRequest().body.readUtf8()
        assertTrue(request.contains("Someone selected text in an app"))
        assertTrue(request.contains("<selected>\\nWould it perhaps be possible to meet tomorrow?\\n</selected>"))
        // The selection is replaced by what came back.
        assertEquals("Hi. yaar mujhe kal report bhejni hai", sentText())
    }

    @Test
    fun `each app gets its own style`() {
        bubble.findTextBox = { textBox("", 0, app = "com.google.android.gm") }
        bubble.captureTarget()
        bubble.process(ByteArray(32_000))
        waitUntil { performed.size >= 2 }
        assertTrue(server.takeRequest().body.readUtf8().contains("Output an email body"))

        Prefs(app).setAppStyle("com.google.android.gm", "notes")
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"- report"}]}}]}"""))
        bubble.captureTarget()
        bubble.process(ByteArray(32_000))
        waitUntil { performed.size >= 4 }
        assertTrue(server.takeRequest().body.readUtf8().contains("as \\\"- \\\" bullet points"))
    }

    @Test
    fun `dictations are kept in history`() {
        bubble.findTextBox = { textBox("", 0, app = "com.whatsapp") }
        bubble.captureTarget()
        bubble.process(ByteArray(32_000))
        waitUntil { performed.size >= 2 }

        val entry = Meetings.history(app).entries().single()
        assertEquals("yaar mujhe kal report bhejni hai", entry.text)
        assertEquals("com.whatsapp", entry.app)
    }

    @Test
    fun `with no internet the phone's own recognizer writes it`() {
        server.shutdown()
        var heard: ByteArray? = null
        OfflineRecognizer.available = { true }
        OfflineRecognizer.recognize = { _, pcm, done ->
            heard = pcm
            done("see you at five")
        }
        bubble.findTextBox = { textBox("Hi", 2) }
        bubble.captureTarget()
        bubble.process(ByteArray(32_000))
        waitUntil { performed.size >= 2 }

        assertEquals(32_000, heard!!.size)
        assertEquals("Hi see you at five", sentText())
        assertEquals(app.getString(R.string.offline_note), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `with no internet and the fallback off, it says why`() {
        server.shutdown()
        OfflineRecognizer.available = { true }
        Prefs(app).edit { putBoolean("offline_fallback", false) }
        bubble.findTextBox = { textBox("Hi", 2) }
        bubble.captureTarget()
        bubble.process(ByteArray(32_000))
        waitUntil { ShadowToast.getTextOfLatestToast() != null }

        assertEquals("Couldn't reach Gemini. Check your connection, then try again.", ShadowToast.getTextOfLatestToast())
        assertNull(performed.firstOrNull())
    }
}
