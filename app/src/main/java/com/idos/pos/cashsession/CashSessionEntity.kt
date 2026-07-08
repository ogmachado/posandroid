package com.idos.pos.cashsession

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.math.BigDecimal
import java.time.Instant

/**
 * Session lifecycle discriminator (task 7.1; specs/cash-session/spec.md
 * "Single Open Session Per Device", "Session Close Is Terminal").
 */
enum class CashSessionStatus { OPEN, CLOSED }

/**
 * Room [TypeConverter] for [CashSessionStatus], persisted as its [Enum.name]
 * TEXT — same idiom as `inventory/InventoryMovementEntity.kt`'s
 * `MovementTypeConverter`, applied at the entity level rather than added to
 * the global `core/db/Converters.kt` list.
 */
class CashSessionStatusConverter {
    @TypeConverter
    fun fromStatus(value: CashSessionStatus?): String? = value?.name

    @TypeConverter
    fun toStatus(value: String?): CashSessionStatus? = value?.let { CashSessionStatus.valueOf(it) }
}

/**
 * A single open-shift row (task 7.1; specs/cash-session/spec.md "Purpose": "a
 * single open shift per device — no per-cashier dimension"). At most one row
 * may have [status] == OPEN at any time — enforced by
 * [CashSessionDao.openAtomic], not by a DB constraint (SQLite has no partial
 * unique index, same reasoning as the reference backend's
 * `CashSessionServiceImpl.open` application-level invariant).
 *
 * **`expectedBalance`/`salesTotal`/`difference` are write-once-at-close
 * snapshot columns, not live values** (design.md "Decision: `expectedBalance`
 * computed LIVE from a Room `Flow` aggregate; snapshot only at close"): while
 * [status] is OPEN these three columns stay `null` and MUST NOT be read as a
 * balance — [CashSessionRepository.currentSessionFlow] derives the live value
 * instead, straight from [openingBalance] + the live movements aggregate (see
 * `ExpectedBalance.kt`). [CashSessionDao.closeSessionAtomic] is the only writer
 * of these three columns, exactly once, at close.
 */
@Entity(
    tableName = "cash_session",
    indices = [Index(value = ["status"])],
)
@TypeConverters(CashSessionStatusConverter::class)
data class CashSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val openedAt: Instant = Instant.now(),
    val closedAt: Instant? = null,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal? = null,
    val expectedBalance: BigDecimal? = null,
    val salesTotal: BigDecimal? = null,
    val difference: BigDecimal? = null,
    val status: CashSessionStatus = CashSessionStatus.OPEN,
)
