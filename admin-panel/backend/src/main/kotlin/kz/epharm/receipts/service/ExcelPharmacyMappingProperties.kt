package kz.epharm.receipts.service

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Explicit, operator-verified Excel export code -> canonical pharmacy ID crosswalk.
 * Neither pharmacy IDs, display names, CRM branches nor storefront IDs are implicit aliases.
 */
@Component
@ConfigurationProperties(prefix = "epharm.receipts.excel")
class ExcelPharmacyMappingProperties {
    var pharmacyCodes: Map<String, String> = emptyMap()

    fun resolve(code: String): String? = pharmacyCodes[code]?.takeIf { it.isNotBlank() }
}
