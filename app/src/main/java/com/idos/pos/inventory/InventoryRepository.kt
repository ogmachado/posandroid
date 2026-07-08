package com.idos.pos.inventory

import com.idos.pos.core.domain.DomainException
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single mutation owner for stock (task 5.4; design.md "Interfaces /
 * Contracts" `InventoryRepository`). Delegates the actual mutation to
 * [InventoryDao.applyMovementAtomic] and converts its thrown
 * [DomainException] into `Result.failure` per design.md's typed-failure
 * convention ("Typed failure via Kotlin Result + sealed DomainError") — no
 * other class in this codebase touches `inventory.stock`.
 *
 * **PIN-gating placement deviation from tasks.md 5.4's literal wording**:
 * tasks.md phrases "the ADJUST path must be routed through `PinGate.require {}`"
 * as part of this repository's task item, but [com.idos.pos.permission.PinGate]
 * is a Compose-state-backed UI primitive (`mutableStateOf`, constructed via
 * `rememberPinGate` inside a composable) that a plain suspend repository
 * cannot depend on without coupling this layer to the Compose runtime. The
 * ONE existing precedent for PIN-gating a mutation in this codebase —
 * [com.idos.pos.catalog.ProductViewModel.submitUpdate] (task 4.6) — wraps the
 * *repository call itself* in `pinGate.require { }` at the ViewModel layer,
 * never inside the repository. [InventoryViewModel] (task 5.5) follows that
 * exact, already-tested precedent instead: ADJUST movements submitted through
 * the ViewModel are wrapped in `pinGate.require { }` there, while
 * [applyMovement] itself stays gate-agnostic and directly callable (e.g. by
 * the future `SalesRepository`'s per-line OUT movements in Phase 8, which
 * must never be PIN-gated).
 */
class InventoryRepository(private val inventoryDao: InventoryDao) {

    fun stockFlow(productId: Long): Flow<Int> = inventoryDao.stockFlow(productId).map { it ?: 0 }

    fun productStockFlow(): Flow<List<ProductStockView>> = inventoryDao.productStockFlow()

    fun movementsForProductFlow(productId: Long): Flow<List<InventoryMovementEntity>> =
        inventoryDao.movementsForProductFlow(productId)

    /**
     * Records an IN/OUT/ADJUST movement through the single mutation owner.
     * PIN-gating (ADJUST) is the caller's responsibility — see class doc.
     */
    suspend fun applyMovement(
        productId: Long,
        type: MovementType,
        quantity: Int,
        description: String? = null,
        orderId: Long? = null,
        createdBy: String? = null,
    ): Result<Unit> = try {
        inventoryDao.applyMovementAtomic(
            productId = productId,
            type = type,
            quantity = quantity,
            description = description,
            orderId = orderId,
            createdBy = createdBy,
        )
        Result.success(Unit)
    } catch (e: DomainException) {
        Result.failure(e)
    }

    /**
     * Sets `minimumStock` independent of any movement, creating the
     * inventory row (with stock 0) if none exists yet
     * (specs/inventory-ledger/spec.md "Minimum Stock Tracking"). Deliberately
     * bypasses [InventoryDao.applyMovementAtomic] — no movement is recorded.
     */
    suspend fun setMinimumStock(productId: Long, minimumStock: Int) {
        val updatedAt = Instant.now()
        val updated = inventoryDao.updateMinimumStockIfExists(productId, minimumStock, updatedAt)
        if (updated == 0) {
            inventoryDao.insertInventory(
                InventoryEntity(productId = productId, stock = 0, minimumStock = minimumStock, updatedAt = updatedAt),
            )
        }
    }

    /**
     * Seeds a zero-stock row for a newly created product, with NO movement —
     * closes [com.idos.pos.catalog.CatalogRepository.createProduct]'s former
     * `TODO(Phase 5 — inventory ledger)` (tasks.md 4.3 deviation note;
     * specs/product-catalog/spec.md "the product is persisted with zero stock
     * in the inventory ledger AND no inventory movement is created as part of
     * product creation").
     */
    suspend fun seedZeroStock(productId: Long) {
        inventoryDao.insertInventory(InventoryEntity(productId = productId, stock = 0, minimumStock = 0))
    }
}
