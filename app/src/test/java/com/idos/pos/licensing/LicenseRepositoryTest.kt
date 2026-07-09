package com.idos.pos.licensing

import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val INSTALLATION_ID = "installation-id-abc123"

/**
 * RED/GREEN (task 3.1/3.2): [LicenseRepository] orchestration — design.md
 * "Interfaces / Contracts" + "Rollback guard (strict, per
 * license-activation/spec.md:33-35)". Deliberately pure-JVM/JUnit4, no
 * Robolectric: [LicenseVerifier]/[InstallationIdStore]/[ClockRollbackDetector]
 * are faked via plain lambdas rather than constructed for real, so this suite
 * exercises ONLY [LicenseRepository]'s own orchestration (rollback guard,
 * status selection, persistence shape) — cryptographic verification is
 * already covered by [LicenseVerifierTest], triangulation math by
 * [ClockRollbackDetectorTest].
 *
 * [FakeLicenseStateDao] is the one fake backed by the REAL [LicenseStateDao]
 * interface (trivially fakeable since it already is an interface) rather
 * than a lambda — Room correctness itself is covered by [LicenseStateDaoTest].
 */
class LicenseRepositoryTest {

    private lateinit var dao: FakeLicenseStateDao

    @Before
    fun setUp() {
        dao = FakeLicenseStateDao()
    }

    private fun claims(
        issuedAt: Instant = Instant.parse("2026-06-01T00:00:00Z"),
        expirationDate: LocalDate = LocalDate.now(ZoneOffset.UTC).plusDays(30),
    ) = LicenseVerifier.Claims(
        licenseId = "license-001",
        issuedAt = issuedAt,
        expirationDate = expirationDate,
        businessId = "biz-1",
        businessName = "Acme Bar",
        businessTaxId = "TAX-1",
        product = LicenseVerifier.PRODUCT_ANDROID_POS,
        machineId = INSTALLATION_ID,
    )

    /**
     * Default fake verify: honors the [Boolean] `compromised` argument the
     * repository passes through (so [currentStatus] tests can exercise the
     * COMPROMISED-overrides-expiry behavior), otherwise always VALID.
     */
    private fun repository(
        verify: (String, String, LocalDate, Boolean) -> LicenseVerifier.Result = { _, _, _, compromised ->
            if (compromised) {
                LicenseVerifier.Result(LicenseStatus.COMPROMISED, claims())
            } else {
                LicenseVerifier.Result(LicenseStatus.VALID, claims())
            }
        },
        evaluateHeartbeat: (Long, Boolean, Long?, Long?, Long?) -> ClockRollbackDetector.Result = { now, _, _, _, _ ->
            ClockRollbackDetector.Result(compromised = false, heartbeatToPersist = now)
        },
    ) = LicenseRepository(
        dao = dao,
        verify = verify,
        installationIdProvider = { INSTALLATION_ID },
        evaluateHeartbeat = evaluateHeartbeat,
    )

    // --- Requirement: Preview Before Install ---

    @Test
    fun preview_doesNotPersistAnyLicenseState() {
        val repo = repository()

        val result = repo.preview("candidate-jws")

        assertTrue(result.isSuccess)
        assertEquals(LicenseStatus.VALID, result.getOrThrow().status)
        assertEquals(0, dao.upsertCallCount)
        assertNull(dao.stored)
    }

    // --- Requirement: Install Persists a Verified License ---

    @Test
    fun install_onFirstActivation_withNoStoredLicense_succeeds() = runBlocking {
        val repo = repository()

        val result = repo.install("candidate-jws")

        assertTrue(result.isSuccess)
        assertEquals(LicenseStatus.VALID, result.getOrThrow())
        assertEquals(1, dao.upsertCallCount)
        assertEquals(claims().issuedAt.epochSecond, dao.stored?.installedIssuedAt)
    }

    @Test
    fun install_whenCurrentlyCompromised_clearsCompromisedOnSuccess() = runBlocking {
        // Given a device with a sticky-COMPROMISED license already stored
        dao.stored = LicenseStateEntity(
            jws = "old-jws",
            installedIssuedAt = Instant.parse("2026-01-01T00:00:00Z").epochSecond,
            lastHeartbeatAt = 1_000L,
            compromised = true,
        )
        val newerIssuedAt = Instant.parse("2026-06-01T00:00:00Z")
        val repo = repository(verify = { _, _, _, _ -> LicenseVerifier.Result(LicenseStatus.VALID, claims(issuedAt = newerIssuedAt)) })

        // When a newer, validly-signed license is installed
        val result = repo.install("new-jws")

        // Then the sticky-compromised flag is cleared
        assertTrue(result.isSuccess)
        assertEquals(false, dao.stored?.compromised)
    }

    @Test
    fun install_whenVerificationFails_isRejected_andActiveLicenseUntouched() = runBlocking {
        dao.stored = LicenseStateEntity(jws = "old-jws", installedIssuedAt = 1_000L, compromised = false)
        val repo = repository(verify = { _, _, _, _ -> LicenseVerifier.Result(LicenseStatus.INVALID_SIGNATURE, claims = null) })

        val result = repo.install("bad-jws")

        assertTrue(result.isFailure)
        assertEquals(DomainError.LicenseVerificationRejected(LicenseStatus.INVALID_SIGNATURE), result.domainErrorOrNull())
        assertEquals(0, dao.upsertCallCount)
        assertEquals("old-jws", dao.stored?.jws)
    }

    @Test
    fun install_whenCandidateIsAlreadyExpired_isRejected_evenWithNewerIssuedAtAndValidSignature() = runBlocking {
        // Given a previously installed VALID license, and a candidate that is
        // correctly signed, correctly bound, has a strictly newer issuedAt
        // (passes the rollback guard) — but has already expired
        // (specs/license-activation/spec.md: "GIVEN a candidate license passes
        // signature, product, and binding checks AND IS UNEXPIRED")
        dao.stored = LicenseStateEntity(jws = "old-jws", installedIssuedAt = Instant.parse("2026-01-01T00:00:00Z").epochSecond, compromised = false)
        val newerButExpiredIssuedAt = Instant.parse("2026-06-01T00:00:00Z")
        val repo = repository(
            verify = { _, _, _, _ ->
                LicenseVerifier.Result(LicenseStatus.EXPIRED, claims(issuedAt = newerButExpiredIssuedAt, expirationDate = LocalDate.now(ZoneOffset.UTC).minusDays(30)))
            },
        )

        // When installation is attempted
        val result = repo.install("expired-jws")

        // Then it is rejected (not merely "installed as EXPIRED"), and the
        // previously active VALID license is left completely untouched
        assertTrue(result.isFailure)
        assertEquals(DomainError.LicenseVerificationRejected(LicenseStatus.EXPIRED), result.domainErrorOrNull())
        assertEquals(0, dao.upsertCallCount)
        assertEquals("old-jws", dao.stored?.jws)
    }

    // --- Requirement: Rollback/Replay Protection ---

    @Test
    fun install_withCandidateIssuedAtEqualToStored_isRejectedAsRollback() = runBlocking {
        // Given the currently active license has issuedAt = T
        val issuedAt = Instant.parse("2026-06-01T00:00:00Z")
        dao.stored = LicenseStateEntity(jws = "old-jws", installedIssuedAt = issuedAt.epochSecond, compromised = false)
        val repo = repository(verify = { _, _, _, _ -> LicenseVerifier.Result(LicenseStatus.VALID, claims(issuedAt = issuedAt)) })

        // When a candidate with the SAME issuedAt (a byte-for-byte replay) is installed
        val result = repo.install("replayed-jws")

        // Then it is rejected as a rollback/replay, and the active license is untouched
        assertTrue(result.isFailure)
        assertEquals(DomainError.LicenseRollbackRejected, result.domainErrorOrNull())
        assertEquals(0, dao.upsertCallCount)
    }

    @Test
    fun install_withCandidateIssuedAtOlderThanStored_isRejectedAsRollback() = runBlocking {
        // Given the currently active license has issuedAt = 2026-06-01
        dao.stored = LicenseStateEntity(jws = "old-jws", installedIssuedAt = Instant.parse("2026-06-01T00:00:00Z").epochSecond, compromised = false)
        val repo = repository(
            verify = { _, _, _, _ -> LicenseVerifier.Result(LicenseStatus.VALID, claims(issuedAt = Instant.parse("2026-05-01T00:00:00Z"))) },
        )

        // When an older, validly-signed license is installed
        val result = repo.install("older-jws")

        // Then it is rejected as a rollback, and the active license is untouched
        assertTrue(result.isFailure)
        assertEquals(DomainError.LicenseRollbackRejected, result.domainErrorOrNull())
        assertEquals("old-jws", dao.stored?.jws)
    }

    @Test
    fun install_withCandidateIssuedAtNewerThanStored_replacesActiveLicense() = runBlocking {
        // Given the currently active license has issuedAt = 2026-06-01
        dao.stored = LicenseStateEntity(jws = "old-jws", installedIssuedAt = Instant.parse("2026-06-01T00:00:00Z").epochSecond, compromised = false)
        val newerIssuedAt = Instant.parse("2026-07-01T00:00:00Z")
        val repo = repository(verify = { _, _, _, _ -> LicenseVerifier.Result(LicenseStatus.VALID, claims(issuedAt = newerIssuedAt)) })

        // When a validly-signed, correctly-bound license with issuedAt = 2026-07-01 is installed
        val result = repo.install("newer-jws")

        // Then the new license replaces the active one
        assertTrue(result.isSuccess)
        assertEquals("newer-jws", dao.stored?.jws)
        assertEquals(newerIssuedAt.epochSecond, dao.stored?.installedIssuedAt)
    }

    @Test
    fun install_onFirstActivation_withNoStoredLicense_alwaysPassesRollbackGuard() = runBlocking {
        // Given no license has ever been installed on this device (storedIssuedAt == null)
        val repo = repository(verify = { _, _, _, _ -> LicenseVerifier.Result(LicenseStatus.VALID, claims(issuedAt = Instant.parse("2020-01-01T00:00:00Z"))) })

        // When any validly-signed candidate is installed, regardless of how old its issuedAt is
        val result = repo.install("first-jws")

        // Then there is nothing to compare against, so the guard always passes
        assertTrue(result.isSuccess)
        assertEquals("first-jws", dao.stored?.jws)
    }

    // --- currentStatus(): COMPROMISED overrides expiry status ---

    @Test
    fun currentStatus_withNoStoredLicense_returnsNotConfigured() = runBlocking {
        val repo = repository()

        val status = repo.currentStatus()

        assertEquals(LicenseStatus.NOT_CONFIGURED, status)
    }

    @Test
    fun currentStatus_whenCompromised_overridesWhatWouldOtherwiseBeValid() = runBlocking {
        // Given a stored license that is sticky-COMPROMISED (would otherwise evaluate VALID)
        dao.stored = LicenseStateEntity(jws = "jws", installedIssuedAt = 1_000L, compromised = true)
        val repo = repository()

        // When status is queried
        val status = repo.currentStatus()

        // Then COMPROMISED overrides the expiry-based evaluation
        assertEquals(LicenseStatus.COMPROMISED, status)
    }

    @Test
    fun currentStatus_whenNotCompromised_reflectsVerifyResult() = runBlocking {
        dao.stored = LicenseStateEntity(jws = "jws", installedIssuedAt = 1_000L, compromised = false)
        val repo = repository()

        val status = repo.currentStatus()

        assertEquals(LicenseStatus.VALID, status)
    }

    // --- heartbeat(): triangulates and persists ---

    @Test
    fun heartbeat_advancesStoredAnchor_whenDetectorReportsNoRollback() = runBlocking {
        dao.stored = LicenseStateEntity(jws = "jws", installedIssuedAt = 1_000L, lastHeartbeatAt = 5_000L, compromised = false)
        var nowSeenByDetector: Long? = null
        val repo = repository(
            evaluateHeartbeat = { now, _, _, _, _ ->
                nowSeenByDetector = now
                ClockRollbackDetector.Result(compromised = false, heartbeatToPersist = now)
            },
        )

        repo.heartbeat()

        assertEquals(1, dao.upsertCallCount)
        assertFalse(dao.stored?.compromised ?: true)
        // heartbeatToPersist is whatever "now" the repository handed the detector — advanced, not the stale 5_000L anchor
        assertEquals(nowSeenByDetector, dao.stored?.lastHeartbeatAt)
    }

    @Test
    fun heartbeat_marksCompromised_whenDetectorFlagsRollback() = runBlocking {
        dao.stored = LicenseStateEntity(jws = "jws", installedIssuedAt = 1_000L, lastHeartbeatAt = 5_000L, compromised = false)
        val repo = repository(evaluateHeartbeat = { _, _, _, _, _ -> ClockRollbackDetector.Result(compromised = true, heartbeatToPersist = null) })

        repo.heartbeat()

        assertEquals(1, dao.upsertCallCount)
        assertTrue(dao.stored?.compromised == true)
        assertEquals(5_000L, dao.stored?.lastHeartbeatAt) // anchor NOT advanced on a detected rollback
    }

    @Test
    fun heartbeat_withNoStoredLicenseYet_stillRecordsAnAnchor() = runBlocking {
        val repo = repository(evaluateHeartbeat = { now, _, _, _, _ -> ClockRollbackDetector.Result(compromised = false, heartbeatToPersist = now) })

        repo.heartbeat()

        assertEquals(1, dao.upsertCallCount)
        assertNull(dao.stored?.jws)
        assertFalse(dao.stored?.compromised ?: true)
    }
}

private class FakeLicenseStateDao : LicenseStateDao {
    var stored: LicenseStateEntity? = null
    var upsertCallCount = 0
        private set

    override suspend fun find(id: Int): LicenseStateEntity? = stored

    override suspend fun upsert(entity: LicenseStateEntity) {
        upsertCallCount++
        stored = entity
    }
}
