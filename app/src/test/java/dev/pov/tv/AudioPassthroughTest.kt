package dev.pov.tv

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPassthroughTest {
    @Test fun automaticPassesEverythingThrough() {
        listOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC3)
            .forEach { assertFalse(it, AudioPassthrough.decodes(AudioPassthrough.AUTO, it)) }
    }

    @Test fun decodeLosslessOnlyDecodesLosslessFormats() {
        listOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X)
            .forEach { assertTrue(it, AudioPassthrough.decodes(AudioPassthrough.DECODE_LOSSLESS, it)) }
        listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS)
            .forEach { assertFalse(it, AudioPassthrough.decodes(AudioPassthrough.DECODE_LOSSLESS, it)) }
    }

    @Test fun decodeAllDecodesEveryEncodedFormatButNeverPcm() {
        listOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS)
            .forEach { assertTrue(it, AudioPassthrough.decodes(AudioPassthrough.DECODE_ALL, it)) }
        assertFalse(AudioPassthrough.decodes(AudioPassthrough.DECODE_ALL, MimeTypes.AUDIO_RAW))
        assertFalse(AudioPassthrough.decodes(AudioPassthrough.DECODE_ALL, null))
    }
}
