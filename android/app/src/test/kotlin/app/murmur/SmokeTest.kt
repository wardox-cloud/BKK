package app.murmur

import android.Manifest
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import app.murmur.core.CleanupEngine
import app.murmur.core.SttProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Opens the real setup screen and keyboard in a simulated phone and checks they work. */
@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @Test
    fun `setup screen saves keys and choices`() {
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val provider = activity.findViewById<Spinner>(R.id.provider)
        assertEquals(3, provider.adapter.count)
        assertEquals(4, activity.findViewById<Spinner>(R.id.language).adapter.count)
        assertEquals(SttProvider.GEMINI.label, provider.selectedItem)

        activity.findViewById<EditText>(R.id.stt_key).setText(" AIza-test ")
        activity.findViewById<EditText>(R.id.anthropic_key).setText("sk-ant-test")
        activity.findViewById<EditText>(R.id.vocabulary).setText("Shmookh\nAxiom Talent")

        // Switch to OpenAI, give it its own key, and save.
        provider.setSelection(SttProvider.entries.indexOf(SttProvider.OPENAI))
        provider.onItemSelectedListener!!.onItemSelected(provider, null, provider.selectedItemPosition, 0)
        assertEquals("", activity.findViewById<EditText>(R.id.stt_key).text.toString())
        activity.findViewById<EditText>(R.id.stt_key).setText("sk-openai")
        activity.findViewById<Button>(R.id.save).performClick()

        val settings = Prefs(activity).dictationSettings()
        assertEquals(SttProvider.OPENAI, settings.provider)
        assertEquals("sk-openai", settings.sttKey)
        assertEquals("AIza-test", settings.geminiKey)
        assertEquals("sk-ant-test", settings.anthropicKey)
        assertEquals(CleanupEngine.CLAUDE, settings.cleanup)
        assertEquals("claude-opus-5-5", settings.claudeModel)
        assertEquals("roman", settings.language)
        assertEquals(listOf("Shmookh", "Axiom Talent"), settings.vocabulary)
    }

    @Test
    fun `keyboard shows its controls and says what setup is missing`() {
        val keyboard = Robolectric.buildService(MurmurKeyboard::class.java).create().get()
        val view = keyboard.onCreateInputView()
        val status = view.findViewById<TextView>(R.id.status)

        keyboard.onStartInputView(EditorInfo(), false)
        assertEquals(5, view.findViewById<LinearLayout>(R.id.styles).childCount)
        assertTrue(status.text.toString(), status.text.contains("microphone"))

        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        keyboard.onStartInputView(EditorInfo(), false)
        assertTrue(status.text.toString(), status.text.contains("API keys"))

        Prefs(keyboard).edit { putString("key_gemini", "AIza-test") }
        keyboard.onStartInputView(EditorInfo(), false)
        assertTrue(status.text.toString(), status.text.startsWith("Tap the mic"))
    }

    @Test
    fun `tapping a style chip selects it`() {
        val keyboard = Robolectric.buildService(MurmurKeyboard::class.java).create().get()
        val view = keyboard.onCreateInputView()
        keyboard.onStartInputView(EditorInfo(), false)
        val styles = view.findViewById<LinearLayout>(R.id.styles)

        (styles.getChildAt(3) as TextView).performClick() // Notes
        assertEquals("notes", Prefs(keyboard).style)
        assertEquals("Notes", (styles.getChildAt(3) as TextView).text)
    }
}
