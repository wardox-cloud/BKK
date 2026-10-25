package app.murmur

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/** Just enough Markdown for meeting notes: headings, bullets, to-dos and **bold**. */
object Markdown {
    fun render(text: String): CharSequence {
        val out = SpannableStringBuilder()
        for ((index, raw) in text.trim().lines().withIndex()) {
            if (index > 0) out.append('\n')
            val line = raw.trimEnd()
            val heading = Regex("^#{1,6}\\s+(.*)").find(line)
            when {
                heading != null -> {
                    val start = out.length
                    out.append(heading.groupValues[1].replace("**", ""))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(1.1f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Regex("^\\s*[-*] \\[[ xX]] ").containsMatchIn(line) -> {
                    val done = Regex("^\\s*[-*] \\[[xX]] ").containsMatchIn(line)
                    out.append(if (done) "☑ " else "☐ ")
                    inline(out, line.replace(Regex("^\\s*[-*] \\[[ xX]] "), ""))
                }
                Regex("^\\s*[-*] ").containsMatchIn(line) -> {
                    out.append("• ")
                    inline(out, line.replace(Regex("^\\s*[-*] "), ""))
                }
                else -> inline(out, line)
            }
        }
        return out
    }

    private fun inline(out: SpannableStringBuilder, text: String) {
        val parts = text.split("**")
        for ((index, part) in parts.withIndex()) {
            val start = out.length
            out.append(part)
            if (index % 2 == 1 && index < parts.size - 1) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
