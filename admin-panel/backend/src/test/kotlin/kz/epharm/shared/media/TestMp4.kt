package kz.epharm.shared.media

import java.nio.ByteBuffer

/** Small MP4 files for tests: just the boxes the length is read from, with a movie box that has a 1000 ticks per second clock. */
object TestMp4 {
    /** A clip of [seconds] whose movie box sits at the end of the file, as in a recording that was never prepared for streaming. */
    fun clip(seconds: Int): ByteArray {
        val ftyp = ByteBuffer.allocate(28).putInt(28).put("ftyp".toByteArray()).put("isom".toByteArray())
            .putInt(0).put("isomiso2mp41".toByteArray()).array()
        val mdat = ByteBuffer.allocate(4_008).putInt(4_008).put("mdat".toByteArray()).put(ByteArray(4_000)).array()
        return ftyp + mdat + movie(seconds * 1000)
    }

    /** A movie box alone, [ticks] long on a 1000 ticks per second clock. */
    fun movie(ticks: Int): ByteArray {
        val mvhd = ByteBuffer.allocate(108).putInt(108).put("mvhd".toByteArray())
            .putInt(0).putInt(0).putInt(0).putInt(1000).putInt(ticks).put(ByteArray(80)).array()
        return ByteBuffer.allocate(8 + mvhd.size).putInt(8 + mvhd.size).put("moov".toByteArray()).put(mvhd).array()
    }
}
