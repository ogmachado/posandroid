package com.idos.pos.cashsession

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.math.BigDecimal
import java.time.Instant

/**
 * Manual cash movement type (task 7.1; specs/cash-session/spec.md "Manual Cash
 * Movements Only on an Open Session"). Mirrors the reference backend's
 * `CASH_MOVEMENT.TYPE` (DEPOSIT/WITHDRAWAL) — the cash-register catalog and
 * cashier-username scoping are dropped (single device, single operator).
 */
enum class CashMovementType { DEPOSIT, WITHDRAWAL }

/**
 * Room [TypeConverter] for [CashMovementType], persisted as its [Enum.name]
 * TEXT — same idiom as [CashSessionStatusConverter].
 */
class CashMovementTypeConverter {
    @TypeConverter
    fun fromType(value: CashMovementType?): String? = value?.name

    @TypeConverter
    fun toType(value: String?): CashMovementType? = value?.let { CashMovementType.valueOf(it) }
}

/**
 * Append-only manual cash movement row (task 7.1), written EXCLUSIVELY by
 * [CashSessionDao.addMovementAtomic] — the single mutation primitive for
 * movements, mirroring `inventory/InventoryDao.applyMovementAtomic`'s
 * "one primitive, one owner" convention for this capability.
 */
@Entity(
    tableName = "cash_movement",
    foreignKeys = [
        ForeignKey(
            entity = CashSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
@TypeConverters(CashMovementTypeConverter::class)
data class CashMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: CashMovementType,
    val amount: BigDecimal,
    val reason: String? = null,
    val createdAt: Instant = Instant.now(),
)
