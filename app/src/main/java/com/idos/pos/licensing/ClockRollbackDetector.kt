package com.idos.pos.licensing

/**
 * Pure wall-clock-rollback triangulation (design.md "Clock-rollback
 * triangulation (`ClockRollbackDetector`, pure)") —
 * specs/anti-tamper-heartbeat/spec.md "Clock-Rollback Detection". Deliberately
 * Context-free: [LicenseRepository] (Phase 3) is what reads/writes the actual
 * Room row and `EncryptedSharedPreferences` mirror, calling [evaluate] once
 * per foreground heartbeat and persisting its [Result] — this class only does
 * the math, matching design's Decision E (pure-vs-Keystore split).
 *
 * `lowerBound = max(licenseIssuedAt, roomLastHeartbeat, prefsLastHeartbeat)`.
 * A rollback is flagged when `now < lowerBound - toleranceSec`. Once
 * [currentlyCompromised] is `true`, [evaluate] always returns `compromised =
 * true` regardless of the current reading — the sticky behavior
 * specs/anti-tamper-heartbeat/spec.md "COMPROMISED Status Is Sticky" requires
 * ("the only way out is installing a new, validly-signed license", which is
 * [LicenseRepository.install]'s job, not this class's).
 */
class ClockRollbackDetector(private val toleranceSec: Long) {

    /**
     * [heartbeatToPersist] is `null` whenever the caller should NOT advance
     * the stored heartbeat anchor — either a rollback was just detected (per
     * design.md: "If rollback -> compromised=true (written); else write now
     * to both") or the license was already sticky-compromised. Non-null
     * means "write this value as the new Room + prefs-mirror heartbeat".
     */
    data class Result(val compromised: Boolean, val heartbeatToPersist: Long?)

    fun evaluate(
        now: Long,
        currentlyCompromised: Boolean,
        licenseIssuedAt: Long?,
        roomLastHeartbeat: Long?,
        prefsLastHeartbeat: Long?,
    ): Result {
        if (currentlyCompromised) {
            return Result(compromised = true, heartbeatToPersist = null)
        }

        val lowerBound = listOfNotNull(licenseIssuedAt, roomLastHeartbeat, prefsLastHeartbeat).maxOrNull()
        val rollbackDetected = lowerBound != null && now < lowerBound - toleranceSec

        return if (rollbackDetected) {
            Result(compromised = true, heartbeatToPersist = null)
        } else {
            Result(compromised = false, heartbeatToPersist = now)
        }
    }
}
