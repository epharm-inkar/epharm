package kz.epharm.shared.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Fixtures are built from boxes byte by byte, so every layout the reader has to understand is spelled out. */
class Mp4DurationTest {
    @Test
    fun `reads a version 0 header with the movie box in front of the media`() {
        val file = box("ftyp", ftyp()) + box("moov", box("mvhd", mvhd0(timescale = 1000, duration = 6000))) +
            box("mdat", ByteArray(2_000))
        assertThat(Mp4Duration.seconds(file)).isEqualTo(6.0)
    }

    @Test
    fun `reads a movie box that sits at the very end of the file`() {
        val file = box("ftyp", ftyp()) + box("free", ByteArray(40)) + box("mdat", ByteArray(5_000)) +
            box("moov", box("mvhd", mvhd0(timescale = 600, duration = 54_000)))
        assertThat(Mp4Duration.seconds(file)).isEqualTo(90.0)
    }

    @Test
    fun `reads a version 1 header whose duration does not fit in 32 bits`() {
        // 5 000 000 000 ticks of a microsecond clock: 5000 s, and more than an unsigned 32-bit value holds.
        val file = box("ftyp", ftyp()) + box("moov", box("mvhd", mvhd1(timescale = 1_000_000, duration = 5_000_000_000L)))
        assertThat(Mp4Duration.seconds(file)).isEqualTo(5000.0)
    }

    @Test
    fun `follows a media box with a 64 bit size and a version 1 header at the end`() {
        val file = box("ftyp", ftyp()) + largeBox("mdat", ByteArray(3_000)) +
            box("moov", box("mvhd", mvhd1(timescale = 90_000, duration = 5_400_000)))
        assertThat(Mp4Duration.seconds(file)).isEqualTo(60.0)
    }

    @Test
    fun `finds the header when other boxes come before it in the movie box`() {
        val movie = box("iods", ByteArray(12)) + box("udta", ByteArray(30)) + box("mvhd", mvhd0(timescale = 1000, duration = 48_500))
        assertThat(Mp4Duration.seconds(box("ftyp", ftyp()) + box("moov", movie))).isEqualTo(48.5)
    }

    @Test
    fun `a movie box with a 64 bit size is read as well`() {
        val movie = box("mvhd", mvhd0(timescale = 1000, duration = 12_000))
        val file = box("ftyp", ftyp()) + largeBox("moov", movie)
        assertThat(Mp4Duration.seconds(file)).isEqualTo(12.0)
    }

    @Test
    fun `a movie box with no length in the header gives nothing`() {
        // A fragmented or live clip: duration all ones, or zero.
        assertThat(Mp4Duration.seconds(box("moov", box("mvhd", mvhd0(1000, 0xFFFF_FFFFL))))).isNull()
        assertThat(Mp4Duration.seconds(box("moov", box("mvhd", mvhd1(1000, -1L))))).isNull()
        assertThat(Mp4Duration.seconds(box("moov", box("mvhd", mvhd0(1000, 0))))).isNull()
        assertThat(Mp4Duration.seconds(box("moov", box("mvhd", mvhd0(0, 5000))))).isNull()
    }

    @Test
    fun `an absurd length is not believed`() {
        assertThat(Mp4Duration.seconds(box("moov", box("mvhd", mvhd1(1, 100_000))))).isNull()
        assertThat(Mp4Duration.seconds(box("moov", box("mvhd", mvhd1(1000, Long.MAX_VALUE))))).isNull()
    }

    @Test
    fun `files that are not an MP4 or are damaged give nothing and never throw`() {
        assertThat(Mp4Duration.seconds(ByteArray(0))).isNull()
        assertThat(Mp4Duration.seconds(ByteArray(7))).isNull()
        assertThat(Mp4Duration.seconds("RIFF....WEBPVP8 ".toByteArray())).isNull()
        // No movie box at all.
        assertThat(Mp4Duration.seconds(box("ftyp", ftyp()) + box("mdat", ByteArray(100)))).isNull()
        // A box that claims to be shorter than its own header.
        assertThat(Mp4Duration.seconds(byteArrayOf(0, 0, 0, 4, 'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte()))).isNull()
        // The file ends inside the movie box.
        assertThat(Mp4Duration.seconds(box("moov", box("mvhd", mvhd0(1000, 6000))).copyOf(20))).isNull()
        // A media box that runs to the end of the file leaves no room for a movie box after it.
        assertThat(Mp4Duration.seconds(sized(0, "mdat", ByteArray(50)))).isNull()
        // A version this reader does not know.
        assertThat(Mp4Duration.seconds(box("moov", box("mvhd", byteArrayOf(7) + ByteArray(31))))).isNull()
    }

    @Test
    fun `a movie box at the end of a huge file is found with a handful of small reads`() {
        // 600 MB of media in front: the reader must jump over it, never fetch it.
        val head = box("ftyp", ftyp())
        val mediaSize = 600L * 1024 * 1024
        val movie = box("moov", box("mvhd", mvhd0(timescale = 1000, duration = 7_000)))
        val mediaHeader = ByteBuffer.allocate(16).putInt(1).put("mdat".toByteArray()).putLong(mediaSize).array()
        val movieAt = head.size + mediaSize
        val reads = mutableListOf<Pair<Long, Int>>()
        val source = ByteRangeSource { offset, length ->
            reads += offset to length
            when {
                offset < head.size -> head.copyOfRange(offset.toInt(), head.size)
                offset == head.size.toLong() -> mediaHeader.copyOf(minOf(length, mediaHeader.size))
                offset == movieAt -> movie.copyOf(minOf(length, movie.size))
                offset == movieAt + 8 -> movie.copyOfRange(8, minOf(movie.size, 8 + length))
                else -> null
            }
        }

        assertThat(Mp4Duration.seconds(source)).isEqualTo(7.0)
        assertThat(reads).hasSizeLessThanOrEqualTo(5)
        assertThat(reads.map { it.second }.max()).isLessThanOrEqualTo(64)
    }

    @Test
    fun `a source that cannot be read gives nothing`() {
        assertThat(Mp4Duration.seconds(ByteRangeSource { _, _ -> null })).isNull()
    }

    private fun ftyp(): ByteArray = "isom".toByteArray() + ByteArray(4) + "isomiso2mp41".toByteArray()

    private fun box(type: String, content: ByteArray): ByteArray = sized(content.size + 8, type, content)

    private fun sized(size: Int, type: String, content: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + content.size).putInt(size).put(type.toByteArray()).put(content).array()

    private fun largeBox(type: String, content: ByteArray): ByteArray =
        ByteBuffer.allocate(16 + content.size).putInt(1).put(type.toByteArray()).putLong(16L + content.size).put(content).array()

    /** version 0: creation, modification, timescale, duration as 32 bits, then rate, volume, matrix and the rest. */
    private fun mvhd0(timescale: Long, duration: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(ByteBuffer.allocate(16).putInt(0).putInt(0).putInt(timescale.toInt()).putInt(duration.toInt()).array())
        out.write(ByteArray(80))
        return out.toByteArray()
    }

    /** version 1: creation, modification and duration as 64 bits. */
    private fun mvhd1(timescale: Long, duration: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 0, 0))
        out.write(ByteBuffer.allocate(28).putLong(0).putLong(0).putInt(timescale.toInt()).putLong(duration).array())
        out.write(ByteArray(80))
        return out.toByteArray()
    }
}
