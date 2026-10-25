package app.murmur.core

/**
 * The writing styles: the built-in ones plus any you write yourself, one per
 * line as "Name: what it should do", for example
 * "LinkedIn: Write it as a LinkedIn post, warm and professional".
 */
class Styles(prompts: Prompts, custom: String) {
    data class Style(val id: String, val label: String, val instruction: String? = null)

    val all: List<Style> = prompts.styles.map { (id, label) -> Style(id, label) } + parse(custom)

    fun find(id: String?): Style? = all.firstOrNull { it.id == id }

    /** The style for dictating into [app]: the one you picked there, else a sensible default for it, else [fallback]. */
    fun forApp(app: String?, chosen: Map<String, String>, fallback: String): Style {
        val id = app?.let { chosen[it] ?: APP_DEFAULTS[it] } ?: fallback
        return find(id) ?: find(fallback) ?: all.first()
    }

    companion object {
        fun parse(custom: String): List<Style> = custom.lines().mapNotNull { line ->
            val name = line.substringBefore(':').trim()
            val instruction = line.substringAfter(':', "").trim()
            if (name.isEmpty() || instruction.isEmpty()) null else Style("custom:" + name.lowercase(), name, instruction)
        }.distinctBy { it.id }

        /** Apps where one style is almost always right. */
        val APP_DEFAULTS: Map<String, String> = buildMap {
            for (app in listOf(
                "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca", "com.instagram.android",
                "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.Slack", "com.microsoft.teams",
                "com.discord", "com.snapchat.android", "com.viber.voip",
            )) put(app, "message")
            for (app in listOf(
                "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
                "com.yahoo.mobile.client.android.mail", "ch.protonmail.android",
            )) put(app, "email")
            for (app in listOf(
                "com.google.android.keep", "com.samsung.android.app.notes", "com.microsoft.office.onenote", "md.obsidian",
                "notion.id", "com.notion.id",
            )) put(app, "notes")
        }
    }
}
