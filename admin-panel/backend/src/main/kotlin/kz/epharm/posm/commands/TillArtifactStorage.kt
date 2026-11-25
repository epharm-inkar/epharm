package kz.epharm.posm.commands

import java.util.concurrent.ConcurrentHashMap
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Private storage of till diagnostics archives. Never the public media bucket: archives are read only through the
 * HQ download endpoint. Keys are produced by [TillCommandRules.archiveKey].
 */
interface TillArtifactStorage {
    /** Stores the archive; throws when the storage is unavailable. */
    fun put(key: String, bytes: ByteArray)

    /** The archive, or null when it is absent. */
    fun get(key: String): ByteArray?

    /** true when the archive is gone (deleted now or already absent); false when the storage could not be reached. */
    fun delete(key: String): Boolean
}

/** Test profile: archives in memory, no network. */
@Component
@Profile("test")
class InMemoryTillArtifactStorage : TillArtifactStorage {
    private val objects = ConcurrentHashMap<String, ByteArray>()

    override fun put(key: String, bytes: ByteArray) {
        objects[key] = bytes.copyOf()
    }

    override fun get(key: String): ByteArray? = objects[key]?.copyOf()

    override fun delete(key: String): Boolean {
        objects.remove(key)
        return true
    }

    fun keys(): Set<String> = objects.keys.toSet()

    /** Tests share one context: each test starts with an empty store. */
    fun clear() = objects.clear()
}
