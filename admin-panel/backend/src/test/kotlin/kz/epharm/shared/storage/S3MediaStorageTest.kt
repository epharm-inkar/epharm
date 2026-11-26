package kz.epharm.shared.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class S3MediaStorageTest {
    @Test
    fun `certificate reads are restricted to managed image objects`() {
        val storage = S3MediaStorage(
            endpoint = "http://127.0.0.1:9000",
            publicUrl = "https://epharm.example.test/media",
            region = "us-east-1",
            accessKey = "test",
            secretKey = "test",
            bucket = "epharm-receipts",
        )
        val managed = "screens/123e4567-e89b-12d3-a456-426614174000.png"

        assertThat(storage.keyFromUrl("https://epharm.example.test/media/epharm-receipts/$managed"))
            .isEqualTo(managed)
        assertThat(storage.keyFromUrl("https://evil.example.test/epharm-receipts/$managed")).isNull()
        assertThat(storage.keyFromUrl("https://epharm.example.test/media/epharm-receipts/private/secret"))
            .isNull()
        assertThat(storage.keyFromUrl("https://epharm.example.test/media/epharm-receipts/$managed/../secret"))
            .isNull()
    }

    @Test
    fun `clip length reads are restricted to video objects of the public bucket`() {
        val storage = S3MediaStorage(
            endpoint = "http://127.0.0.1:9000",
            publicUrl = "https://epharm.example.test/media",
            region = "us-east-1",
            accessKey = "test",
            secretKey = "test",
            bucket = "epharm-receipts",
        )
        val base = "https://epharm.example.test/media/epharm-receipts"

        assertThat(storage.videoKeyFromUrl("$base/training/fire-safety/module-1.mp4")).isEqualTo("training/fire-safety/module-1.mp4")
        assertThat(storage.videoKeyFromUrl("$base/screens/123e4567-e89b-12d3-a456-426614174000.mp4"))
            .isEqualTo("screens/123e4567-e89b-12d3-a456-426614174000.mp4")
        assertThat(storage.videoKeyFromUrl("$base/screens/Clip.MOV")).isEqualTo("screens/Clip.MOV")

        // Another host, another bucket, another kind of file, or a key that climbs out of its folder: never asked.
        assertThat(storage.videoKeyFromUrl("https://evil.example.test/epharm-receipts/screens/a.mp4")).isNull()
        assertThat(storage.videoKeyFromUrl("https://epharm.example.test/media/other-bucket/screens/a.mp4")).isNull()
        assertThat(storage.videoKeyFromUrl("$base/screens/a.webm")).isNull()
        assertThat(storage.videoKeyFromUrl("$base/screens/a.png")).isNull()
        assertThat(storage.videoKeyFromUrl("$base/screens/../private/a.mp4")).isNull()
        assertThat(storage.videoKeyFromUrl("$base//etc/a.mp4")).isNull()
        assertThat(storage.videoKeyFromUrl("$base/screens/a%20b.mp4")).isNull()
        assertThat(storage.videoKeyFromUrl("$base/")).isNull()
        assertThat(storage.videoKeyFromUrl("https://www.youtube.com/watch?v=abc")).isNull()

        // None of these reaches the network: the request is refused before it is made.
        assertThat(storage.readRange("https://evil.example.test/epharm-receipts/screens/a.mp4", 0, 16)).isNull()
        assertThat(storage.readRange("$base/screens/a.mp4", -1, 16)).isNull()
        assertThat(storage.readRange("$base/screens/a.mp4", 0, 0)).isNull()
        assertThat(storage.readRange("$base/screens/a.mp4", 0, 10_000_000)).isNull()
    }
}
