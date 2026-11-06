package dev.pov.tv

import kotlin.math.roundToInt

/** The TV and phone apps share every screen; this is the one switch between their layouts. */
internal object DeviceLayout {
    val phone = BuildConfig.MOBILE
    val tv = !phone

    /** Canvas units across the short side of a phone: about one real dp on a portrait phone. */
    const val PHONE_SHORT_SIDE = 400

    /**
     * Logical width that every layout size is drawn against: the TV's 960 x 540 canvas, or [PHONE_SHORT_SIDE] across a
     * portrait phone. A phone build shown on a landscape screen (a car head unit, a tablet) keeps the same unit size
     * and gets a proportionally wider canvas, so posters stay phone-sized and rows show more of them instead of
     * every view being stretched to the screen's long side. Set by [fit] before any layout is built.
     */
    var canvasWidth = if (phone) PHONE_SHORT_SIDE else 960
        private set

    /** True for the phone build on a landscape screen, which gets the wider canvas. */
    val wide get() = phone && canvasWidth > PHONE_SHORT_SIDE * 5 / 4

    fun fit(resources: android.content.res.Resources) {
        if (!phone) return
        val metrics = resources.displayMetrics
        val (width, height) = metrics.widthPixels to metrics.heightPixels
        canvasWidth = if (width > height && height > 0) (PHONE_SHORT_SIDE.toFloat() * width / height).roundToInt() else PHONE_SHORT_SIDE
    }

    /** Grid columns for the phone build: two on a portrait phone, more across a wide landscape screen. */
    fun phoneColumns(contentWidth: Int) = (contentWidth / 165).coerceAtLeast(2)

    /**
     * Pixels per canvas unit. On a phone this follows the short side, so a view shown in the landscape player (the
     * Streams panel's badges) is the same size as in the portrait app instead of growing with the wider screen.
     */
    fun unit(resources: android.content.res.Resources): Float {
        val metrics = resources.displayMetrics
        val across = if (phone) minOf(metrics.widthPixels, metrics.heightPixels) else metrics.widthPixels
        return across / (if (phone) PHONE_SHORT_SIDE else canvasWidth).toFloat()
    }
}
