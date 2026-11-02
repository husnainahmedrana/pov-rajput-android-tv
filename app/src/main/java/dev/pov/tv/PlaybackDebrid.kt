package dev.pov.tv

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import dev.pov.tv.data.DebridServices
import dev.pov.tv.data.Easynews

/** Explicit resolution provenance, never guessed from the account priority or filename. */
internal object PlaybackDebrid {
    private const val EASYNEWS = "easynews"
    fun normalize(service: String?): String? = service?.takeIf { it in DebridServices.IDS }
    private fun logo(id: String) = when (id) {
        "offcloud" -> R.drawable.pov_offcloud_logo
        "torbox" -> R.drawable.pov_torbox_logo
        "realdebrid" -> R.drawable.pov_realdebrid_logo
        "alldebrid" -> R.drawable.pov_alldebrid_logo
        "premiumize" -> R.drawable.pov_premiumize_logo
        EASYNEWS -> R.drawable.pov_easynews_logo
        else -> R.drawable.pov_debridlink_logo
    }
    /** AllDebrid's mark is a wordmark that already spells the name. */
    private fun wordmark(id: String?) = id == "alldebrid"

    /**
     * [url] marks Easynews: an easynews.com address is the same test the player uses to add the Easynews login, so the
     * badge shows exactly when the video streams from Easynews.
     */
    fun bind(root: View, service: String?, url: String = "") {
        val id = normalize(service) ?: EASYNEWS.takeIf { Easynews.isHost(url) }
        root.findViewById<View>(R.id.pov_debrid_badge).visibility = if (id == null) View.GONE else View.VISIBLE
        val label = when (id) { null -> ""; EASYNEWS -> "Easynews"; else -> DebridServices.name(id) }
        root.findViewById<TextView>(R.id.pov_debrid_name).apply {
            text = label; visibility = if (wordmark(id)) View.GONE else View.VISIBLE
        }
        root.findViewById<ImageView>(R.id.pov_debrid_logo).apply {
            val density = resources.displayMetrics.density
            layoutParams = layoutParams.apply { width = ((if (wordmark(id)) 72 else 20) * density).toInt() }
            setImageResource(if (id == null) 0 else logo(id))
            contentDescription = if (id == null) null else "Streaming with $label"
        }
    }
}
