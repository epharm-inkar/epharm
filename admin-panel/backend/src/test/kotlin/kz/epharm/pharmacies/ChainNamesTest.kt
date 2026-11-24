package kz.epharm.pharmacies

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChainNamesTest {

    @Test
    fun `every spelling of a network gives one name`() {
        val cases = mapOf(
            "JNK-фарм г.Павлодар Горького 41" to "JNK-фарм",
            "Jnk-фарм г.Павлодар, Украинская 39/2" to "JNK-фарм",
            "JNk-фарм г.Алматы Утеген батыра 73б" to "JNK-фарм",
            "JNK-фарм с.Жетиген, ул Валиханова 92Г" to "JNK-фарм",
            "Ирбис-Жер РПО г.Шымкент ,ул.Уалиханова 186" to "Ирбис-Жер",
            "ИРБИС Жер г.Талдыкорган Шевченко 128" to "Ирбис-Жер",
            "Ирбис Транс Систем Узынагаш толе би 51а" to "Ирбис Транс Систем",
            "ИРБИС ТРАНС СИСТЕМ Нурсултан Мангелик Ел 51/2" to "Ирбис Транс Систем",
            "Курмет-Фарм пос.Боралдай, ул.Байконур 23" to "Курмет-Фарм",
            "Аспект-траст г.Алматы Гагарина 159" to "Аспект-траст",
            "Лимон фарм г.Караганда, ул.Аманжолова 3" to "Лимон Фарм",
            "Лимон Фарм Жезказган Абая 81" to "Лимон Фарм",
            "ТОО \"Tengripharm\" г.Астана Абая 63" to "ТОО \"Tengripharm\"",
            "ТОО Медин г.Семей ул.Засядко 54" to "ТОО Медин",
        )
        cases.forEach { (name, chain) -> assertThat(ChainNames.fromPharmacyName(name)).`as`(name).isEqualTo(chain) }
    }

    @Test
    fun `warehouses and unknown names get no network`() {
        assertThat(ChainNames.fromPharmacyName("Склад ТОО Курмет-Фарм")).isNull()
        assertThat(ChainNames.fromPharmacyName("СКЛАД")).isNull()
        assertThat(ChainNames.fromPharmacyName("Аптека г.Алматы Жетысу 2, 85")).isNull()
        assertThat(ChainNames.fromPharmacyName("")).isNull()
    }

    private data class SeedRow(
        val id: String = "", val name: String = "", val chain: String = "", val city: String = "",
        val addr: String = "", val active: Boolean = true,
    )

    private val seed: List<SeedRow> = javaClass.classLoader.getResourceAsStream("seed/pharmacies.json")!!
        .use { jacksonObjectMapper().readValue(it) }

    @Test
    fun `seed register uses one spelling per network and a full city name`() {
        val warehouse = Regex("^\\s*склад", RegexOption.IGNORE_CASE)
        seed.forEach { row ->
            ChainNames.fromPharmacyName(row.name)?.let { assertThat(row.chain).`as`(row.name).isEqualTo(it) }
            if (!warehouse.containsMatchIn(row.name)) {
                assertThat(row.city).`as`(row.name).isNotEqualTo("—").hasSizeGreaterThan(3)
            }
        }
    }

    @Test
    fun `closed pharmacies without a till are off in the seed register`() {
        val off = seed.filterNot { it.active }.map { it.id }
        assertThat(off).containsExactlyInAnyOrder(
            "sloc_01KSBH8M075A0WRTF1SXT6XPFY", "sloc_01KSBH8M08E25N0AKBKSGFS00G", "sloc_01KSBH8M08SJNS8KGMTHRQAVA0",
            "sloc_01KSBH8M08TG18FQ5KNTG2M29R", "sloc_01KSBRMS3ACBVBTYKHGTTZ2R5F",
        )
    }
}
