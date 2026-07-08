package com.idos.pos.sales

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.idos.pos.cashsession.CashSessionEntity
import java.math.BigDecimal
import java.time.Instant

/**
 * Order lifecycle status (task 8.1; design.md Room schema table: "status
 * (COMPLETED default; VOID reserved for Slice B)"). Only [COMPLETED] is ever
 * produced in this slice — [VOID] exists purely for forward schema
 * compatibility, matching specs/sales-order/spec.md's Non-Goals ("`VOID` and
 * compensating reversal movements — deferred").
 */
enum class OrderStatus { COMPLETED, VOID }

/**
 * Order type discriminator (task 8.1; design.md Room schema table: "type
 * (SALE only in Slice A)"). Only [SALE] is ever produced in this slice —
 * [RETURN] exists purely for forward schema compatibility, matching
 * specs/sales-order/spec.md's Non-Goals ("`RETURN` order type ... deferred").
 */
enum class OrderType { SALE, RETURN }

class OrderStatusConverter {
    @TypeConverter
    fun fromStatus(value: OrderStatus?): String? = value?.name

    @TypeConverter
    fun toStatus(value: String?): OrderStatus? = value?.let { OrderStatus.valueOf(it) }
}

class OrderTypeConverter {
    @TypeConverter
    fun fromType(value: OrderType?): String? = value?.name

    @TypeConverter
    fun toType(value: String?): OrderType? = value?.let { OrderType.valueOf(it) }
}

/**
 * Sale-order header row (task 8.1; specs/sales-order/spec.md). Table name is
 * `orders`, not `order` — SQL reserved word, mirroring the reference
 * backend's identical table-name workaround (design.md Room schema notes).
 *
 * Written EXCLUSIVELY through [SalesDao.insertOrderWithLines], called from
 * inside [SalesRepository.createOrder]'s single `db.withTransaction { }` that
 * also drives the per-line `OUT` stock movement via
 * [com.idos.pos.inventory.InventoryDao.applyMovementAtomic] — see
 * [SalesRepository]'s class doc for the atomicity guarantee.
 *
 * [orderNumber] is scoped to the calendar day (device-local, see
 * [todayRange]) — NOT globally unique across days by design
 * (specs/sales-order/spec.md "Per-Day Sequential Order Number").
 */
@Entity(
    tableName = "orders",
    foreignKeys = [
        ForeignKey(
            entity = CashSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PaymentMethodEntity::class,
            parentColumns = ["id"],
            childColumns = ["paymentMethodId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["createdAt"]),
    ],
)
@TypeConverters(OrderStatusConverter::class, OrderTypeConverter::class)
data class OrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderNumber: Int,
    val sessionId: Long,
    val paymentMethodId: Long,
    val total: BigDecimal,
    val payment: BigDecimal,
    val changeAmount: BigDecimal,
    val status: OrderStatus = OrderStatus.COMPLETED,
    val type: OrderType = OrderType.SALE,
    val createdAt: Instant = Instant.now(),
)
