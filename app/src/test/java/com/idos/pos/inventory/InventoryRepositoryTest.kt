package com.idos.pos.inventory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED/GREEN (task 5.4, Robolectric + in-memory Room + Turbine): validates
 * [InventoryRepository]'s `Result`/[DomainError] wrapping around
 * [InventoryDao.applyMovementAtomic], the live [InventoryRepository.stockFlow],
 * [InventoryRepository.setMinimumStock], and [InventoryRepository.seedZeroStock].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InventoryRepositoryTest {

    private lateinit var db: PosDatabase
    private lateinit var repository: InventoryRepository
    private var productId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = InventoryRepository(db.inventoryDao())

        val unitMeasureId = db.unitMeasureDao().insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        productId = db.productDao().insert(
            ProductEntity(
                name = "Widget",
                code = "SKU-600",
                barcode = null,
                price = BigDecimal("10.00"),
                costPrice = BigDecimal("5.00"),
                unitMeasureId = unitMeasureId,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- applyMovement wraps the DAO primitive as Result ---

    @Test
    fun applyMovement_withInType_succeeds_andStockFlowEmitsNewValue() = runBlocking {
        repository.stockFlow(productId).test {
            assertEquals(0, awaitItem())

            val result = repository.applyMovement(productId, MovementType.IN, 20)

            assertTrue(result.isSuccess)
            assertEquals(20, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun applyMovement_outExceedingStock_returnsFailure_withInsufficientStockError() = runBlocking {
        repository.applyMovement(productId, MovementType.IN, 3)

        val result = repository.applyMovement(productId, MovementType.OUT, 5)

        assertTrue(result.isFailure)
        assertEquals(
            DomainError.InsufficientStock(productId, available = 3, requested = 5),
            result.domainErrorOrNull(),
        )
    }

    @Test
    fun applyMovement_adjustWithNegativeQuantity_returnsFailure_withInvalidMovementQuantityError() = runBlocking {
        val result = repository.applyMovement(productId, MovementType.ADJUST, -1)

        assertTrue(result.isFailure)
        assertEquals(DomainError.InvalidMovementQuantity(-1), result.domainErrorOrNull())
    }

    // --- Requirement: Minimum Stock Tracking ---

    @Test
    fun setMinimumStock_onProductWithNoInventoryRowYet_createsRowWithZeroStock() = runBlocking {
        repository.setMinimumStock(productId, 5)

        val row = db.inventoryDao().findByProductId(productId)
        assertEquals(5, row?.minimumStock)
        assertEquals(0, row?.stock)
    }

    @Test
    fun setMinimumStock_onProductWithExistingStock_updatesThresholdWithoutChangingStock() = runBlocking {
        repository.applyMovement(productId, MovementType.IN, 8)

        repository.setMinimumStock(productId, 5)

        val row = db.inventoryDao().findByProductId(productId)
        assertEquals(5, row?.minimumStock)
        assertEquals(8, row?.stock)
    }

    // --- seedZeroStock (closes CatalogRepository's Phase 4 TODO) ---

    @Test
    fun seedZeroStock_createsRowWithZeroStock_andNoMovement() = runBlocking {
        repository.seedZeroStock(productId)

        val row = db.inventoryDao().findByProductId(productId)
        assertEquals(0, row?.stock)
        assertEquals(0, db.inventoryDao().movementsForProductFlow(productId).first().size)
    }
}
