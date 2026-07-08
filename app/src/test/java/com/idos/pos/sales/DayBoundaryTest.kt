package com.idos.pos.sales

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure `java.time` arithmetic — no Robolectric/Room needed (task 8.2).
 * Proves [todayRange] resolves "today" against the CLOCK'S OWN ZONE, not a
 * UTC calendar day, using instants deliberately chosen to fall on the SAME
 * UTC calendar day but DIFFERENT local calendar days (and vice versa) — the
 * exact ambiguity a naive `Instant.now().truncatedTo(DAYS)` implementation
 * would get wrong (see the sibling Java backend's day-boundary bug, commit
 * `0abefbf`).
 */
class DayBoundaryTest {

    // --- A negative-offset zone: local day starts LATER than the UTC day ---

    @Test
    fun todayRange_inNegativeOffsetZone_startsAtLocalMidnight_notUtcMidnight() {
        // GIVEN a fixed clock at 2026-07-08T05:30:00Z in zone -05:00
        //  -> local time is 2026-07-08T00:30:00-05:00 (just past LOCAL midnight)
        val zone = ZoneOffset.ofHours(-5)
        val clock = Clock.fixed(Instant.parse("2026-07-08T05:30:00Z"), zone)

        // WHEN
        val range = todayRange(clock)

        // THEN local midnight 2026-07-08 in zone -05:00 is 2026-07-08T05:00:00Z,
        // NOT 2026-07-08T00:00:00Z (which is what a naive UTC truncation would give)
        assertEquals(Instant.parse("2026-07-08T05:00:00Z"), range.start)
        assertEquals(Instant.parse("2026-07-09T05:00:00Z"), range.endExclusive)
    }

    @Test
    fun todayRange_instantJustBeforeLocalMidnight_fallsOutsideRange_evenThoughSameUtcCalendarDay() {
        // GIVEN the same zone/"now" as above, and an instant that is on the SAME
        // UTC calendar day (2026-07-08) but BEFORE local midnight (still "yesterday" locally)
        val zone = ZoneOffset.ofHours(-5)
        val clock = Clock.fixed(Instant.parse("2026-07-08T05:30:00Z"), zone)
        val stillYesterdayLocally = Instant.parse("2026-07-08T03:00:00Z") // local: 2026-07-07T22:00 (yesterday)

        // WHEN
        val range = todayRange(clock)

        // THEN this instant must fall BEFORE range.start — a naive UTC-day
        // implementation would have incorrectly included it in "today"
        // (both instants share UTC calendar day 2026-07-08)
        assertTrue(stillYesterdayLocally.isBefore(range.start))
    }

    // --- A positive-offset zone: local day starts EARLIER than the UTC day ---

    @Test
    fun todayRange_inPositiveOffsetZone_startsBeforeUtcMidnight() {
        // GIVEN a fixed clock at 2026-07-08T20:00:00Z in zone +05:00
        //  -> local time is 2026-07-09T01:00:00+05:00 (already "tomorrow" in UTC terms)
        val zone = ZoneOffset.ofHours(5)
        val clock = Clock.fixed(Instant.parse("2026-07-08T20:00:00Z"), zone)

        // WHEN
        val range = todayRange(clock)

        // THEN local midnight 2026-07-09 in zone +05:00 is 2026-07-08T19:00:00Z
        assertEquals(Instant.parse("2026-07-08T19:00:00Z"), range.start)
        assertEquals(Instant.parse("2026-07-09T19:00:00Z"), range.endExclusive)
    }

    @Test
    fun todayRange_endExclusive_isExactlyOneLocalDayAfterStart() {
        val clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.ofHours(-3))

        val range = todayRange(clock)

        assertEquals(Duration.ofDays(1), Duration.between(range.start, range.endExclusive))
    }
}
