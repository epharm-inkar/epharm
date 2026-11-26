package kz.epharm.shared.media

import kz.epharm.shared.storage.MediaStorage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * The real length of a lesson clip, known to the server itself.
 *
 * Whatever the browser says about a clip can be made up by the one who watches it, so the watch guard of a
 * video lesson must not rest on it. The length is read once from the MP4 file in the object storage the
 * backend already uses ([MediaStorage.readRange], a few small ranged reads) and kept per URL for a while:
 * long enough to spare the storage, short enough to notice a file that was replaced under the same URL.
 * Null means the length could not be read (a clip of another format, an address outside our storage, the
 * storage not answering); the caller keeps its former rule. A miss is remembered for a short time only, so
 * that a storage hiccup heals quickly and a broken address is not asked about on every save.
 */
@Component
class VideoClipLengths internal constructor(
    private val storage: MediaStorage,
    private val now: () -> Long,
) {
    @Autowired
    constructor(storage: MediaStorage) : this(storage, System::currentTimeMillis)

    private val log = LoggerFactory.getLogger(VideoClipLengths::class.java)
    private val cache = ConcurrentHashMap<String, Entry>()

    private class Entry(val seconds: Long?, val expiresAt: Long)

    /** Length of the clip at [url] in whole seconds, rounded up; null when it is not known. */
    fun lengthSeconds(url: String?): Long? {
        val key = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val at = now()
        cache[key]?.takeIf { it.expiresAt > at }?.let { return it.seconds }
        val seconds = measure(key, at)
        if (cache.size >= MAX_ENTRIES) cache.entries.removeIf { it.value.expiresAt <= at }
        if (cache.size >= MAX_ENTRIES) cache.clear()
        cache[key] = Entry(seconds, at + if (seconds != null) FOUND_TTL_MS else MISSED_TTL_MS)
        return seconds
    }

    private fun measure(url: String, startedAt: Long): Long? {
        val deadline = startedAt + READ_BUDGET_MS
        val source = ByteRangeSource { offset, length ->
            if (now() > deadline) null else storage.readRange(url, offset, length)
        }
        val seconds = runCatching { Mp4Duration.seconds(source) }
            .onFailure { log.warn("Не удалось определить длину видео {}: {}", url, it.message) }
            .getOrNull()
        if (seconds == null) log.info("Длина видео {} не определена по файлу, урок считается по плановым минутам", url)
        return seconds?.let { ceil(it).toLong().coerceAtLeast(1) }
    }

    private companion object {
        const val FOUND_TTL_MS = 30 * 60 * 1000L
        const val MISSED_TTL_MS = 2 * 60 * 1000L
        const val READ_BUDGET_MS = 6_000L
        const val MAX_ENTRIES = 512
    }
}
