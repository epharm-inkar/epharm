package kz.epharm.promo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.promo.dto.CreatePromoRequest
import kz.epharm.promo.dto.PromoDto
import kz.epharm.promo.dto.UpdatePromoRequest
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.repository.PromoFundingState
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.promo.service.MotivationPermissions
import kz.epharm.promo.service.PromoService
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.Optional

class PromoBudgetModeTest {
    private val promos = mockk<PromoRepository>()
    private val scope = mockk<AdministrativeScope>(relaxed = true)
    private val rules = mockk<RuleRepository>(relaxed = true)
    private val pharmacies = mockk<PharmacyRepository>(relaxed = true)
    private val service = PromoService(promos, mockk<MedusaPriceService>(), rules, pharmacies, mockk(), scope)

    init { every { promos.save(any()) } answers { firstArg<PromoEntity>() } }

    private fun locked(promo: PromoEntity, mode: Boolean = promo.budgetUnlimited, amount: Long = promo.budget,
        committed: Long = promo.spent) {
        every { promos.findForUpdate(promo.id) } returns Optional.of(promo)
        every { promos.fundingState(promo.id) } returns object : PromoFundingState {
            override val budget = amount
            override val spent = committed
            override val budgetUnlimited = mode
        }
    }

    @Test fun `old create defaults to capped zero and omitted update mode is not a false reset`() {
        val mapper = jacksonObjectMapper()
        val create = mapper.readValue<CreatePromoRequest>("""{"title":"Test","budget":0}""")
        val update = mapper.readValue<UpdatePromoRequest>("""{"title":"Old editor"}""")
        assertFalse(create.budgetUnlimited)
        assertNull(update.budgetUnlimited)
        assertNull(service.preparedPatch(update).budgetUnlimited)
        assertFalse(service.preparedPatch(UpdatePromoRequest(budgetUnlimited = false)).budgetUnlimited!!)
        assertFalse(service.create(create, "test").budgetUnlimited)
        verify(exactly = 0) { scope.requireAllPermission(MotivationPermissions.configureBonus) }
    }

    @Test fun `explicit unlimited create and both mode transitions require financial permission`() {
        assertTrue(service.create(CreatePromoRequest(title = "Test", budgetUnlimited = true), "test").budgetUnlimited)
        verify(exactly = 1) { scope.requireAllPermission(MotivationPermissions.configureBonus) }
        val promo = PromoEntity(id = "test")
        assertTrue(service.requiresBonusPermission(promo, UpdatePromoRequest(budgetUnlimited = true)))
        promo.budgetUnlimited = true
        assertTrue(service.requiresBonusPermission(promo, UpdatePromoRequest(budgetUnlimited = false)))
        assertFalse(service.requiresBonusPermission(promo, UpdatePromoRequest(title = "Other")))
        assertTrue(PromoDto.of(promo).budgetUnlimited)
    }

    @Test fun `old title patch preserves current unlimited mode even after a stale entity load`() {
        val promo = PromoEntity(id = "test", budget = 100, spent = 0, budgetUnlimited = false)
        locked(promo, mode = true, amount = 0, committed = 500)
        val result = service.update("test", UpdatePromoRequest(title = "Updated"))
        assertTrue(result.budgetUnlimited)
        assertEquals(0L, result.budget)
        assertEquals(500L, result.spent)
        verify(exactly = 0) { promos.updateFundingIfCoversSpent(any(), any(), any()) }
        verify(exactly = 0) { scope.requireAllPermission(MotivationPermissions.configureBonus) }
    }

    @Test fun `switching back to limited is guarded atomically by committed spend`() {
        val promo = PromoEntity(id = "test", budgetUnlimited = true, spent = 500)
        locked(promo)
        every { promos.updateFundingIfCoversSpent("test", 499, false) } returns 0
        val error = assertThrows(AppException::class.java) {
            service.update("test", UpdatePromoRequest(budget = 499, budgetUnlimited = false))
        }
        assertEquals(HttpStatus.CONFLICT, error.status)
        verify(exactly = 0) { promos.save(any()) }
    }

    @Test fun `budget-only update keeps mode omitted in atomic database operation`() {
        val promo = PromoEntity(id = "test", budgetUnlimited = true, spent = 500, budget = 1000)
        locked(promo)
        every { promos.updateFundingIfCoversSpent("test", 0, null) } returns 1
        val result = service.update("test", UpdatePromoRequest(budget = 0))
        assertTrue(result.budgetUnlimited)
        assertEquals(500L, result.spent)
        assertEquals(0L, result.budget)
        verify(exactly = 1) { promos.updateFundingIfCoversSpent("test", 0, null) }
    }

    @Test fun `stale limited snapshot cannot bypass permission to turn off committed unlimited mode`() {
        val promo = PromoEntity(id = "test", budgetUnlimited = false)
        locked(promo, mode = true)
        every { scope.requireAllPermission(MotivationPermissions.configureBonus) } throws
            AppException(ErrorCode.FORBIDDEN, "Denied", HttpStatus.FORBIDDEN)
        assertThrows(AppException::class.java) {
            service.update("test", UpdatePromoRequest(budgetUnlimited = false))
        }
        verify(exactly = 0) { promos.updateFundingIfCoversSpent(any(), any(), any()) }
        verify(exactly = 0) { promos.save(any()) }
    }
}
