package dev.pov.tv

/** A pending seek never changes playback until the remote key is released. */
internal class DpadSeekPreview {
    var active = false
        private set
    var position = 0L
        private set
    private var origin = 0L
    private var duration = 0L
    private var direction = 0
    private var distanceInDirection = 0L
    val offsetSeconds get() = (position - origin) / 1000
    fun begin(current: Long, length: Long): Boolean {
        if (length <= 0) return false
        duration = length; origin = current.coerceIn(0, length); position = origin; active = true
        direction = 0; distanceInDirection = 0
        return true
    }
    fun step(direction: Int): Long {
        require(active && direction in listOf(-1, 1))
        if (this.direction != direction) { this.direction = direction; distanceInDirection = 0 }
        // One-second steps for the first ten seconds of a hold, then a gentle climb that tops out at 30 seconds a step,
        // so a held key never races past the scene (seek previews change every 10 seconds).
        val increment = when {
            distanceInDirection >= 900_000 -> 30_000L
            distanceInDirection >= 300_000 -> 20_000L
            distanceInDirection >= 60_000 -> 10_000L
            distanceInDirection >= 10_000 -> 5_000L
            else -> 1_000L
        }
        val previous = position
        position = if (direction > 0) position + minOf(increment, duration - position) else position - minOf(increment, position)
        distanceInDirection = (distanceInDirection + kotlin.math.abs(position - previous)).coerceAtMost(900_000)
        return position
    }
    /** A fresh press of the key (not a held repeat) starts again from precise one-second steps. */
    fun newPress() { distanceInDirection = 0 }
    fun finish(cancelled: Boolean = false): Long? {
        val result = if (active && !cancelled) position else null
        active = false
        return result
    }
    companion object {
        /** A signed offset such as "+0:45" or "-12:30". */
        fun offset(milliseconds: Long): String = (if (milliseconds < 0) "-" else "+") + time(kotlin.math.abs(milliseconds)).removePrefix("0")
        fun time(position: Long): String {
            val seconds = position.coerceAtLeast(0) / 1000
            return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
        }
    }
}
