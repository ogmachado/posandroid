package com.idos.pos.currency

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

/**
 * Display-only alternative-currency row (see design.md "Decision: Payment-method
 * catalog is read-only" sibling decision on currency + currency-display spec).
 *
 * [exchangeRate] is "units of the primary currency (CUP) per 1 unit of this one" —
 * e.g. 540 means 1 USD = 540 CUP. Never persisted on an order; conversion is
 * computed at render time as `total / exchangeRate`.
 */
@Entity(
    tableName = "currency",
    indices = [Index(value = ["code"], unique = true)],
)
data class CurrencyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val symbol: String,
    val exchangeRate: BigDecimal,
    val active: Boolean = true,
    val displayOrder: Int = 0,
)
