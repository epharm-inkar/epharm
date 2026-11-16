package kz.epharm.receipts

import kz.epharm.receipts.service.ExcelPharmacyMappingProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class ExcelPharmacyMappingPropertiesTest {
    @Test
    fun `empty defaults never infer codes from canonical IDs`() {
        assertNull(ExcelPharmacyMappingProperties().resolve("pharmacy-id"))
    }

    @Test
    fun `Spring configuration binds explicitly supplied aliases preserving exact case and punctuation`() {
        val source = MapConfigurationPropertySource(mapOf(
            "epharm.receipts.excel.pharmacy-codes[Branch.A]" to "pharmacy-id",
            "epharm.receipts.excel.pharmacy-codes[pharmacy-id]" to "pharmacy-id",
        ))
        val mapping = Binder(source).bind("epharm.receipts.excel", Bindable.of(ExcelPharmacyMappingProperties::class.java)).get()
        assertEquals("pharmacy-id", mapping.resolve("Branch.A"))
        assertEquals("pharmacy-id", mapping.resolve("pharmacy-id"))
        assertNull(mapping.resolve("branch.a"))
        assertNull(mapping.resolve(" Branch.A "))
    }
}
