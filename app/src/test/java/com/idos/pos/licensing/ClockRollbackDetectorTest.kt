package com.idos.pos.licensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOLERANCE_SEC = 60L

/**
 * RED/GREEN (task 2.3/2.4): [ClockRollbackDetector.evaluate] — design.md
 * "Clock-rollback triangulation (`ClockRollbackDetector`, pure)" —
 * specs/anti-tamper-heartbeat/spec.md scenarios: rollback beyond tolerance
 * flags, drift within tolerance doesn't, sticky-compromised stays sticky.
 * Deliberately no Android/Robolectric dependency — pure `Long` epoch-second
 * math only, same category as [InstallationIdTest].
 */
class ClockRollbackDetectorTest {

    private val detector = ClockRollbackDetector(toleranceSec = TOLERANCE_SEC)

    // --- Scenario: Clock moved backward beyond tolerance is detected ---

    @Test
    fun evaluate_whenNowIsBeforeLowerBoundMinusTolerance_flagsCompromised() {
        val lastHeartbeat = 10_000L
        val now = lastHeartbeat - TOLERANCE_SEC - 1 // 1s beyond tolerance

        val result = detector.evaluate(
            now = now,
            currentlyCompromised = false,
            licenseIssuedAt = null,
            roomLastHeartbeat = lastHeartbeat,
            prefsLastHeartbeat = null,
        )

        assertTrue(result.compromised)
        assertNull(result.heartbeatToPersist) // rollback: heartbeat anchor is not advanced
    }

    // --- Scenario: Small forward or backward drift within tolerance is not flagged ---

    @Test
    fun evaluate_whenDriftIsWithinTolerance_doesNotFlagCompromised() {
        val lastHeartbeat = 10_000L
        val now = lastHeartbeat - TOLERANCE_SEC + 1 // 1s inside tolerance, backward

        val result = detector.evaluate(
            now = now,
            currentlyCompromised = false,
            licenseIssuedAt = null,
            roomLastHeartbeat = lastHeartbeat,
            prefsLastHeartbeat = null,
        )

        assertFalse(result.compromised)
        assertEquals(now, result.heartbeatToPersist)
    }

    @Test
    fun evaluate_whenClockMovesForward_doesNotFlagCompromised() {
        val lastHeartbeat = 10_000L
        val now = lastHeartbeat + 5_000L

        val result = detector.evaluate(
            now = now,
            currentlyCompromised = false,
            licenseIssuedAt = null,
            roomLastHeartbeat = lastHeartbeat,
            prefsLastHeartbeat = null,
        )

        assertFalse(result.compromised)
        assertEquals(now, result.heartbeatToPersist)
    }

    // --- Scenario: lowerBound is the MAX of licenseIssuedAt / Room / prefs mirror ---

    @Test
    fun evaluate_usesMaxOfAllThreeSources_asLowerBound() {
        // roomLastHeartbeat is the freshest source; a rollback relative to it must
        // still be flagged even though licenseIssuedAt/prefs are much older.
        val result = detector.evaluate(
            now = 5_000L,
            currentlyCompromised = false,
            licenseIssuedAt = 1_000L,
            roomLastHeartbeat = 10_000L,
            prefsLastHeartbeat = 2_000L,
        )

        assertTrue(result.compromised)
    }

    // --- Scenario: Correcting the clock does not clear COMPROMISED (sticky) ---

    @Test
    fun evaluate_whenAlreadyCompromised_staysCompromised_evenWithNoDrift() {
        val result = detector.evaluate(
            now = 999_999L, // clock now reads far in the future, i.e. "corrected"
            currentlyCompromised = true,
            licenseIssuedAt = null,
            roomLastHeartbeat = 10_000L,
            prefsLastHeartbeat = null,
        )

        assertTrue(result.compromised)
        assertNull(result.heartbeatToPersist)
    }

    // --- Scenario: no prior heartbeat recorded yet (first-ever heartbeat) ---

    @Test
    fun evaluate_withNoPriorSources_neverFlagsCompromised() {
        val result = detector.evaluate(
            now = 1L,
            currentlyCompromised = false,
            licenseIssuedAt = null,
            roomLastHeartbeat = null,
            prefsLastHeartbeat = null,
        )

        assertFalse(result.compromised)
        assertEquals(1L, result.heartbeatToPersist)
    }
}
