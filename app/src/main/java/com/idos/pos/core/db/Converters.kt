package com.idos.pos.core.db

import androidx.room.TypeConverter
import java.math.BigDecimal
import java.time.Instant

/**
 * Room [androidx.room.TypeConverter]s for the two non-primitive column types used
 * across the schema (see design.md Room schema notes):
 * - Money is `BigDecimal` persisted as TEXT — never `Double` — to preserve exact
 *   currency arithmetic.
 * - Timestamps are `Instant` persisted as epoch-millis `Long`.
 */
class Converters {

    @TypeConverter
    fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(value: String?): BigDecimal? = value?.let { BigDecimal(it) }

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }
}
