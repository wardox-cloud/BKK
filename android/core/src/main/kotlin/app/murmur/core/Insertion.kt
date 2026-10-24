package app.murmur.core

/** Where dictated text goes in a text box, and how it's spaced. */
object Insertion {
    /** Adds a space before [text] when it would otherwise run into the previous word. */
    fun spaced(text: String, before: Char?): String =
        if (before != null && !before.isWhitespace() && text.firstOrNull()?.isWhitespace() == false) " $text" else text

    data class Result(val text: String, val cursor: Int)

    /**
     * Puts [dictation] into [existing] in place of the selection [start]..[end]
     * (a plain cursor when they're equal; -1 means no cursor, so it goes at the end).
     */
    fun splice(existing: String, start: Int, end: Int, dictation: String): Result {
        val from = if (start in 0..existing.length) start else existing.length
        val to = if (end in from..existing.length) end else from
        val insert = spaced(dictation, existing.getOrNull(from - 1))
        return Result(existing.substring(0, from) + insert + existing.substring(to), from + insert.length)
    }
}
