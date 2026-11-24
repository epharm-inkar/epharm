package kz.epharm.appupdate.service

/**
 * Tills that receive pilot POSM releases (POSM_UPDATE_PILOT_DEVICES). Entries are separated by commas:
 * `pharmacyId|deviceId` admits one till, `pharmacyId|*` every till of that pharmacy. Device names such as
 * KASSA1 repeat across pharmacies, so a bare device id is not accepted. Empty admits nobody; the retired
 * fleet key never takes part in a pilot.
 */
class PosmUpdatePilotScope(raw: String) {
    private val entries: Set<Pair<String, String>> = raw.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { entry ->
            val parts = entry.split('|').map(String::trim)
            require(parts.size == 2 && parts.all { it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl) }) {
                "Invalid POSM update pilot entry: use pharmacyId|deviceId or pharmacyId|*"
            }
            parts[0] to parts[1]
        }
        .toSet()

    init {
        require(entries.size <= 200) { "Too many POSM update pilot entries" }
        require(entries.none { it.first == "*" }) { "A pilot pharmacy must be named explicitly" }
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    fun allows(pharmacyId: String?, deviceId: String?, legacy: Boolean): Boolean {
        if (legacy || pharmacyId.isNullOrBlank() || deviceId.isNullOrBlank()) return false
        val pharmacy = pharmacyId.trim()
        return (pharmacy to "*") in entries ||
            entries.any { it.first == pharmacy && it.second.equals(deviceId.trim(), ignoreCase = true) }
    }
}

/** Dotted numeric versions such as 1.0.91; a missing part counts as 0. Non-numeric versions compare as text. */
object ReleaseVersions {
    fun compare(left: String, right: String): Int {
        val a = left.trim().split('.')
        val b = right.trim().split('.')
        if ((a + b).any { part -> part.isEmpty() || part.any { !it.isDigit() } }) return left.trim().compareTo(right.trim())
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrNull(index)?.toBigInteger() ?: java.math.BigInteger.ZERO)
                .compareTo(b.getOrNull(index)?.toBigInteger() ?: java.math.BigInteger.ZERO)
            if (difference != 0) return difference
        }
        return 0
    }
}
