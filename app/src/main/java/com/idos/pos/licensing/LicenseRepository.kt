package com.idos.pos.licensing

import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.DomainException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Verification outcome for a NOT-YET-installed candidate license
 * (specs/license-activation/spec.md "Preview Before Install"). Carries
 * whatever [LicenseVerifier.verify] returned — including a rejection status
 * (INVALID_SIGNATURE/WRONG_PRODUCT/MACHINE_MISMATCH/MALFORMED/EXPIRED) — so
 * [LicenseRepository.preview] never fails as a [Result]; the
 * operator-facing distinction lives in [status] itself. [claims] is present
 * whenever the signature, product, and machine-id checks all passed (i.e.
 * [status] is one of VALID/IN_GRACE_PERIOD/EXPIRED), `null` otherwise.
 */
data class LicensePreview(val status: LicenseStatus, val claims: LicenseVerifier.Claims?)

/**
 * License lifecycle orchestration (design.md "Interfaces / Contracts",
 * "Rollback guard (strict, per license-activation/spec.md:33-35)") — the
 * single entry point `MainActivity`/`PosApplication` (Phase 5) and
 * `ActivationViewModel` (Phase 4) use for status, preview, install, and the
 * foreground heartbeat.
 *
 * **Deviation from design.md's literal 5-arg constructor** (`dao, verifier,
 * idStore, prefsMirror, detector`): this class takes [verify] /
 * [installationIdProvider] / [evaluateHeartbeat] as plain function
 * references instead of the concrete [LicenseVerifier] /
 * [InstallationIdStore] / [ClockRollbackDetector] instances directly. All
 * three of those classes are `final`, and two of them (`LicenseVerifier`
 * needs `org.json`; `InstallationIdStore` needs a real `Context` + Android
 * Keystore) are expensive or impossible to fake under plain JVM JUnit4
 * without Robolectric — and opening them up or extracting interfaces would
 * mean touching already-completed Phase 1/2 files, out of scope for this
 * phase. Function-reference parameters let `LicenseRepositoryTest` fake
 * exactly the 4 collaborators task 3.1 names ("fakes for
 * dao/verifier/idStore/detector") with plain lambdas — no Robolectric
 * required — while [create] wires the real objects' method references for
 * production.
 *
 * Also **not implemented**: design's separate `prefsMirror` (an
 * EncryptedSharedPreferences-backed duplicate of the heartbeat/compromised
 * anchors, for defense-in-depth against a wiped/corrupted Room DB alone).
 * Task 3.1's fakes list has no 5th collaborator for it either, so this is a
 * deliberate scope reduction to Room-only persistence for this phase — see
 * the apply report for the follow-up risk this leaves before production
 * ship.
 */
class LicenseRepository(
    private val dao: LicenseStateDao,
    private val verify: (jws: String, installationId: String, nowUtc: LocalDate, compromised: Boolean) -> LicenseVerifier.Result,
    private val installationIdProvider: () -> String,
    private val evaluateHeartbeat: (
        now: Long,
        currentlyCompromised: Boolean,
        licenseIssuedAt: Long?,
        roomLastHeartbeat: Long?,
        prefsLastHeartbeat: Long?,
    ) -> ClockRollbackDetector.Result,
    private val clock: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
    private val nowEpochSecond: () -> Long = { Instant.now().epochSecond },
) {

    companion object {
        /** Reuses the reference backend's `IDOS_LICENSE_GRACE_PERIOD_DAYS` default (design.md Decision F). */
        const val GRACE_PERIOD_DAYS = 3L

        /** Reuses the reference backend's `IDOS_LICENSE_TIME_TOLERANCE_SECONDS` default (design.md Decision F). */
        const val CLOCK_TOLERANCE_SECONDS = 60L

        /** Production wiring — `AppContainer` (task 3.3) calls this with the real collaborators. */
        fun create(
            dao: LicenseStateDao,
            verifier: LicenseVerifier,
            idStore: InstallationIdStore,
            detector: ClockRollbackDetector,
        ): LicenseRepository = LicenseRepository(
            dao = dao,
            verify = verifier::verify,
            installationIdProvider = idStore::installationId,
            evaluateHeartbeat = detector::evaluate,
        )
    }

    /** [InstallationIdStore.installationId], surfaced for the activation screen (Phase 4). */
    fun installationId(): String = installationIdProvider()

    /**
     * Re-evaluates the currently-installed license fresh against today's
     * date and the persisted sticky-compromised flag —
     * specs/license-verification/spec.md "NOT_CONFIGURED When No License
     * Exists" + specs/anti-tamper-heartbeat/spec.md "Rollback Marks the
     * License COMPROMISED" (the COMPROMISED override itself is
     * [LicenseVerifier.verify]'s own step 6, not duplicated here).
     */
    suspend fun currentStatus(): LicenseStatus {
        val state = dao.find() ?: return LicenseStatus.NOT_CONFIGURED
        val jws = state.jws ?: return LicenseStatus.NOT_CONFIGURED
        return verify(jws, installationId(), clock(), state.compromised).status
    }

    /**
     * Verify-without-persist (specs/license-activation/spec.md "Preview
     * Before Install"). Always called with `compromised = false` — a
     * candidate being previewed has no relationship yet to this device's
     * sticky-compromised flag.
     */
    fun preview(jws: String): Result<LicensePreview> {
        val result = verify(jws, installationId(), clock(), false)
        return Result.success(LicensePreview(result.status, result.claims))
    }

    /**
     * Verifies, applies the strict rollback/replay guard, and persists —
     * specs/license-activation/spec.md "Install Persists a Verified
     * License" + "Rollback/Replay Protection" +
     * specs/anti-tamper-heartbeat/spec.md "Installing a new valid license
     * clears COMPROMISED". A verification failure (including an
     * already-EXPIRED candidate — spec.md "GIVEN a candidate license passes
     * signature, product, and binding checks **and is unexpired**") or a
     * rejected rollback guard leaves the currently active license (if any)
     * completely untouched — no partial write. Only VALID and
     * IN_GRACE_PERIOD candidates that also pass the rollback guard are ever
     * persisted, matching the desktop's existing behavior where
     * IN_GRACE_PERIOD continues to operate but EXPIRED does not.
     */
    suspend fun install(jws: String): Result<LicenseStatus> {
        val stored = dao.find()
        val result = verify(jws, installationId(), clock(), false)
        val claims = result.claims
        if (claims == null || result.status == LicenseStatus.EXPIRED) {
            return Result.failure(DomainException(DomainError.LicenseVerificationRejected(result.status)))
        }

        val candidateIssuedAt = claims.issuedAt.epochSecond
        val storedIssuedAt = stored?.installedIssuedAt
        if (storedIssuedAt != null && candidateIssuedAt <= storedIssuedAt) {
            return Result.failure(DomainException(DomainError.LicenseRollbackRejected))
        }

        dao.upsert(
            LicenseStateEntity(
                jws = jws,
                installedIssuedAt = candidateIssuedAt,
                lastHeartbeatAt = stored?.lastHeartbeatAt,
                compromised = false,
            ),
        )
        return Result.success(result.status)
    }

    /**
     * Foreground-triggered triangulation + persist
     * (specs/anti-tamper-heartbeat/spec.md "Heartbeat Is
     * Foreground-Triggered" + "Clock-Rollback Detection"). Called
     * unconditionally on every resume/start, even before any license is
     * installed, so the anchor exists from the device's very first
     * foreground moment.
     */
    suspend fun heartbeat() {
        val state = dao.find()
        val result = evaluateHeartbeat(
            nowEpochSecond(),
            state?.compromised ?: false,
            state?.installedIssuedAt,
            state?.lastHeartbeatAt,
            null, // prefsLastHeartbeat mirror — not implemented in this phase, see class doc
        )
        dao.upsert(
            (state ?: LicenseStateEntity()).copy(
                compromised = result.compromised,
                lastHeartbeatAt = result.heartbeatToPersist ?: state?.lastHeartbeatAt,
            ),
        )
    }
}
