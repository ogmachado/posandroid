package com.idos.pos.core.db

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RED (task 1.2, strict TDD scope covers the core/db package): [Converters]
 * round-trips BigDecimal<->TEXT and Instant<->epoch-millis Long, per design.md's
 * Room schema notes ("Money is BigDecimal persisted as TEXT ... never Double").
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun bigDecimalToString_and_back_roundTrips_exactValue() {
        val original = BigDecimal("123.45")

        val text = converters.bigDecimalToString(original)
        val restored = converters.stringToBigDecimal(text)

        assertEquals(original, restored)
    }

    @Test
    fun bigDecimalToString_handlesNull() {
        assertNull(converters.bigDecimalToString(null))
    }

    @Test
    fun stringToBigDecimal_handlesNull() {
        assertNull(converters.stringToBigDecimal(null))
    }

    @Test
    fun instantToLong_and_back_roundTrips_exactValue() {
        val original = Instant.ofEpochMilli(1_720_000_000_123)

        val millis = converters.instantToLong(original)
        val restored = converters.longToInstant(millis)

        assertEquals(original, restored)
    }

    @Test
    fun instantToLong_handlesNull() {
        assertNull(converters.instantToLong(null))
    }

    @Test
    fun longToInstant_handlesNull() {
        assertNull(converters.longToInstant(null))
    }
}
