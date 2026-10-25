package app.murmur

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import app.murmur.core.CLAUDE_MODELS
import app.murmur.core.CleanupEngine
import app.murmur.core.Prompts
import app.murmur.core.SttProvider

/** Setup checklist and settings: enable the keyboard, allow the mic, add keys. */
class SetupActivity : Activity() {
    private lateinit var prefs: Prefs
    private val prompts by lazy { Prompts.load() }

    private val providers = SttProvider.entries
    private val engines = CleanupEngine.entries
    private val claudeModels = CLAUDE_MODELS.keys.toList()
    private lateinit var languages: List<String>

    // Keys and models per provider while the screen is open.
    private val keys = mutableMapOf<SttProvider, String>()
    private val models = mutableMapOf<SttProvider, String>()
    private var shownProvider = SttProvider.GEMINI

    private lateinit var stepEnable: TextView
    private lateinit var stepMic: TextView
    private lateinit var stepKey: TextView
    private lateinit var stepBubble: TextView
    private lateinit var bubbleAlways: CheckBox
    private lateinit var language: Spinner
    private lateinit var provider: Spinner
    private lateinit var sttKey: EditText
    private lateinit var sttModel: EditText
    private lateinit var cleanup: Spinner
    private lateinit var geminiCleanupKey: EditText
    private lateinit var anthropicKey: EditText
    private lateinit var claudeModel: Spinner
    private lateinit var vocabulary: EditText
    private lateinit var switchBack: CheckBox
    private lateinit var customStyles: EditText
    private lateinit var offlineFallback: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        prefs = Prefs(this)
        languages = prompts.languages.keys.toList()

        stepEnable = findViewById(R.id.step_enable)
        stepMic = findViewById(R.id.step_mic)
        stepKey = findViewById(R.id.step_key)
        stepBubble = findViewById(R.id.step_bubble)
        bubbleAlways = findViewById(R.id.bubble_always)
        language = findViewById(R.id.language)
        provider = findViewById(R.id.provider)
        sttKey = findViewById(R.id.stt_key)
        sttModel = findViewById(R.id.stt_model)
        cleanup = findViewById(R.id.cleanup)
        geminiCleanupKey = findViewById(R.id.gemini_cleanup_key)
        anthropicKey = findViewById(R.id.anthropic_key)
        claudeModel = findViewById(R.id.claude_model)
        vocabulary = findViewById(R.id.vocabulary)
        switchBack = findViewById(R.id.switch_back)
        customStyles = findViewById(R.id.custom_styles)
        offlineFallback = findViewById(R.id.offline_fallback)

        language.adapter = adapter(prompts.languages.values.toList())
        provider.adapter = adapter(providers.map { it.label })
        cleanup.adapter = adapter(engines.map { it.label })
        claudeModel.adapter = adapter(CLAUDE_MODELS.values.toList())

        for (each in providers) {
            keys[each] = prefs.key(each)
            models[each] = prefs.model(each)
        }
        shownProvider = prefs.provider
        language.setSelection(languages.indexOf(prefs.language).coerceAtLeast(0))
        provider.setSelection(providers.indexOf(shownProvider))
        cleanup.setSelection(engines.indexOf(prefs.cleanup))
        claudeModel.setSelection(claudeModels.indexOf(prefs.claudeModel).coerceAtLeast(0))
        anthropicKey.setText(prefs.anthropicKey)
        vocabulary.setText(prefs.vocabulary)
        switchBack.isChecked = prefs.switchBack
        bubbleAlways.isChecked = prefs.bubbleAlways
        customStyles.setText(prefs.customStyles)
        offlineFallback.isChecked = prefs.offlineFallback
        showProvider()

        provider.onItemSelectedListener = onSelected {
            stashProvider()
            shownProvider = providers[provider.selectedItemPosition]
            showProvider()
        }
        cleanup.onItemSelectedListener = onSelected { showCleanupFields() }

        findViewById<Button>(R.id.enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.allow_mic).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        }
        findViewById<Button>(R.id.get_key).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.app_info).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        findViewById<Button>(R.id.choose_keyboard).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.reset_app_styles).setOnClickListener {
            prefs.clearAppStyles()
            showAppStyles()
        }
    }

    override fun onResume() {
        super.onResume()
        showSteps()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showSteps()
    }

    private fun showSteps() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val micAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bubbleOn = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':')
            .any { ComponentName.unflattenFromString(it) == ComponentName(this, BubbleService::class.java) }
        stepEnable.text = getString(R.string.step_enable) + if (enabled) "  ✓" else ""
        stepMic.text = getString(R.string.step_mic) + if (micAllowed) "  ✓" else ""
        stepKey.text = getString(R.string.step_key) + if (prefs.key(prefs.provider).isNotBlank()) "  ✓" else ""
        stepBubble.text = getString(R.string.step_bubble) + if (bubbleOn) "  ✓" else ""
        showQuota()
        showAppStyles()
    }

    private fun showAppStyles() {
        val styles = prefs.styles(prompts)
        val picked = prefs.appStyles.entries.joinToString(", ") { (app, style) ->
            HistoryActivity.appName(this, app) + " → " + (styles.find(style)?.label ?: style)
        }
        findViewById<TextView>(R.id.app_styles).text =
            if (picked.isEmpty()) getString(R.string.app_styles_none) else getString(R.string.app_styles, picked)
    }

    /** Which models are out of free requests today, if any. */
    private fun showQuota() {
        val quota = prefs.geminiQuota
        val usedUp = quota.rests().filter { it.daily }.map { rest ->
            rest.perDay?.let { getString(R.string.quota_per_day, rest.model, it) } ?: rest.model
        }
        val status = findViewById<TextView>(R.id.quota_status)
        status.visibility = if (usedUp.isEmpty()) View.GONE else View.VISIBLE
        status.text = getString(R.string.quota_used_up, usedUp.joinToString(", "), quota.nextResetLocal())
        val usage = quota.usage()
        findViewById<TextView>(R.id.usage_today).text = if (usage.isEmpty()) {
            getString(R.string.usage_none, quota.nextResetLocal())
        } else {
            getString(R.string.usage_today, usage.values.sum(), usage.entries.joinToString(", ") { "${it.key} ${it.value}" }, quota.nextResetLocal())
        }
    }

    private fun showProvider() {
        sttKey.setText(keys[shownProvider])
        sttKey.hint = when (shownProvider) {
            SttProvider.GEMINI -> "AIza..."
            SttProvider.OPENAI -> "sk-..."
            SttProvider.GROQ -> "gsk_..."
        }
        sttModel.setText(models[shownProvider])
        showCleanupFields()
    }

    private fun stashProvider() {
        keys[shownProvider] = sttKey.text.toString().trim()
        models[shownProvider] = sttModel.text.toString().trim()
    }

    /** Gemini cleanup needs its own key field only when another provider transcribes. */
    private fun showCleanupFields() {
        val engine = engines[cleanup.selectedItemPosition]
        val geminiKeyField = engine == CleanupEngine.GEMINI && shownProvider != SttProvider.GEMINI
        visible(geminiKeyField, R.id.gemini_cleanup_key_label, R.id.gemini_cleanup_key)
        if (geminiKeyField) geminiCleanupKey.setText(keys[SttProvider.GEMINI])
        visible(engine == CleanupEngine.CLAUDE, R.id.anthropic_key_label, R.id.anthropic_key, R.id.claude_model_label, R.id.claude_model)
    }

    private fun save() {
        stashProvider()
        if (geminiCleanupKey.visibility == View.VISIBLE) keys[SttProvider.GEMINI] = geminiCleanupKey.text.toString().trim()
        prefs.edit {
            putString("provider", shownProvider.id)
            for (each in providers) {
                putString("key_${each.id}", keys[each])
                putString("model_${each.id}", models[each])
            }
            putString("language", languages[language.selectedItemPosition])
            putString("cleanup", engines[cleanup.selectedItemPosition].id)
            putString("anthropic_key", anthropicKey.text.toString().trim())
            putString("claude_model", claudeModels[claudeModel.selectedItemPosition])
            putString("vocabulary", vocabulary.text.toString())
            putBoolean("switch_back", switchBack.isChecked)
            putBoolean("bubble_always", bubbleAlways.isChecked)
            putString("custom_styles", customStyles.text.toString())
            putBoolean("offline_fallback", offlineFallback.isChecked)
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        showSteps()
    }

    private fun visible(show: Boolean, vararg ids: Int) {
        for (id in ids) findViewById<View>(id).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun adapter(labels: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

    private fun onSelected(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private companion object {
        const val MIC_REQUEST = 1
    }
}
