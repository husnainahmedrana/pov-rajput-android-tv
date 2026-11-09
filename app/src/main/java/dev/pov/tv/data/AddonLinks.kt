package dev.pov.tv.data

/** Add-on install links handed to POV: a stremio:// link opened from a web page, or a link shared from another app. */
object AddonLinks {
    private const val VIEW = "android.intent.action.VIEW"
    private const val SEND = "android.intent.action.SEND"
    private val LINK = Regex("(?i)\\b(?:https|stremio)://[^\\s\"'<>]+")

    /** The add-on link in an incoming intent's [action], [data] and shared [text], or null when there isn't one. */
    fun from(action: String?, data: String?, text: String?): String? = when (action) {
        VIEW -> data?.trim()?.takeIf { it.startsWith("stremio://", ignoreCase = true) && it.length > "stremio://".length }
        // Shared text often wraps the link in a title or a sentence: the first link in it.
        SEND -> text?.let { LINK.find(it)?.value?.trimEnd('.', ',', ')', ']') }
        else -> null
    }?.take(16_000)

    /** stremio:// install links are the same address over HTTPS. */
    fun https(link: String): String = link.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")

    /** The add-on's manifest address, for reading its name. */
    fun manifest(link: String): String = runCatching { SourceProviders.endpoint(https(link)) + "/manifest.json" }
        .getOrElse { https(link).let { if (it.substringBefore('?').endsWith("manifest.json")) it else it.trimEnd('/') + "/manifest.json" } }
}
