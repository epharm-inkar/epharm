package kz.epharm.mobile.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.dto.RefreshResponse
import kz.epharm.mobile.auth.dto.MobileAuthResponse
import kz.epharm.mobile.auth.dto.VerifySmsResponse
import kz.epharm.mobile.auth.repository.MobileOtpRepository
import kz.epharm.mobile.auth.repository.MobileRefreshTokenRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.entity.PharmacistTier
import kz.epharm.pharmacists.repository.PharmacistRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * E2E мобильной аутентификации через MockMvc: request → verify → register/login → refresh → me.
 * Профиль test → DevDataSeeder НЕ работает; данные сеем сами.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MobileAuthIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test")
            .withUsername("epharm")
            .withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var otpRepository: MobileOtpRepository
    @Autowired private lateinit var refreshRepository: MobileRefreshTokenRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    private val activePhone = "+77002223344"
    private val blockedPhone = "+77003334455"
    private val unactivatedPhone = "+77000000012"
    private val unactivatedIin = "000101500011"
    private val takenIin = "880404400014"

    @BeforeEach
    fun seed() {
        jdbc.execute("TRUNCATE posm_staff_rate_limits, mobile_password_reset_challenges, mobile_password_reset_rate_limits")
        refreshRepository.deleteAll()
        otpRepository.deleteAll()
        pharmacistRepository.deleteAll()

        pharmacistRepository.save(
            PharmacistEntity(id = "u_active", name = "Иван Существующий", iin = "951212500015", phone = activePhone)
                .also {
                    it.status = PharmacistStatus.active
                    it.tier = PharmacistTier.Gold
                    it.balance = 42_000
                    it.passwordHash = passwordEncoder.encode("Farm123!")
                },
        )
        pharmacistRepository.save(
            PharmacistEntity(id = "u_blocked", name = "Пётр Блокированный", iin = "781122300017", phone = blockedPhone)
                .also {
                    it.status = PharmacistStatus.blocked
                    it.passwordHash = passwordEncoder.encode("Farm123!")
                },
        )
        pharmacistRepository.save(
            PharmacistEntity(id = "u_dup", name = "Сергей Дубликат", iin = takenIin, phone = "+77004445566")
                .also { it.status = PharmacistStatus.active },
        )
        pharmacistRepository.save(
            PharmacistEntity(
                id = "u_unactivated",
                name = "Грущак Василий Григорьевич",
                iin = unactivatedIin,
                phone = unactivatedPhone,
            ).also { it.status = PharmacistStatus.active },
        )
    }

    // ── Саморегистрация нового номера ─────────────────────────────────────────

    @Test
    fun `новый номер verify возвращает registered=false без токенов`() {
        requestOtp("+7 (777) 100-20-30")
        val resp = verify("+7 (777) 100-20-30", "5445")
        assertThat(resp.registered).isFalse
        assertThat(resp.tokens).isNull()
    }

    @Test
    fun `register создаёт pending-фармацевта без аптеки и выдаёт токены`() {
        requestOtp("+7 (777) 100-20-30")
        verify("+7 (777) 100-20-30", "5445")

        val result = mockMvc.perform(
            post("/api/mobile/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"+7 (777) 100-20-30","fio":"Тест Тестов","iin":"990303500014"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacist.status").value("pending"))
            .andExpect(jsonPath("$.pharmacist.name").value("Тест Тестов"))
            .andExpect(jsonPath("$.pharmacist.balance").value(0))
            .andExpect(jsonPath("$.tokens.accessToken").isString)
            .andReturn()

        val body = objectMapper.readValue(result.response.contentAsByteArray, MobileAuthResponse::class.java)
        // Аптека не назначена (pending) + номер нормализован в E.164.
        assertThat(body.pharmacist.pharmacyId).isNull()
        assertThat(body.pharmacist.phone).isEqualTo("+77771002030")

        // Токен работает на /me.
        mockMvc.perform(
            get("/api/mobile/auth/me").header("Authorization", "Bearer ${body.tokens.accessToken}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("pending"))
            .andExpect(jsonPath("$.name").value("Тест Тестов"))
    }

    @Test
    fun `register с невалидным ИИН (неверная контрольная сумма) отклоняется 400`() {
        requestOtp("+7 (777) 100-20-31")
        verify("+7 (777) 100-20-31", "5445")
        // 12 цифр, но контрольная сумма не сходится → @Iin отвергает до бизнес-логики.
        mockMvc.perform(
            post("/api/mobile/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"+7 (777) 100-20-31","fio":"Плохой ИИН","iin":"990303500010"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `register без verify отклоняется OTP_NOT_VERIFIED`() {
        mockMvc.perform(
            post("/api/mobile/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"+77770001122","fio":"Без Верификации","iin":"910228400016"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("OTP_NOT_VERIFIED"))
    }

    @Test
    fun `register с занятым ИИН отклоняется CONFLICT`() {
        requestOtp("+77779990011")
        verify("+77779990011", "5445")
        mockMvc.perform(
            post("/api/mobile/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"+77779990011","fio":"Чужой ИИН","iin":"$takenIin"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    // ── Вход существующего фармацевта ─────────────────────────────────────────

    @Test
    fun `initial password byte limit preserves verified OTP and rejects silent BCrypt truncation`() {
        mockMvc.perform(post("/api/mobile/auth/activation/sms/request").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(mapOf("iin" to unactivatedIin, "phone" to unactivatedPhone))))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/mobile/auth/activation/sms/verify").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(mapOf("iin" to unactivatedIin, "phone" to unactivatedPhone, "code" to "5445"))))
            .andExpect(status().isOk)
        val tokenCount = refreshRepository.count()
        for (value in listOf("x".repeat(73), "я".repeat(37))) {
            mockMvc.perform(post("/api/mobile/auth/activation/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(mapOf("iin" to unactivatedIin, "phone" to unactivatedPhone, "password" to value))))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            assertThat(pharmacistRepository.findByIin(unactivatedIin)!!.passwordHash).isNull()
            assertThat(refreshRepository.count()).isEqualTo(tokenCount)
        }
        // The same verified proof remains usable; exact edge whitespace is not trimmed.
        val exact = " " + "я".repeat(35) + " "
        mockMvc.perform(post("/api/mobile/auth/activation/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(mapOf("iin" to unactivatedIin, "phone" to unactivatedPhone, "password" to exact))))
            .andExpect(status().isOk)
        val stored = pharmacistRepository.findByIin(unactivatedIin)!!.passwordHash!!
        assertThat(passwordEncoder.matches(exact, stored)).isTrue
        assertThat(passwordEncoder.matches(exact.trim(), stored)).isFalse
    }

    @Test
    fun `whitespace variants of an IIN cannot bypass the shared password login attempt budget`() {
        repeat(10) { index ->
            val variant = " ".repeat(index) + "951212500015" + "\t".repeat(index)
            mockMvc.perform(post("/api/mobile/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(mapOf("iin" to variant, "password" to "wrong"))))
                .andExpect(status().isUnauthorized)
        }
        for (variant in listOf("951212500015", " 951212500015 ", "\t951212500015\t")) {
            mockMvc.perform(post("/api/mobile/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(mapOf("iin" to variant, "password" to "Farm123!"))))
                .andExpect(status().isTooManyRequests)
                .andExpect(jsonPath("$.code").value("OTP_TOO_MANY_ATTEMPTS"))
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posm_staff_rate_limits", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `первая активация привязывает созданный пароль к ИИН`() {
        mockMvc.perform(
            post("/api/mobile/auth/activation/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"$unactivatedIin"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.passwordSet").value(false))
            .andExpect(jsonPath("$.phoneMasked").isString)

        mockMvc.perform(
            post("/api/mobile/auth/activation/sms/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"$unactivatedIin","phone":"$unactivatedPhone"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.devCode").value("5445"))

        mockMvc.perform(
            post("/api/mobile/auth/activation/sms/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"$unactivatedIin","phone":"$unactivatedPhone","code":"5445"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verified").value(true))

        mockMvc.perform(
            post("/api/mobile/auth/activation/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"$unactivatedIin","phone":"$unactivatedPhone","password":"NewFarm123!"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacist.id").value("u_unactivated"))
            .andExpect(jsonPath("$.tokens.accessToken").isString)

        val saved = pharmacistRepository.findByIin(unactivatedIin)!!
        val hash = checkNotNull(saved.passwordHash)
        assertThat(hash).isNotBlank
        assertThat(hash).doesNotContain("NewFarm123!")
        assertThat(passwordEncoder.matches("NewFarm123!", hash)).isTrue

        mockMvc.perform(
            post("/api/mobile/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"$unactivatedIin","password":"NewFarm123!"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacist.id").value("u_unactivated"))
    }

    @Test
    fun `активация отклоняет телефон не привязанный к ИИН`() {
        mockMvc.perform(
            post("/api/mobile/auth/activation/sms/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"$unactivatedIin","phone":"+77001112233"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `создание пароля без подтверждения телефона запрещено`() {
        mockMvc.perform(
            post("/api/mobile/auth/activation/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"$unactivatedIin","phone":"$unactivatedPhone","password":"NewFarm123!"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("OTP_NOT_VERIFIED"))
    }

    @Test
    fun `повторная активация ИИН с готовым паролем запрещена`() {
        mockMvc.perform(
            post("/api/mobile/auth/activation/sms/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"951212500015","phone":"$activePhone"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `вход по ИИН и паролю выдаёт токены и профиль`() {
        mockMvc.perform(
            post("/api/mobile/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"951212500015","password":"Farm123!"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacist.id").value("u_active"))
            .andExpect(jsonPath("$.tokens.accessToken").isString)
    }

    @Test
    fun `неверный пароль не раскрывает существование ИИН`() {
        mockMvc.perform(
            post("/api/mobile/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"951212500015","password":"Wrong123!"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.message").value("Неверный ИИН или пароль"))
    }

    @Test
    fun `заблокированный фармацевт не может войти по ИИН`() {
        mockMvc.perform(
            post("/api/mobile/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"iin":"781122300017","password":"Farm123!"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("PHARMACIST_BLOCKED"))
    }

    // ── Вход по номеру телефона и паролю (25.11.2026) ─────────────────────────

    @Test
    fun `вход по номеру телефона в любом привычном формате`() {
        for (typed in listOf("+77002223344", "+7 700 222 33 44", "87002223344", "8 (700) 222-33-44", "7002223344")) {
            loginByPhone(typed, "Farm123!")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.pharmacist.id").value("u_active"))
                .andExpect(jsonPath("$.tokens.accessToken").isString)
                .andExpect(jsonPath("$.tokens.refreshToken").isString)
        }
    }

    @Test
    fun `неверный пароль, неизвестный или чужой номер дают один и тот же отказ`() {
        savePharmacist("u_other", "000101500071", "+77005556677", "Other123!")
        for ((phone, password) in listOf(
            activePhone to "Wrong123!",
            "+77009998877" to "Farm123!",
            // Another person's number with a password that is right only for a third person.
            "+77005556677" to "Farm123!",
            activePhone to "Other123!",
            // A card that has a phone but no password yet.
            unactivatedPhone to "Farm123!",
        )) {
            loginByPhone(phone, password)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Неверный номер телефона или пароль"))
        }
        assertThat(refreshRepository.count()).isZero
    }

    @Test
    fun `заблокированный фармацевт не может войти по телефону`() {
        loginByPhone("8 700 333 44 55", "Farm123!")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("PHARMACIST_BLOCKED"))
    }

    @Test
    fun `номер в старом формате находит карточку, а номер двух карточек не впускает никого`() {
        savePharmacist("u_legacy", "000101500081", "8 (701) 555-12-34", "Legacy123!")
        loginByPhone("+7 701 555 12 34", "Legacy123!")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacist.id").value("u_legacy"))

        // The unique index sees two different texts; the number itself now belongs to two cards.
        savePharmacist("u_twin", "000101500091", "+77015551234", "Twin123!")
        for (password in listOf("Legacy123!", "Twin123!")) {
            loginByPhone("87015551234", password)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
        }
        loginByIin("000101500091", "Twin123!")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacist.id").value("u_twin"))
    }

    @Test
    fun `телефон и ИИН одной карточки тратят один лимит попыток`() {
        val formats = listOf("+77002223344", "8 700 222 33 44", "7002223344", "+7 (700) 222-33-44", "87002223344")
        repeat(10) { index ->
            loginByPhone(formats[index % formats.size], "wrong-$index").andExpect(status().isUnauthorized)
        }
        loginByPhone(activePhone, "Farm123!")
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("OTP_TOO_MANY_ATTEMPTS"))
        loginByIin("951212500015", "Farm123!")
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("OTP_TOO_MANY_ATTEMPTS"))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posm_staff_rate_limits", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `неизвестный номер ограничен своим лимитом и не тратит лимит чужой карточки`() {
        repeat(10) { loginByPhone("+77009998877", "wrong-try").andExpect(status().isUnauthorized) }
        loginByPhone("8 700 999 88 77", "wrong-try")
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("OTP_TOO_MANY_ATTEMPTS"))
        loginByPhone(activePhone, "Farm123!").andExpect(status().isOk)
    }

    @Test
    fun `вход принимает ровно одно из ИИН и телефона в понятном формате`() {
        for (body in listOf(
            mapOf("password" to "Farm123!"),
            mapOf("iin" to "", "phone" to " ", "password" to "Farm123!"),
            mapOf("iin" to "951212500015", "phone" to activePhone, "password" to "Farm123!"),
            mapOf("phone" to "+1 202 555 0100", "password" to "Farm123!"),
            mapOf("phone" to "not a phone", "password" to "Farm123!"),
            mapOf("phone" to activePhone, "password" to "123"),
        )) {
            mockMvc.perform(
                post("/api/mobile/auth/login").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body)),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        }
        // Malformed requests never reach the password check, so they spend no attempt.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posm_staff_rate_limits", Int::class.java)).isZero
    }

    @Test
    fun `verify существующего номера сразу выдаёт токены и профиль`() {
        requestOtp(activePhone)
        val result = mockMvc.perform(
            post("/api/mobile/auth/sms/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"$activePhone","code":"5445"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.registered").value(true))
            .andExpect(jsonPath("$.pharmacist.balance").value(42000))
            .andExpect(jsonPath("$.tokens.accessToken").isString)
            .andReturn()
        val resp = objectMapper.readValue(result.response.contentAsByteArray, VerifySmsResponse::class.java)
        assertThat(resp.tokens).isNotNull
    }

    @Test
    fun `verify заблокированного номера возвращает 403 PHARMACIST_BLOCKED`() {
        requestOtp(blockedPhone)
        mockMvc.perform(
            post("/api/mobile/auth/sms/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"$blockedPhone","code":"5445"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("PHARMACIST_BLOCKED"))
    }

    // ── Ошибки кода ────────────────────────────────────────────────────────────

    @Test
    fun `неверный код возвращает 400 OTP_INVALID`() {
        requestOtp(activePhone)
        mockMvc.perform(
            post("/api/mobile/auth/sms/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"$activePhone","code":"000000"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("OTP_INVALID"))
    }

    @Test
    fun `verify без запроса кода возвращает 400 OTP_NOT_REQUESTED`() {
        mockMvc.perform(
            post("/api/mobile/auth/sms/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"+77770002233","code":"5445"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("OTP_NOT_REQUESTED"))
    }

    // ── Refresh + защита ─────────────────────────────────────────────────────

    @Test
    fun `refresh ротирует пару, повтор старого токена даёт 401`() {
        requestOtp(activePhone)
        val verifyResult = mockMvc.perform(
            post("/api/mobile/auth/sms/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"$activePhone","code":"5445"}"""),
        ).andExpect(status().isOk).andReturn()
        val verify = objectMapper.readValue(verifyResult.response.contentAsByteArray, VerifySmsResponse::class.java)
        val oldRefresh = verify.tokens!!.refreshToken

        val refreshResult = mockMvc.perform(
            post("/api/mobile/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$oldRefresh"}"""),
        ).andExpect(status().isOk).andReturn()
        val refreshed = objectMapper.readValue(refreshResult.response.contentAsByteArray, RefreshResponse::class.java)
        assertThat(refreshed.tokens.refreshToken).isNotEqualTo(oldRefresh)

        // Повтор старого — уже revoked (rotation одноразовый).
        mockMvc.perform(
            post("/api/mobile/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$oldRefresh"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    fun `me без токена возвращает 401`() {
        mockMvc.perform(get("/api/mobile/auth/me")).andExpect(status().isUnauthorized)
    }

    // ── Профиль /api/mobile/me (Фаза B) ──────────────────────────────────────

    @Test
    fun `GET mobile me с токеном отдаёт баланс из админки`() {
        requestOtp(activePhone)
        val verifyResult = mockMvc.perform(
            post("/api/mobile/auth/sms/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"$activePhone","code":"5445"}"""),
        ).andExpect(status().isOk).andReturn()
        val verify = objectMapper.readValue(verifyResult.response.contentAsByteArray, VerifySmsResponse::class.java)

        mockMvc.perform(
            get("/api/mobile/me").header("Authorization", "Bearer ${verify.tokens!!.accessToken}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balance").value(42000))
            .andExpect(jsonPath("$.tier").value("Gold"))
            .andExpect(jsonPath("$.status").value("active"))
    }

    @Test
    fun `GET mobile me без токена возвращает 401`() {
        mockMvc.perform(get("/api/mobile/me")).andExpect(status().isUnauthorized)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun loginByPhone(phone: String, password: String) = mockMvc.perform(
        post("/api/mobile/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(mapOf("phone" to phone, "password" to password))),
    )

    private fun loginByIin(iin: String, password: String) = mockMvc.perform(
        post("/api/mobile/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(mapOf("iin" to iin, "password" to password))),
    )

    private fun savePharmacist(id: String, iin: String, phone: String, password: String) {
        pharmacistRepository.save(
            PharmacistEntity(id = id, name = "Синтетический $id", iin = iin, phone = phone).also {
                it.status = PharmacistStatus.active
                it.passwordHash = passwordEncoder.encode(password)
            },
        )
    }

    private fun requestOtp(phone: String) {
        mockMvc.perform(
            post("/api/mobile/auth/sms/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"$phone"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sent").value(true))
            .andExpect(jsonPath("$.devCode").value("5445"))
    }

    private fun verify(phone: String, code: String): VerifySmsResponse {
        val result = mockMvc.perform(
            post("/api/mobile/auth/sms/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"$phone","code":"$code"}"""),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readValue(result.response.contentAsByteArray, VerifySmsResponse::class.java)
    }
}
