package app.murmur

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import app.murmur.core.Prompts

/**
 * Picks the writing style for one app, from a long-press on the mic bubble.
 * Murmur remembers it for that app.
 */
class StylePickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.style_picker)
        setFinishOnTouchOutside(true)
        val prefs = Prefs(this)
        val prompts = Prompts.load()
        val app = intent.getStringExtra(EXTRA_APP)
        val styles = prefs.styles(prompts)
        val current = styles.forApp(app, prefs.appStyles, prefs.style).id
        findViewById<TextView>(R.id.picker_title).text =
            if (app == null) getString(R.string.picker_title_default) else getString(R.string.picker_title, HistoryActivity.appName(this, app))
        val list = findViewById<LinearLayout>(R.id.picker_styles)
        for (style in styles.all) {
            val selected = style.id == current
            list.addView(
                TextView(this).apply {
                    text = style.label
                    textSize = 16f
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setTextColor(getColor(if (selected) R.color.on_accent else R.color.text))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(getColor(if (selected) R.color.accent else R.color.surface))
                    }
                    setOnClickListener {
                        prefs.setAppStyle(app, style.id)
                        finish()
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                },
            )
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_APP = "app"

        fun intent(context: Context, app: String?) = Intent(context, StylePickerActivity::class.java)
            .putExtra(EXTRA_APP, app)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
