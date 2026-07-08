package com.idos.pos.inventory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant

/**
 * Movement type discriminator for [InventoryMovementEntity] — mirrors the
 * reference backend's `INVENTORY_MOVEMENT.TYPE` column (IN/OUT/ADJUST), per
 * design.md Room schema table and specs/inventory-ledger/spec.md.
 */
enum class MovementType { IN, OUT, ADJUST }

/**
 * Room [TypeConverter] for [MovementType], persisted as its [Enum.name] TEXT.
 * Applied at the entity level (see [InventoryMovementEntity]'s
 * `@TypeConverters` annotation) rather than added to the global
 * `core/db/Converters.kt` list — this type is inventory-specific domain
 * vocabulary, not a generic cross-cutting column type like `BigDecimal`/`Instant`.
 */
class MovementTypeConverter {
    @TypeConverter
    fun fromMovementType(value: MovementType?): String? = value?.name

    @TypeConverter
    fun toMovementType(value: String?): MovementType? = value?.let { MovementType.valueOf(it) }
}

/**
 * Append-only stock-change ledger row (task 5.1; specs/inventory-ledger/spec.md
 * "Purpose": "Stock is a ledger of movements (IN/OUT/ADJUST), not a directly
 * mutable counter"). Written EXCLUSIVELY by [InventoryDao.applyMovementAtomic]
 * — the single stock-mutation primitive (spec "Single-Owner Stock Mutation").
 *
 * **`orderId` has NO foreign-key constraint yet, unlike design.md's Room
 * schema table which lists it as `orderId (nullable)` FK.** The `orders`
 * table does not exist until Phase 8 (sales) — inventing it here would
 * violate that phase's ownership of its own schema, the same reasoning
 * `catalog/CatalogRepository.createProduct`'s Phase 4→5 TODO used for the
 * inventory table itself. Per design.md "Migration / Rollout" this schema is
 * greenfield v1 with no production data and no migration history, so Phase 8
 * is free to add the FK constraint by editing this entity directly when
 * `OrderEntity` lands — no migration is required. Until then this column just
 * records which order (if any) triggered the movement, unenforced at the DB
 * level.
 */
@Entity(
    tableName = "inventory_movement",
    foreignKeys = [
        ForeignKey(
            entity = com.idos.pos.catalog.ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["productId"]),
        Index(value = ["orderId"]),
        Index(value = ["createdAt"]),
    ],
)
@TypeConverters(MovementTypeConverter::class)
data class InventoryMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val type: MovementType,
    val quantity: Int,
    val description: String? = null,
    val orderId: Long? = null,
    val createdBy: String? = null,
    val createdAt: Instant = Instant.now(),
)
