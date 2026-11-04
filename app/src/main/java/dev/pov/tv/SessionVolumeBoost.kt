package dev.pov.tv

internal enum class VolumeBoostState { OFF, WAITING_FOR_AUDIO, REQUESTED, UNAVAILABLE }

/** The volume boost every video starts with (Settings → Audio & Subtitles); 0 is off. */
internal class VolumeBoostPreferences(context: android.content.Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pov_audio", android.content.Context.MODE_PRIVATE)
    var defaultDb: Int
        get() = preferences.getInt("default_boost_db", 0).takeIf { it in SessionVolumeBoost.STEPS } ?: 0
        set(value) { require(value in SessionVolumeBoost.STEPS); preferences.edit().putInt("default_boost_db", value).apply() }
    val label: String get() = if (defaultDb == 0) "Off" else "+$defaultDb dB"
}

/**
 * Session-owned software gain. Public methods and notifications run on the main thread.
 * REQUESTED is retained for UI compatibility and means PCM was processed with the selected gain.
 * No platform audio effect is constructed; older TVs can crash natively during effect setup.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SessionVolumeBoost(
    private val dispatchToMain: (() -> Unit) -> Unit,
    private val onStateChanged: () -> Unit = {},
) {
    var gainDb = 0
        private set
    var state = VolumeBoostState.OFF
        private set
    private var generation = 0L
    private var processor: SoftwarePcmGainAudioProcessor? = null
    private var routeState = PcmGainRoute.WAITING
    private var processedGainDb: Int? = null
    private var requestedRevision = 0L
    val processedPcmFrames: Long get() = processor?.processedPcmFrames ?: 0L

    /** A fresh processor belongs to one sink; old audio-thread events cannot revive it. */
    fun createAudioProcessor(): SoftwarePcmGainAudioProcessor {
        generation++
        val revision = generation
        processor?.setGainDb(0)
        routeState = PcmGainRoute.WAITING
        processedGainDb = null
        return SoftwarePcmGainAudioProcessor { route, appliedGain, appliedRevision ->
            dispatchToMain {
                if (revision == generation && (route != PcmGainRoute.PROCESSING || appliedRevision == requestedRevision)) {
                    routeState = route
                    processedGainDb = appliedGain
                    updateState()
                }
            }
        }.also {
            processor = it
            requestedRevision = it.setGainDb(gainDb)
            updateState()
        }
    }

    fun setGain(db: Int): VolumeBoostState {
        require(db in STEPS) { "Unsupported volume boost" }
        gainDb = db
        requestedRevision = processor?.setGainDb(db) ?: 0L
        // A paused player waits for a new buffer before claiming a changed gain is active.
        if (processedGainDb != db) processedGainDb = null
        updateState()
        return state
    }

    fun close() {
        generation++
        processor?.setGainDb(0)
        processor = null
        gainDb = 0
        processedGainDb = null
        routeState = PcmGainRoute.WAITING
        updateState()
    }

    /** End/error keeps the sink binding alive, so retry or replay can select boost again. */
    fun resetGain() { setGain(0) }

    private fun updateState() {
        val next = when {
            gainDb == 0 -> VolumeBoostState.OFF
            routeState == PcmGainRoute.BYPASSED -> VolumeBoostState.UNAVAILABLE
            routeState == PcmGainRoute.PROCESSING && processedGainDb == gainDb -> VolumeBoostState.REQUESTED
            else -> VolumeBoostState.WAITING_FOR_AUDIO
        }
        if (state != next) {
            state = next
            onStateChanged()
        }
    }

    companion object {
        val STEPS = listOf(0, 3, 6, 9, 12)
    }
}
