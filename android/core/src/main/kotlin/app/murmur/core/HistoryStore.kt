package app.murmur.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** Your past dictations, kept on the phone: one JSON line each, newest last in the file. */
class HistoryStore(private val file: File, private val limit: Int = 1000) {
    data class Entry(val time: Long = 0, val text: String = "", val app: String? = null)

    private val json = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Synchronized
    fun add(entry: Entry) {
        if (entry.text.isBlank()) return
        file.parentFile?.mkdirs()
        file.appendText(json.writeValueAsString(entry) + "\n")
        // Trim now and then rather than on every dictation.
        val all = read()
        if (all.size > limit * 5 / 4) write(all.takeLast(limit))
    }

    /** Newest first; with a [query], only entries containing all its words. */
    @Synchronized
    fun entries(query: String = ""): List<Entry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return read().asReversed().filter { entry -> words.all { it in entry.text.lowercase() } }.take(limit)
    }

    @Synchronized
    fun remove(entry: Entry) = write(read().filterNot { it == entry })

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun read(): List<Entry> =
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
            runCatching { json.readValue(line, Entry::class.java) }.getOrNull()
        }

    private fun write(entries: List<Entry>) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(entries.joinToString("") { json.writeValueAsString(it) + "\n" })
        temporary.renameTo(file)
    }
}
