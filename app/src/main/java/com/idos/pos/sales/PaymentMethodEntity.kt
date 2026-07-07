package com.idos.pos.sales

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Seed-only payment-method catalog row (see design.md "Payment-method catalog is
 * read-only (seed-only) in Slice A"). [affectsCashBalance] drives the cash-session
 * `expectedBalance` math — only orders paid with a method where this is true
 * contribute to the live cash-sales aggregate.
 */
@Entity(
    tableName = "payment_method",
    indices = [Index(value = ["code"], unique = true)],
)
data class PaymentMethodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val affectsCashBalance: Boolean,
    val active: Boolean = true,
)
