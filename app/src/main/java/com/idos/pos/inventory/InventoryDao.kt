package com.idos.pos.inventory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.DomainException
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Projection row for the inventory list/movement-entry screens — LEFT JOINs
 * `product` with its (possibly absent) `inventory` row so a product with no
 * movement yet still shows up with stock 0 (task 5.1/5.5). Deliberately a
 * plain POJO (not [com.idos.pos.catalog.ProductEntity] /[InventoryEntity])
 * since it spans both tables.
 */
data class ProductStockView(
    val productId: Long,
    val productName: String,
    val productCode: String,
    val stock: Int,
    val minimumStock: Int,
)

/**
 * `@Dao abstract class` (not `interface`) so [applyMovementAtomic] can be a
 * concrete `@Transaction`-annotated method that calls the other abstract
 * DAO methods on `this` — the standard Room+Kotlin pattern for a
 * multi-statement single-transaction primitive (task 5.1/5.3).
 */
@Dao
abstract class InventoryDao {

    @Query("SELECT * FROM inventory WHERE productId = :productId")
    abstract suspend fun findByProductId(productId: Long): InventoryEntity?

    @Query("SELECT stock FROM inventory WHERE productId = :productId")
    abstract fun stockFlow(productId: Long): Flow<Int?>

    @Query(
        """
        SELECT p.id AS productId, p.name AS productName, p.code AS productCode,
               COALESCE(i.stock, 0) AS stock, COALESCE(i.minimumStock, 0) AS minimumStock
        FROM product p LEFT JOIN inventory i ON i.productId = p.id
        ORDER BY p.name
        """,
    )
    abstract fun productStockFlow(): Flow<List<ProductStockView>>

    /**
     * Single-row counterpart to [productStockFlow] (nav-shell addition — see
     * `nav/PosNavHost.kt`'s movement-form route): the inventory list screen's
     * click callback only carries a `productId`, so the movement-form route
     * needs a one-shot [ProductStockView] lookup by id rather than re-deriving
     * it from the full list (which would also race the list's own Flow on a
     * freshly-created ViewModel instance for that route).
     */
    @Query(
        """
        SELECT p.id AS productId, p.name AS productName, p.code AS productCode,
               COALESCE(i.stock, 0) AS stock, COALESCE(i.minimumStock, 0) AS minimumStock
        FROM product p LEFT JOIN inventory i ON i.productId = p.id
        WHERE p.id = :productId
        """,
    )
    abstract suspend fun findProductStockView(productId: Long): ProductStockView?

    @Query("SELECT * FROM inventory_movement WHERE productId = :productId ORDER BY createdAt DESC")
    abstract fun movementsForProductFlow(productId: Long): Flow<List<InventoryMovementEntity>>

    @Insert
    abstract suspend fun insertInventory(entity: InventoryEntity)

    @Update
    abstract suspend fun updateInventory(entity: InventoryEntity)

    /**
     * Returns the number of rows updated (0 or 1) so callers can fall back to
     * an insert when no inventory row exists yet — used by
     * [InventoryRepository.setMinimumStock] to keep minimum-stock editable
     * independent of any movement having ever been recorded
     * (specs/inventory-ledger/spec.md "Minimum Stock Tracking").
     */
    @Query("UPDATE inventory SET minimumStock = :minimumStock, updatedAt = :updatedAt WHERE productId = :productId")
    abstract suspend fun updateMinimumStockIfExists(productId: Long, minimumStock: Int, updatedAt: Instant): Int

    @Insert
    abstract suspend fun insertMovement(entity: InventoryMovementEntity): Long

    /**
     * The single stock-mutation primitive (task 5.3; design.md "Transaction
     * boundaries"). Appends the movement row + creates-or-updates
     * [InventoryEntity.stock] in ONE DAO transaction: Room wraps this whole
     * method body — which itself calls the abstract `@Insert`/`@Update`/`@Query`
     * methods above — in a single SQLite transaction because of the
     * `@Transaction` annotation.
     *
     * **The negative-stock guard lives HERE** (design.md: "so it applies
     * uniformly to every caller — sale line or standalone manual OUT"): an
     * `OUT` that would take stock negative, or that targets a product with no
     * inventory row yet (implicit stock 0), throws [DomainException] wrapping
     * [DomainError.InsufficientStock] and Room rolls back the whole
     * transaction — no movement row and no inventory mutation survive
     * (specs/inventory-ledger/spec.md "OUT Movement Decreases Stock and MUST
     * NOT Go Negative").
     *
     * A negative `quantity` is rejected up front for every movement type via
     * [DomainError.InvalidMovementQuantity] — the spec only requires this for
     * `ADJUST` ("Adjust with a negative quantity"), but a negative `IN`/`OUT`
     * quantity has no legitimate business meaning either (it would silently
     * invert the direction of the movement), so the guard is applied
     * uniformly rather than scoped to `ADJUST` alone.
     */
    @Transaction
    open suspend fun applyMovementAtomic(
        productId: Long,
        type: MovementType,
        quantity: Int,
        description: String? = null,
        orderId: Long? = null,
        createdBy: String? = null,
        createdAt: Instant = Instant.now(),
    ): Long {
        if (quantity < 0) {
            throw DomainException(DomainError.InvalidMovementQuantity(quantity))
        }

        val current = findByProductId(productId)
        val currentStock = current?.stock ?: 0

        val newStock = when (type) {
            MovementType.IN -> currentStock + quantity
            MovementType.OUT -> {
                val result = currentStock - quantity
                if (result < 0) {
                    throw DomainException(
                        DomainError.InsufficientStock(
                            productId = productId,
                            available = currentStock,
                            requested = quantity,
                        ),
                    )
                }
                result
            }
            MovementType.ADJUST -> quantity
        }

        if (current == null) {
            insertInventory(InventoryEntity(productId = productId, stock = newStock, minimumStock = 0, updatedAt = createdAt))
        } else {
            updateInventory(current.copy(stock = newStock, updatedAt = createdAt))
        }

        return insertMovement(
            InventoryMovementEntity(
                productId = productId,
                type = type,
                quantity = quantity,
                description = description,
                orderId = orderId,
                createdBy = createdBy,
                createdAt = createdAt,
            ),
        )
    }
}
