package com.idos.pos.catalog

import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.DomainException
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow

/**
 * Product catalog repository (task 4.3). Validates the `unitMeasureId`
 * reference and code/barcode uniqueness before persisting — see design.md
 * "File Changes" row for `catalog/...` and specs/product-catalog/spec.md.
 *
 * **Barcode uniqueness has its own [DomainError.DuplicateBarcode] variant**:
 * the originally locked [DomainError] contract (Phase 1, task 1.3) had 7
 * variants and no dedicated "duplicate barcode" case, so an earlier revision
 * of this repository reused [DomainError.DuplicateCode] for both code and
 * barcode collisions. That produced a misleading UI message (barcode value
 * shown under a "code" label), so [DomainError.DuplicateBarcode] was added
 * as an 8th variant — confirmed with the user as the correct fix over leaving
 * the ambiguity as tech debt.
 *
 * **Phase 4 / Phase 5 sequencing note**: design.md's "File Changes" table says
 * product creation "Seeds a zero-stock `inventory` row on create (no
 * movement)". The `inventory` table/DAO do not exist yet — they are created in
 * Phase 5 (`inventory/InventoryEntity.kt` + `InventoryDao`, still pending in
 * tasks.md as of this apply batch). Inventing that schema here would violate
 * Phase 5's ownership of it, so the seed call is a marked TODO in
 * [createProduct] instead — wire it once `InventoryRepository`/`InventoryDao`
 * land (PR 4 per the Suggested Work Units table).
 */
class CatalogRepository(
    private val productDao: ProductDao,
    private val unitMeasureDao: UnitMeasureDao,
) {

    fun findAllFlow(): Flow<List<ProductEntity>> = productDao.findAllFlow()

    suspend fun findById(id: Long): ProductEntity? = productDao.findById(id)

    suspend fun findByBarcode(barcode: String): ProductEntity? = productDao.findByBarcode(barcode)

    suspend fun createProduct(
        name: String,
        code: String,
        barcode: String?,
        price: BigDecimal,
        costPrice: BigDecimal,
        unitMeasureId: Long,
        categoryId: Long? = null,
    ): Result<Long> {
        val normalizedBarcode = normalizeBarcode(barcode)

        validateUnitMeasure(unitMeasureId)?.let { return Result.failure(it) }
        validateCodeUnique(code, excludeId = null)?.let { return Result.failure(it) }
        validateBarcodeUnique(normalizedBarcode, excludeId = null)?.let { return Result.failure(it) }

        val id = productDao.insert(
            ProductEntity(
                name = name,
                code = code,
                barcode = normalizedBarcode,
                price = price,
                costPrice = costPrice,
                unitMeasureId = unitMeasureId,
                categoryId = categoryId,
            ),
        )

        // TODO(Phase 5 — inventory ledger): seed a zero-stock `inventory` row
        // for this product id here, with NO movement, e.g.:
        //   inventoryRepository.seedZeroStock(id)
        // Deferred until `InventoryRepository`/`InventoryDao` exist (tasks.md
        // Phase 5). Do not invent the inventory schema from this module.

        return Result.success(id)
    }

    suspend fun updateProduct(
        id: Long,
        name: String,
        code: String,
        barcode: String?,
        price: BigDecimal,
        costPrice: BigDecimal,
        unitMeasureId: Long,
        categoryId: Long? = null,
    ): Result<Unit> {
        val normalizedBarcode = normalizeBarcode(barcode)

        validateUnitMeasure(unitMeasureId)?.let { return Result.failure(it) }
        validateCodeUnique(code, excludeId = id)?.let { return Result.failure(it) }
        validateBarcodeUnique(normalizedBarcode, excludeId = id)?.let { return Result.failure(it) }

        productDao.update(
            ProductEntity(
                id = id,
                name = name,
                code = code,
                barcode = normalizedBarcode,
                price = price,
                costPrice = costPrice,
                unitMeasureId = unitMeasureId,
                categoryId = categoryId,
            ),
        )
        return Result.success(Unit)
    }

    private fun normalizeBarcode(barcode: String?): String? =
        barcode?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun validateUnitMeasure(unitMeasureId: Long): DomainException? {
        val exists = unitMeasureDao.findById(unitMeasureId) != null
        return if (exists) null else DomainException(DomainError.UnitMeasureNotFound(unitMeasureId))
    }

    private suspend fun validateCodeUnique(code: String, excludeId: Long?): DomainException? {
        val existing = productDao.findByCode(code) ?: return null
        return if (existing.id == excludeId) null else DomainException(DomainError.DuplicateCode(code))
    }

    private suspend fun validateBarcodeUnique(barcode: String?, excludeId: Long?): DomainException? {
        if (barcode == null) return null
        val existing = productDao.findByBarcode(barcode) ?: return null
        return if (existing.id == excludeId) null else DomainException(DomainError.DuplicateBarcode(barcode))
    }
}
