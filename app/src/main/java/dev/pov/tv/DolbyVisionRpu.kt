package dev.pov.tv

/**
 * Dolby Vision Profile 7 → Profile 8.1, one RPU at a time, so a Profile 7 Blu-ray remux (dual layer in one track) plays
 * as Dolby Vision on devices whose DV decoder takes Profile 8 only (most Amlogic boxes). The enhancement layer is
 * dropped by the caller; this rewrites the metadata (RPU) the same way as dovi_tool's mode 2
 * (github.com/quietvoid/dovi_tool, MIT licence), which CoreELEC and others use:
 * - header: no enhancement-layer resampling, residual disabled (that is what makes it 8.1);
 * - mapping: the NLQ (enhancement layer) parameters are removed and the picture partitions set to one; for a full
 *   enhancement layer (FEL) the reshaping curves become identity, because the base layer is the HDR10 picture; for a
 *   minimal one (MEL) the curves are kept;
 * - display management: the YCbCr→RGB and RGB→LMS matrices get the standard Profile 8.1 values; every extension block
 *   (L1 brightness, L2/L8 trims, L5, L6…) is copied bit for bit.
 * The CRC is recomputed. Anything unexpected returns null, and the caller then drops the RPU (plain HDR10) rather than
 * send the decoder metadata that wasn't checked.
 *
 * Input and output are the RPU NAL payload after the two-byte NAL header (0x7C 0x01): rpu_nal_prefix 0x19 onwards, with
 * start-code emulation prevention bytes.
 */
internal object DolbyVisionRpu {
    /** Facts about one RPU, for tests and the log. */
    data class Info(val profile: Int, val fel: Boolean?, val identityMapping: Boolean)

    /** The Profile 8.1 RPU for a Profile 7 one; the same bytes for an RPU already Profile 8; null when it can't be done. */
    fun toProfile81(escaped: ByteArray, offset: Int = 0, length: Int = escaped.size - offset, keepFelMapping: Boolean = false): ByteArray? {
        val rpu = parse(unescape(escaped, offset, length)) ?: return null
        if (rpu.profile == 8) return escaped.copyOfRange(offset, offset + length)
        if (rpu.profile != 7) return null
        return try { escape(write(rpu, identity = rpu.fel == true && !keepFelMapping)) } catch (_: RuntimeException) { null }
    }

    fun info(escaped: ByteArray): Info? = parse(unescape(escaped, 0, escaped.size))?.let { rpu ->
        Info(rpu.profile, rpu.fel, rpu.mapping?.let { m -> m.pivots.all { it.size == 2 && it[0] == 0L && it[1] == 1023L } && m.identity } ?: false)
    }

    // ---------------------------------------------------------------- parsing

    private class Header(
        val rpuType: Int, val rpuFormat: Int, val vdrRpuProfile: Int, val vdrRpuLevel: Int, val seqInfo: Boolean,
        val chromaResamplingExplicit: Boolean, val coefficientDataType: Int, val coefficientLog2Denom: Long,
        val normalizedIdc: Int, val blFullRange: Boolean, val blBitDepthMinus8: Long, val elBitDepthMinus8Raw: Long,
        val vdrBitDepthMinus8: Long, val spatialResampling: Boolean, val reservedZero3: Int,
        val elSpatialResampling: Boolean, val disableResidual: Boolean, val dmPresent: Boolean, val usePrev: Boolean,
        val prevId: Long,
    ) {
        val denomBits: Int get() = if (coefficientDataType == 0) coefficientLog2Denom.toInt() else 32
        val blBits: Int get() = (blBitDepthMinus8 + 8).toInt()
        val elBits: Int get() = ((elBitDepthMinus8Raw and 0xFF) + 8).toInt()
        val profile: Int get() = when (vdrRpuProfile) {
            0 -> if (blFullRange) 5 else 0
            1 -> if (elSpatialResampling && !disableResidual) (if (vdrBitDepthMinus8 == 4L) 7 else 4) else 8
            else -> 0
        }
    }

    private class Mapping(
        val id: Long, val colorSpace: Long, val chromaFormat: Long, val pivots: List<LongArray>,
        /** The rpu_data_mapping_param bits (every curve's coefficients), copied when the curves are kept. */
        val paramBits: Bits, val identity: Boolean,
    )

    /** Display management: the three ids, the fixed block (null when compressed), the extension block sections. */
    private class Dm(val ids: LongArray, val fixed: LongArray?, val sections: List<List<Pair<Long, Bits>>>)

    private class Rpu(val header: Header, val mapping: Mapping?, val fel: Boolean?, val dm: Dm?, val remaining: Bits) {
        val profile get() = header.profile
    }

    /** The widths of the fixed display-management fields, in order (ycc→rgb, offsets, rgb→lms, signal, source). */
    private val DM_FIXED = intArrayOf(16, 16, 16, 16, 16, 16, 16, 16, 16, 32, 32, 32, 16, 16, 16, 16, 16, 16, 16, 16, 16,
        16, 16, 16, 32, 5, 2, 2, 2, 12, 12, 10)
    private const val SIGNAL_COLOR_SPACE = 26
    private val P81_MATRICES = longArrayOf(9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0, 16777216, 134217728, 134217728,
        7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962)

    private fun parse(data: ByteArray): Rpu? = try {
        var end = data.size
        while (end > 0 && data[end - 1] == 0.toByte()) end--
        require(end >= 7 && data[0] == 0x19.toByte() && data[end - 1] == 0x80.toByte())
        val crcAt = end - 5
        val received = ((data[crcAt].toLong() and 0xFF) shl 24) or ((data[crcAt + 1].toLong() and 0xFF) shl 16) or
            ((data[crcAt + 2].toLong() and 0xFF) shl 8) or (data[crcAt + 3].toLong() and 0xFF)
        require(crc32(data, 1, crcAt) == received) { "RPU CRC mismatch" }
        val reader = BitReader(data, crcAt * 8L)
        reader.bits(8)
        val header = readHeader(reader)
        require(header.rpuType == 2 && header.vdrRpuLevel == 0 && header.blBitDepthMinus8 == 2L)
        var fel: Boolean? = null
        val mapping = if (!header.usePrev) {
            val id = reader.ue(); val colorSpace = reader.ue(); val chroma = reader.ue()
            require(colorSpace == 0L && chroma == 0L)
            val pivots = List(3) {
                val count = reader.ue(); require(count < 8)
                LongArray((count + 2).toInt()) { reader.bits(header.blBits) }
            }
            val nlq = header.rpuFormat and 0x700 == 0 && !header.disableResidual
            if (nlq) { require(reader.bits(3) == 0L); reader.bits(header.blBits); reader.bits(header.blBits) }
            reader.ue(); reader.ue()                                          // picture partitions
            val paramStart = reader.position
            var identity = true
            for (cmp in 0 until 3) for (piece in 0 until pivots[cmp].size - 1) {
                when (reader.ue()) {
                    0L -> {
                        val order = reader.ue(); require(order <= 1)
                        if (order == 0L) require(!reader.bit()) { "linear interpolation is not supported" }
                        val coefficients = (0 until order.toInt() + 2).map {
                            (if (header.coefficientDataType == 0) reader.se() else 0L) to reader.bits(header.denomBits)
                        }
                        if (coefficients != listOf(0L to 0L, 1L to 0L)) identity = false
                    }
                    1L -> {
                        identity = false
                        val order = reader.bits(2); require(order <= 2)
                        if (header.coefficientDataType == 0) reader.se()
                        reader.bits(header.denomBits)
                        repeat(((order + 1) * 7).toInt()) { if (header.coefficientDataType == 0) reader.se(); reader.bits(header.denomBits) }
                    }
                    else -> throw IllegalArgumentException("mapping_idc")
                }
            }
            val paramBits = reader.copy(paramStart, reader.position)
            if (nlq) {
                var mel = header.coefficientDataType == 0
                repeat(3) {
                    if (reader.bits(header.elBits) != 0L) mel = false
                    if (header.coefficientDataType == 0 && reader.ue() != 1L) mel = false
                    if (reader.bits(header.denomBits) != 0L) mel = false
                    repeat(2) { if (header.coefficientDataType == 0 && reader.ue() != 0L) mel = false; if (reader.bits(header.denomBits) != 0L) mel = false }
                }
                fel = !mel
            }
            Mapping(id, colorSpace, chroma, pivots, paramBits, identity)
        } else null
        val dm = if (header.dmPresent) {
            val ids = longArrayOf(reader.ue(), reader.ue(), reader.ue())
            val fixed = if (header.reservedZero3 != 1) LongArray(DM_FIXED.size) { reader.bits(DM_FIXED[it]) } else null
            val sections = mutableListOf(section(reader))
            if (reader.remaining() >= 56 - 40) sections += section(reader)    // CM v4.0 (56 bits counted with the CRC)
            Dm(ids, fixed, sections)
        } else null
        while (reader.position % 8 != 0L) require(!reader.bit()) { "rpu_alignment_zero_bit" }
        val remaining = reader.copy(reader.position, crcAt * 8L)
        Rpu(header, mapping, fel, dm, remaining)
    } catch (_: RuntimeException) { null }

    private fun section(reader: BitReader): List<Pair<Long, Bits>> {
        val count = reader.ue(); require(count <= 255)
        while (reader.position % 8 != 0L) require(!reader.bit()) { "dm_alignment_zero_bit" }
        return List(count.toInt()) {
            val length = reader.ue(); require(length in 1..1024)
            val level = reader.bits(8)
            val start = reader.position
            reader.skip(length * 8)
            level to reader.copy(start - 8, reader.position).also { it.lengthBytes = length }
        }
    }

    private fun readHeader(reader: BitReader): Header {
        val rpuType = reader.bits(6).toInt(); val rpuFormat = reader.bits(11).toInt()
        val profile = reader.bits(4).toInt(); val level = reader.bits(4).toInt(); val seqInfo = reader.bit()
        var chroma = false; var dataType = 0; var denom = 0L; var normalized = 0; var fullRange = false
        var bl = 0L; var el = 0L; var vdr = 0L; var spatial = false; var reserved = 0; var elSpatial = false; var disable = false
        if (seqInfo) {
            chroma = reader.bit(); dataType = reader.bits(2).toInt()
            require(dataType == 0 || dataType == 1)
            if (dataType == 0) { denom = reader.ue(); require(denom <= 23) }
            normalized = reader.bits(2).toInt(); fullRange = reader.bit()
            if (rpuFormat and 0x700 == 0) {
                bl = reader.ue(); el = reader.ue(); vdr = reader.ue()
                spatial = reader.bit(); reserved = reader.bits(3).toInt(); elSpatial = reader.bit(); disable = reader.bit()
            }
        }
        val dm = reader.bit(); val usePrev = reader.bit()
        val prev = if (usePrev) reader.ue() else 0L
        return Header(rpuType, rpuFormat, profile, level, seqInfo, chroma, dataType, denom, normalized, fullRange, bl, el, vdr,
            spatial, reserved, elSpatial, disable, dm, usePrev, prev)
    }

    // ---------------------------------------------------------------- writing

    private fun write(rpu: Rpu, identity: Boolean): ByteArray {
        val h = rpu.header
        val out = BitWriter()
        out.bits(0x19, 8)
        out.bits(h.rpuType.toLong(), 6); out.bits(h.rpuFormat.toLong(), 11); out.bits(h.vdrRpuProfile.toLong(), 4)
        out.bits(h.vdrRpuLevel.toLong(), 4); out.bit(h.seqInfo)
        if (h.seqInfo) {
            out.bit(h.chromaResamplingExplicit); out.bits(h.coefficientDataType.toLong(), 2)
            if (h.coefficientDataType == 0) out.ue(h.coefficientLog2Denom)
            out.bits(h.normalizedIdc.toLong(), 2); out.bit(h.blFullRange)
            if (h.rpuFormat and 0x700 == 0) {
                out.ue(h.blBitDepthMinus8); out.ue(h.elBitDepthMinus8Raw); out.ue(h.vdrBitDepthMinus8)
                out.bit(h.spatialResampling); out.bits(h.reservedZero3.toLong(), 3)
                out.bit(false)                                                // el_spatial_resampling_filter_flag
                out.bit(true)                                                 // disable_residual_flag: Profile 8.1
            }
        }
        out.bit(h.dmPresent); out.bit(h.usePrev)
        if (h.usePrev) out.ue(h.prevId)
        rpu.mapping?.let { m ->
            out.ue(m.id); out.ue(m.colorSpace); out.ue(m.chromaFormat)
            if (identity) {
                repeat(3) { out.ue(0); out.bits(0, h.blBits); out.bits(1023, h.blBits) }
            } else m.pivots.forEach { pivots -> out.ue(pivots.size - 2L); pivots.forEach { out.bits(it, h.blBits) } }
            // No NLQ parameters any more (residual disabled); one partition.
            out.ue(0); out.ue(0)
            if (identity) repeat(3) {
                out.ue(0); out.ue(0); out.bit(false)                          // polynomial, first order, no interpolation
                listOf(0L, 1L).forEach { whole -> if (h.coefficientDataType == 0) out.se(whole); out.bits(0, h.denomBits) }
            } else out.copy(m.paramBits)
        }
        rpu.dm?.let { dm ->
            dm.ids.forEach(out::ue)
            dm.fixed?.let { fixed ->
                for (index in DM_FIXED.indices) {
                    val value = when {
                        index < P81_MATRICES.size -> P81_MATRICES[index]
                        index == SIGNAL_COLOR_SPACE -> 0L
                        else -> fixed[index]
                    }
                    out.bits(value, DM_FIXED[index])
                }
            }
            dm.sections.forEach { blocks ->
                out.ue(blocks.size.toLong()); out.align()
                blocks.forEach { (_, bits) -> out.ue(bits.lengthBytes); out.copy(bits) }
            }
        }
        out.copy(rpu.remaining)
        out.align()
        val body = out.toByteArray()
        val crc = crc32(body, 1, body.size)
        return body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte(), 0x80.toByte())
    }

    // ---------------------------------------------------------------- bits

    /** A run of bits copied out of an RPU (an extension block includes its level byte). */
    private class Bits(val data: ByteArray, val bitLength: Long) { var lengthBytes = 0L; fun isEmpty() = bitLength == 0L }

    private class BitReader(private val data: ByteArray, private val limit: Long) {
        var position = 0L
        fun remaining() = limit - position
        fun bit(): Boolean {
            require(position < limit) { "RPU ended early" }
            val value = (data[(position ushr 3).toInt()].toInt() shr (7 - (position and 7).toInt())) and 1
            position++
            return value == 1
        }
        fun bits(count: Int): Long { var value = 0L; repeat(count) { value = (value shl 1) or (if (bit()) 1L else 0L) }; return value }
        fun skip(count: Long) { require(position + count <= limit) { "RPU ended early" }; position += count }
        fun ue(): Long {
            var zeros = 0
            while (!bit()) { zeros++; require(zeros < 32) { "bad exp-Golomb" } }
            return (1L shl zeros) - 1 + bits(zeros)
        }
        fun se(): Long { val code = ue(); return if (code and 1L == 1L) (code + 1) / 2 else -(code / 2) }
        fun copy(from: Long, to: Long): Bits {
            val saved = position; position = from
            val writer = BitWriter()
            for (i in from until to) writer.bit(bit())
            position = saved
            return Bits(writer.toByteArray(), to - from)
        }
    }

    private class BitWriter {
        private var bytes = ByteArray(512)
        private var length = 0L
        fun bit(value: Boolean) {
            val index = (length ushr 3).toInt()
            if (index >= bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            if (value) bytes[index] = (bytes[index].toInt() or (1 shl (7 - (length and 7).toInt()))).toByte()
            length++
        }
        /** The low [count] bits of [value] (two's complement for negative matrix entries). */
        fun bits(value: Long, count: Int) { for (i in count - 1 downTo 0) bit((value ushr i) and 1L == 1L) }
        fun ue(value: Long) {
            val code = value + 1
            val size = 64 - java.lang.Long.numberOfLeadingZeros(code)
            repeat(size - 1) { bit(false) }
            bits(code, size)
        }
        fun ue(value: Int) = ue(value.toLong())
        fun se(value: Long) = ue(if (value > 0) value * 2 - 1 else -value * 2)
        fun align() { while (length % 8 != 0L) bit(false) }
        fun copy(source: Bits) {
            for (i in 0 until source.bitLength) bit((source.data[(i ushr 3).toInt()].toInt() shr (7 - (i and 7).toInt())) and 1 == 1)
        }
        fun toByteArray(): ByteArray = bytes.copyOf(((length + 7) ushr 3).toInt())
    }

    /** CRC-32/MPEG-2 (polynomial 0x04C11DB7, no reflection), as the RPU's rpu_data_crc32. */
    private fun crc32(data: ByteArray, from: Int, to: Int): Long {
        var crc = 0xFFFFFFFFL
        for (i in from until to) {
            crc = crc xor ((data[i].toLong() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and 0x80000000L != 0L) ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL else (crc shl 1) and 0xFFFFFFFFL }
        }
        return crc
    }

    /** Removes the 0x03 after each 0x00 0x00 (start-code emulation prevention). */
    private fun unescape(data: ByteArray, offset: Int, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(length)
        var zeros = 0
        for (i in offset until offset + length) {
            val byte = data[i].toInt() and 0xFF
            if (zeros >= 2 && byte == 3) { zeros = 0; continue }
            out.write(byte)
            zeros = if (byte == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Puts the 0x03 back after each 0x00 0x00 that a byte of 0–3 follows (the same rule dovi_tool writes). */
    private fun escape(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        var zeros = 0
        for (b in data) {
            val byte = b.toInt() and 0xFF
            if (zeros >= 2 && byte <= 3) { out.write(3); zeros = 0 }
            out.write(byte)
            zeros = if (byte == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }
}
