package kz.epharm.posm.attill

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.posm.service.PosmDeviceIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant

/** Who each till last named: the in-memory copy, Redis next to the device presence, and the durable «last seen». */
class TillPersonPresenceTest {
    private val t0 = Instant.parse("2026-11-26T09:00:00Z")
    private fun at(minutes: Long): Instant = t0.plus(Duration.ofMinutes(minutes))

    private fun till(device: String = "KASSA1", pharmacy: String = "abay") = PosmDeviceIdentity(device, pharmacy, legacy = false)

    @Test fun `a till names one person and a colleague who signs in takes it over`() {
        val presence = TillPersonPresence(windowMinutes = 20)
        presence.record(till(), "ph_a", t0)
        presence.record(till(), "ph_b", at(5))
        val people = presence.latest(at(6))
        assertEquals(1, people.size)
        assertEquals("ph_b", people.single().pharmacistId)
        assertEquals(at(5), people.single().since)
    }

    @Test fun `each till has its own person, and one computer name in two pharmacies is two tills`() {
        val presence = TillPersonPresence(windowMinutes = 20)
        presence.record(till("KASSA1", "abay"), "ph_a", t0)
        presence.record(till("KASSA1", "tole"), "ph_b", t0)
        presence.record(till("KASSA2", "abay"), "ph_c", t0)
        assertEquals(setOf("ph_a", "ph_b", "ph_c"), presence.latest(at(1)).map { it.pharmacistId }.toSet())
    }

    @Test fun `the same till spelled in another case is the same till`() {
        val presence = TillPersonPresence(windowMinutes = 20)
        presence.record(till("KASSA1"), "ph_a", t0)
        presence.record(till("kassa1"), "ph_b", at(1))
        assertEquals(listOf("ph_b"), presence.latest(at(2)).map { it.pharmacistId })
    }

    @Test fun `a person keeps since across the checks of one shift`() {
        val presence = TillPersonPresence(windowMinutes = 20)
        presence.record(till(), "ph_a", t0)
        presence.record(till(), "ph_a", at(15))
        presence.record(till(), "ph_a", at(30))
        val person = presence.latest(at(31)).single()
        assertEquals(t0, person.since)
        assertEquals(at(30), person.seenAt)
    }

    @Test fun `a till without its own key has no identity and is not recorded`() {
        val presence = TillPersonPresence(windowMinutes = 20)
        presence.record(PosmDeviceIdentity(null, "abay", legacy = true), "ph_a", t0)
        presence.record(PosmDeviceIdentity("KASSA1", null, legacy = false), "ph_a", t0)
        presence.record(PosmDeviceIdentity("  ", "abay", legacy = false), "ph_a", t0)
        assertTrue(presence.latest(at(1)).isEmpty())
    }

    @Test fun `records older than two days are dropped on reading`() {
        val presence = TillPersonPresence(windowMinutes = 20)
        presence.record(till(), "ph_a", t0)
        assertEquals(1, presence.latest(t0.plus(Duration.ofHours(47))).size)
        assertTrue(presence.latest(t0.plus(Duration.ofHours(49))).isEmpty())
    }

    @Test fun `forgetting clears everything`() {
        val presence = TillPersonPresence(windowMinutes = 20)
        presence.record(till(), "ph_a", t0)
        presence.forgetAll()
        assertTrue(presence.latest(at(1)).isEmpty())
    }

    @Test fun `the window is the setting, kept within its bounds`() {
        assertEquals(Duration.ofMinutes(20), TillPersonPresence(windowMinutes = 20).window)
        assertEquals(Duration.ofMinutes(30), TillPersonPresence(windowMinutes = 30).window)
        assertEquals(Duration.ofMinutes(5), TillPersonPresence(windowMinutes = 0).window)
    }

    @Test fun `redis keeps the person next to the device presence and restores it after a restart`() {
        val provider = mockk<ObjectProvider<StringRedisTemplate>>()
        val redis = mockk<StringRedisTemplate>()
        val hash = mockk<HashOperations<String, String, String>>()
        every { provider.getIfAvailable() } returns redis
        every { redis.opsForHash<String, String>() } returns hash
        every { hash.get(any(), any()) } returns null
        every { hash.put(any(), any(), any()) } returns Unit

        TillPersonPresence(windowMinutes = 20, redisProvider = provider).record(till(), "ph_a", t0)
        val key = "abay\u001Fkassa1"
        val stored = AtTillRules.encode(TillPerson("ph_a", "abay", "KASSA1", t0, t0))
        verify { hash.put("epharm:posm:presence:person", key, stored) }

        every { hash.entries("epharm:posm:presence:person") } returns mapOf(key to stored)
        every { hash.delete(any(), *anyVararg()) } returns 0L
        val afterRestart = TillPersonPresence(windowMinutes = 20, redisProvider = provider).latest(at(5))
        assertEquals(listOf(TillPerson("ph_a", "abay", "KASSA1", t0, t0)), afterRestart)
    }

    @Test fun `the person already in redis decides since, so another server instance continues the stretch`() {
        val provider = mockk<ObjectProvider<StringRedisTemplate>>()
        val redis = mockk<StringRedisTemplate>()
        val hash = mockk<HashOperations<String, String, String>>()
        every { provider.getIfAvailable() } returns redis
        every { redis.opsForHash<String, String>() } returns hash
        every { hash.put(any(), any(), any()) } returns Unit
        val key = "abay\u001Fkassa1"
        every { hash.get("epharm:posm:presence:person", key) } returns AtTillRules.encode(TillPerson("ph_a", "abay", "KASSA1", t0, at(10)))

        TillPersonPresence(windowMinutes = 20, redisProvider = provider).record(till(), "ph_a", at(15))
        verify { hash.put("epharm:posm:presence:person", key, AtTillRules.encode(TillPerson("ph_a", "abay", "KASSA1", t0, at(15)))) }
    }

    @Test fun `stale and damaged redis records are removed when read`() {
        val provider = mockk<ObjectProvider<StringRedisTemplate>>()
        val redis = mockk<StringRedisTemplate>()
        val hash = mockk<HashOperations<String, String, String>>()
        every { provider.getIfAvailable() } returns redis
        every { redis.opsForHash<String, String>() } returns hash
        val fresh = AtTillRules.encode(TillPerson("ph_a", "abay", "KASSA1", t0, t0))
        val old = AtTillRules.encode(TillPerson("ph_b", "abay", "KASSA2", t0.minus(Duration.ofDays(3)), t0.minus(Duration.ofDays(3))))
        every { hash.entries("epharm:posm:presence:person") } returns mapOf("abay\u001Fkassa1" to fresh, "abay\u001Fkassa2" to old, "abay\u001Fkassa3" to "garbage")
        every { hash.delete(any(), *anyVararg()) } returns 2L

        val people = TillPersonPresence(windowMinutes = 20, redisProvider = provider).latest(at(1))
        assertEquals(listOf("ph_a"), people.map { it.pharmacistId })
        verify { hash.delete("epharm:posm:presence:person", "abay\u001Fkassa2", "abay\u001Fkassa3") }
    }

    @Test fun `a redis failure never fails the request and the in-memory copy stands in`() {
        val provider = mockk<ObjectProvider<StringRedisTemplate>>()
        every { provider.getIfAvailable() } throws IllegalStateException("redis is down")
        val presence = TillPersonPresence(windowMinutes = 20, redisProvider = provider)
        presence.record(till(), "ph_a", t0)
        assertEquals(listOf("ph_a"), presence.latest(at(1)).map { it.pharmacistId })

        val broken = mockk<StringRedisTemplate>()
        val working = mockk<ObjectProvider<StringRedisTemplate>>()
        every { working.getIfAvailable() } returns broken
        every { broken.opsForHash<String, String>() } throws IllegalStateException("connection reset")
        val second = TillPersonPresence(windowMinutes = 20, redisProvider = working)
        second.record(till(), "ph_b", t0)
        assertEquals(listOf("ph_b"), second.latest(at(1)).map { it.pharmacistId })
    }

    @Test fun `the last seen is written to the database at most once in five minutes per pharmacist`() {
        val jdbc = mockk<JdbcTemplate>()
        every { jdbc.update(any<String>(), *anyVararg()) } returns 1
        val presence = TillPersonPresence(windowMinutes = 20, jdbc = jdbc)
        presence.record(till(), "ph_a", t0)
        presence.record(till(), "ph_a", at(4))
        verify(exactly = 1) { jdbc.update(any<String>(), *anyVararg()) }
        presence.record(till(), "ph_a", at(6))
        verify(exactly = 2) { jdbc.update(any<String>(), *anyVararg()) }
        // Another pharmacist is written at once.
        presence.record(till("KASSA2"), "ph_b", at(6))
        verify(exactly = 3) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @Test fun `a failed database write never fails the request and is not repeated on every request`() {
        val jdbc = mockk<JdbcTemplate>()
        every { jdbc.update(any<String>(), *anyVararg()) } throws org.springframework.dao.DataIntegrityViolationException("no such pharmacist")
        val presence = TillPersonPresence(windowMinutes = 20, jdbc = jdbc)
        presence.record(till(), "ph_gone", t0)
        presence.record(till(), "ph_gone", at(1))
        assertEquals(listOf("ph_gone"), presence.latest(at(2)).map { it.pharmacistId })
        verify(exactly = 1) { jdbc.update(any<String>(), *anyVararg()) }
    }
}
