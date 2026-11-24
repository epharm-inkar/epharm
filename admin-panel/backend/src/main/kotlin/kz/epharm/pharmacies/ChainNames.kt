package kz.epharm.pharmacies

/**
 * Network (chain) name of a pharmacy under the umbrella chain `inkar`, taken from the start of its name.
 *
 * The register came from the storefront's stock locations, where the network is only part of the name and is
 * spelled in many ways (`JNK-фарм`, `Jnk-фарм`, `JNk-фарм`; `Ирбис-Жер`, `ИРБИС Жер`; `Лимон фарм`), and the old
 * parser glued a village onto it (`JNK-фарм с.Жети`). Reports by network need one spelling, the one the network's
 * own pharmacy list uses. Warehouses (`Склад ...`) are not pharmacies of a network and get no name here.
 *
 * Keep in step with `V109__pharmacy_directory_cleanup.sql`, which applied the same rules to the stored rows.
 */
object ChainNames {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("^\\s*jnk", RegexOption.IGNORE_CASE) to "JNK-фарм",
        Regex("^\\s*ирбис[\\s-]+жер", RegexOption.IGNORE_CASE) to "Ирбис-Жер",
        Regex("^\\s*ирбис\\s+транс", RegexOption.IGNORE_CASE) to "Ирбис Транс Систем",
        Regex("^\\s*курмет", RegexOption.IGNORE_CASE) to "Курмет-Фарм",
        Regex("^\\s*аспект", RegexOption.IGNORE_CASE) to "Аспект-траст",
        Regex("^\\s*лимон", RegexOption.IGNORE_CASE) to "Лимон Фарм",
        Regex("^\\s*(тоо\\s*)?\"?tengripharm", RegexOption.IGNORE_CASE) to "ТОО \"Tengripharm\"",
        Regex("^\\s*(тоо\\s+)?медин", RegexOption.IGNORE_CASE) to "ТОО Медин",
    )

    /** The network's single spelling, or null when the name does not start with a known network. */
    fun fromPharmacyName(name: String): String? =
        rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(name) }?.second
}
