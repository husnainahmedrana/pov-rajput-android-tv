package dev.pov.tv

/** The TV and phone apps share every screen; this is the one switch between their layouts. */
internal object DeviceLayout {
    val phone = BuildConfig.MOBILE
    val tv = !phone
    /**
     * Logical width that every layout size is drawn against: the TV's 960 x 540 canvas, or about one
     * real dp per unit across a portrait phone.
     */
    val canvasWidth = if (phone) 400 else 960

    /**
     * Pixels per canvas unit. On a phone this follows the short side, so a view shown in the landscape player (the
     * Streams panel's badges) is the same size as in the portrait app instead of growing with the wider screen.
     */
    fun unit(resources: android.content.res.Resources): Float {
        val metrics = resources.displayMetrics
        val across = if (phone) minOf(metrics.widthPixels, metrics.heightPixels) else metrics.widthPixels
        return across / canvasWidth.toFloat()
    }
}
