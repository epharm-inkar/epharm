package kz.epharm.shared.media

/**
 * Random access to the bytes of a stored file, so that a header can be read without downloading the file.
 * Returns up to [length] bytes from [offset], fewer near the end of the file, null when nothing can be read.
 */
fun interface ByteRangeSource {
    fun read(offset: Long, length: Int): ByteArray?
}

/**
 * Length of an MP4 clip, read from its movie header (`moov` > `mvhd`: timescale and duration).
 *
 * The file is walked box by box with small ranged reads, so a clip whose `moov` sits at the very end of a
 * large file (the usual layout of a recording that was not made for streaming) costs a handful of reads of
 * a few dozen bytes, not a download. Both the 32-bit (version 0) and the 64-bit (version 1) `mvhd` are
 * understood, and so are boxes with a 64-bit size. A fragmented clip with no length in the header, a file
 * of another format or a damaged one gives null: the caller has to fall back to something else.
 */
object Mp4Duration {
    private const val READ_BYTES = 48
    private const val MAX_TOP_LEVEL_BOXES = 64
    private const val MAX_MOVIE_BOXES = 64
    private const val MAX_SECONDS = 24.0 * 60 * 60
    private const val UNKNOWN_32 = 0xFFFF_FFFFL

    private const val MOOV = "moov"
    private const val MVHD = "mvhd"

    private class Box(
        val type: String,
        val headerLength: Int,
        val contentStart: Long,
        /** Offset just past the box, null when the box runs to the end of the file. */
        val end: Long?,
        /** The first bytes of the box, header included. */
        val head: ByteArray,
    )

    fun seconds(bytes: ByteArray): Double? = seconds { offset, length ->
        if (offset < 0 || offset >= bytes.size || length <= 0) null
        else bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
    }

    fun seconds(source: ByteRangeSource): Double? {
        var offset = 0L
        repeat(MAX_TOP_LEVEL_BOXES) {
            val box = readBox(source, offset) ?: return null
            if (box.type == MOOV) return movieSeconds(source, box)
            // A box that runs to the end of the file leaves no room for a moov after it.
            offset = box.end ?: return null
        }
        return null
    }

    private fun movieSeconds(source: ByteRangeSource, moov: Box): Double? {
        var offset = moov.contentStart
        repeat(MAX_MOVIE_BOXES) {
            if (moov.end != null && offset >= moov.end) return null
            val box = readBox(source, offset) ?: return null
            if (box.type == MVHD) return headerSeconds(box)
            offset = box.end ?: return null
        }
        return null
    }

    private fun headerSeconds(mvhd: Box): Double? {
        val head = mvhd.head
        val v = mvhd.headerLength
        if (head.size < v + 4) return null
        val timescale: Long
        val duration: Long
        when (head[v].toInt() and 0xFF) {
            0 -> {
                if (head.size < v + 20) return null
                timescale = u32(head, v + 12)
                duration = u32(head, v + 16)
                if (duration == UNKNOWN_32) return null
            }
            1 -> {
                if (head.size < v + 32) return null
                timescale = u32(head, v + 20)
                duration = u64(head, v + 24)
            }
            else -> return null
        }
        // All ones is how a header says the length is not known yet; a value past 2^63 reads as negative.
        if (timescale <= 0 || duration <= 0 || duration == -1L) return null
        val seconds = duration.toDouble() / timescale.toDouble()
        return seconds.takeIf { it.isFinite() && it > 0 && it <= MAX_SECONDS }
    }

    private fun readBox(source: ByteRangeSource, offset: Long): Box? {
        val head = source.read(offset, READ_BYTES) ?: return null
        if (head.size < 8) return null
        val size = u32(head, 0)
        val type = String(head, 4, 4, Charsets.ISO_8859_1)
        return when {
            size == 0L -> Box(type, 8, offset + 8, null, head)
            size == 1L -> {
                if (head.size < 16) return null
                val large = u64(head, 8)
                // Negative: 2^63 or more. Shorter than its own header, or past the range of a long: damaged.
                if (large < 16 || large > Long.MAX_VALUE - offset) return null
                Box(type, 16, offset + 16, offset + large, head)
            }
            size < 8 -> null
            else -> Box(type, 8, offset + 8, offset + size, head)
        }
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

    private fun u64(bytes: ByteArray, at: Int): Long = (u32(bytes, at) shl 32) or u32(bytes, at + 4)
}
