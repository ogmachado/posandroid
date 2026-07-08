package com.idos.pos.inventory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Running-stock projection row, 1:1 with [com.idos.pos.catalog.ProductEntity]
 * (task 5.1; design.md "Decision: Separate `inventory` table (1:1 product) +
 * keep `inventory_movement` ledger"). `productId` is simultaneously the
 * primary key AND the foreign key, enforcing the 1:1 relationship at the
 * schema level.
 *
 * Mutated EXCLUSIVELY by [InventoryDao.applyMovementAtomic] in the same
 * transaction that appends the corresponding [InventoryMovementEntity] row —
 * never written independently (design.md "Running stock is a denormalized
 * projection of the ledger, updated in the same transaction that appends the
 * movement — never mutated independently"). The one exception is
 * [InventoryRepository.seedZeroStock] / [InventoryRepository.setMinimumStock],
 * which intentionally do NOT append a movement (specs/product-catalog/spec.md
 * "product is persisted with zero stock ... no inventory movement is created
 * as part of product creation"; specs/inventory-ledger/spec.md "Minimum Stock
 * Tracking").
 *
 * **No DB-level `CHECK(stock >= 0)` constraint**: design.md's Room schema
 * table calls for one as "a DB backstop under the app-level guard", but
 * Room 2.6.1 (this project's pinned version — see `app/build.gradle.kts`
 * task 0.4) does not expose an `@Entity(checkConstraints = ...)` parameter;
 * that API landed in a later Room release. The authoritative guard —
 * the one the spec actually requires — is the app-level check inside
 * [InventoryDao.applyMovementAtomic], which is unaffected by this gap. Revisit
 * adding the DB-level backstop if/when Room is upgraded.
 */
@Entity(
    tableName = "inventory",
    foreignKeys = [
        ForeignKey(
            entity = com.idos.pos.catalog.ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class InventoryEntity(
    @PrimaryKey val productId: Long,
    val stock: Int,
    val minimumStock: Int = 0,
    val updatedAt: Instant = Instant.now(),
)
