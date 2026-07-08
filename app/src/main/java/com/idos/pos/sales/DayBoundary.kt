package com.idos.pos.sales

import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * `[start, endExclusive)` instant range for one calendar day, in whatever
 * zone the [Clock] passed to [todayRange] carries.
 */
data class DayRange(val start: Instant, val endExclusive: Instant)

/**
 * Computes the `[todayStart, todayEnd)` range for the CURRENT DEVICE LOCAL
 * calendar day (task 8.3; specs/sales-order/spec.md "Per-Day Sequential Order
 * Number").
 *
 * **Day-boundary correctness (do not "fix" this to `Instant.now().truncatedTo(DAYS)`)**:
 * this app is single-device/offline with no server or UTC concept, so "today"
 * MUST be the device's local calendar day — [LocalDate.now] resolved against
 * [clock]'s own [Clock.getZone], never a UTC truncation of the instant. A UTC
 * truncation shifts the day boundary for any timezone west/east of UTC, which
 * is exactly the bug class the sibling Java backend shipped and later fixed
 * (`D:\idos-pos` commit `0abefbf fix(reports): correct day-boundary
 * comparisons across timezones`).
 *
 * [clock] is an explicit parameter (defaulting to [Clock.systemDefaultZone]
 * at every production call site) precisely so tests can freeze it at an exact
 * instant/zone — see `SalesRepositoryTest`'s day-boundary test, which proves
 * this function resolves a UTC-day-ambiguous instant to the correct LOCAL
 * day, not the arithmetically simpler but WRONG UTC day.
 */
fun todayRange(clock: Clock): DayRange {
    val zone = clock.zone
    val today = LocalDate.now(clock)
    val start = today.atStartOfDay(zone).toInstant()
    val endExclusive = today.plusDays(1).atStartOfDay(zone).toInstant()
    return DayRange(start, endExclusive)
}
