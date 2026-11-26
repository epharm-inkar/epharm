package kz.epharm.shared.media

import kz.epharm.shared.storage.MediaStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VideoClipLengthsTest {
    private var clock = 1_000_000L
    private val url = "https://epharm.example.test/s3/epharm-receipts/training/module-1.mp4"

    private fun clip(seconds: Int): ByteArray = TestMp4.clip(seconds)

    private fun movie(ticks: Int): ByteArray = TestMp4.movie(ticks)

    /** One object under [url] that can be replaced; any other address is not ours. Counts how often it is asked. */
    private class FakeStorage(var bytes: ByteArray?, private val ours: String) : MediaStorage {
        var reads = 0

        override fun upload(bytes: ByteArray, contentType: String, originalName: String): String = error("unused")
        override fun read(url: String): ByteArray? = null
        override fun delete(url: String) = Unit

        override fun readRange(url: String, offset: Long, length: Int): ByteArray? {
            reads++
            val all = bytes?.takeIf { url == ours } ?: return null
            if (offset < 0 || length <= 0 || offset >= all.size) return null
            return all.copyOfRange(offset.toInt(), minOf(all.size.toLong(), offset + length).toInt())
        }
    }

    private fun lengths(storage: MediaStorage) = VideoClipLengths(storage) { clock }

    @Test
    fun `the length comes from the file, whatever the planned minutes are`() {
        assertThat(lengths(FakeStorage(clip(6), url)).lengthSeconds(url)).isEqualTo(6)
        assertThat(lengths(FakeStorage(clip(95), url)).lengthSeconds(url)).isEqualTo(95)
    }

    @Test
    fun `a clip is measured once and remembered, then measured again after a while`() {
        val storage = FakeStorage(clip(90), url)
        val service = lengths(storage)

        assertThat(service.lengthSeconds(url)).isEqualTo(90)
        val firstReads = storage.reads
        assertThat(firstReads).isGreaterThan(0)
        assertThat(service.lengthSeconds(url)).isEqualTo(90)
        assertThat(storage.reads).isEqualTo(firstReads)

        // The file is replaced under the same address: the old length is kept for a while, then the new one is read.
        storage.bytes = clip(45)
        clock += 29 * 60 * 1000L
        assertThat(service.lengthSeconds(url)).isEqualTo(90)
        assertThat(storage.reads).isEqualTo(firstReads)
        clock += 2 * 60 * 1000L
        assertThat(service.lengthSeconds(url)).isEqualTo(45)
    }

    @Test
    fun `a length with a fraction is rounded up`() {
        assertThat(lengths(FakeStorage(movie(5_400), url)).lengthSeconds(url)).isEqualTo(6)
    }

    @Test
    fun `a file that cannot be measured gives nothing and is asked about again soon, not on every save`() {
        val storage = FakeStorage("not a video at all, just some text".toByteArray(), url)
        val service = lengths(storage)

        assertThat(service.lengthSeconds(url)).isNull()
        val firstReads = storage.reads
        assertThat(service.lengthSeconds(url)).isNull()
        assertThat(storage.reads).isEqualTo(firstReads)

        // It was damaged, then somebody uploaded a proper one: the miss is not remembered for long.
        storage.bytes = clip(30)
        clock += 3 * 60 * 1000L
        assertThat(service.lengthSeconds(url)).isEqualTo(30)
    }

    @Test
    fun `an address the storage does not hold, and an empty one, give nothing`() {
        val service = lengths(FakeStorage(clip(10), url))

        assertThat(service.lengthSeconds("https://www.youtube.com/watch?v=abc")).isNull()
        assertThat(service.lengthSeconds("   ")).isNull()
        assertThat(service.lengthSeconds(null)).isNull()
    }

    @Test
    fun `a failing storage never breaks the caller`() {
        val failing = object : MediaStorage {
            override fun upload(bytes: ByteArray, contentType: String, originalName: String): String = error("unused")
            override fun read(url: String): ByteArray? = null
            override fun delete(url: String) = Unit
            override fun readRange(url: String, offset: Long, length: Int): ByteArray? = throw IllegalStateException("storage is down")
        }

        assertThat(lengths(failing).lengthSeconds(url)).isNull()
    }

    @Test
    fun `a storage that only implements the old methods reads nothing`() {
        val oldStorage = object : MediaStorage {
            override fun upload(bytes: ByteArray, contentType: String, originalName: String): String = error("unused")
            override fun read(url: String): ByteArray? = null
            override fun delete(url: String) = Unit
        }

        assertThat(oldStorage.readRange(url, 0, 16)).isNull()
        assertThat(lengths(oldStorage).lengthSeconds(url)).isNull()
    }
}
