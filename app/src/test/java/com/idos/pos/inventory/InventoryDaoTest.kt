package com.idos.pos.inventory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.DomainException
import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED/GREEN (tasks 5.2, 5.3, Robolectric + in-memory Room): validates
 * [InventoryDao.applyMovementAtomic] — the single stock-mutation primitive —
 * against specs/inventory-ledger/spec.md scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InventoryDaoTest {

    private lateinit var db: PosDatabase
    private lateinit var dao: InventoryDao
    private var productId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.inventoryDao()

        val unitMeasureId = db.unitMeasureDao().insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        productId = db.productDao().insert(
            ProductEntity(
                name = "Widget",
                code = "SKU-500",
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

    // --- Scenario: Receive stock for a product with no prior inventory row ---

    @Test
    fun inMovement_withNoPriorRow_createsRowWithQuantityAsStock() = runBlocking {
        dao.applyMovementAtomic(productId = productId, type = MovementType.IN, quantity = 20)

        assertEquals(20, dao.findByProductId(productId)?.stock)
    }

    // --- Scenario: Sale mutates stock only through the ledger primitive (IN records a movement) ---

    @Test
    fun inMovement_persistsAMovementRow() = runBlocking {
        dao.applyMovementAtomic(productId = productId, type = MovementType.IN, quantity = 20, description = "Initial receipt")

        val movements = dao.movementsForProductFlow(productId).first()
        assertEquals(1, movements.size)
        assertEquals(MovementType.IN, movements[0].type)
        assertEquals(20, movements[0].quantity)
    }

    // --- Scenario: OUT movement within available stock ---

    @Test
    fun outMovement_withinAvailableStock_decrementsStock_andPersistsMovement() = runBlocking {
        dao.applyMovementAtomic(productId = productId, type = MovementType.IN, quantity = 10)

        dao.applyMovementAtomic(productId = productId, type = MovementType.OUT, quantity = 4)

        assertEquals(6, dao.findByProductId(productId)?.stock)
        assertEquals(2, dao.movementsForProductFlow(productId).first().size)
    }

    // --- Scenario: OUT movement exceeding available stock ---

    @Test
    fun outMovement_exceedingAvailableStock_isRejected_andPersistsNoMovement_andLeavesStockUnchanged() = runBlocking {
        dao.applyMovementAtomic(productId = productId, type = MovementType.IN, quantity = 3)

        val exception = try {
            dao.applyMovementAtomic(productId = productId, type = MovementType.OUT, quantity = 5)
            null
        } catch (e: DomainException) {
            e
        }
        checkNotNull(exception) { "expected a DomainException" }

        assertEquals(
            DomainError.InsufficientStock(productId, available = 3, requested = 5),
            exception!!.error,
        )
        assertEquals(3, dao.findByProductId(productId)?.stock)
        assertEquals(1, dao.movementsForProductFlow(productId).first().size) // only the IN movement
    }

    // --- Scenario: OUT movement against a product with no inventory row ---

    @Test
    fun outMovement_withNoInventoryRow_isRejected_andPersistsNoRowNoMovement() = runBlocking {
        val exception = try {
            dao.applyMovementAtomic(productId = productId, type = MovementType.OUT, quantity = 1)
            null
        } catch (e: DomainException) {
            e
        }
        checkNotNull(exception) { "expected a DomainException" }

        assertEquals(
            DomainError.InsufficientStock(productId, available = 0, requested = 1),
            exception!!.error,
        )
        assertNull(dao.findByProductId(productId))
        assertEquals(0, dao.movementsForProductFlow(productId).first().size)
    }

    // --- Scenario: Adjust stock to a new absolute value ---

    @Test
    fun adjustMovement_setsStockToExactQuantity_regardlessOfPriorValue() = runBlocking {
        dao.applyMovementAtomic(productId = productId, type = MovementType.IN, quantity = 15)

        dao.applyMovementAtomic(productId = productId, type = MovementType.ADJUST, quantity = 8)

        assertEquals(8, dao.findByProductId(productId)?.stock)
    }

    @Test
    fun adjustMovement_withNoPriorRow_createsRowWithExactQuantity() = runBlocking {
        dao.applyMovementAtomic(productId = productId, type = MovementType.ADJUST, quantity = 12)

        assertEquals(12, dao.findByProductId(productId)?.stock)
    }

    // --- Scenario: Adjust with a negative quantity ---

    @Test
    fun adjustMovement_withNegativeQuantity_isRejected_andPersistsNoMovement_andLeavesStockUnchanged() = runBlocking {
        dao.applyMovementAtomic(productId = productId, type = MovementType.IN, quantity = 10)

        val exception = try {
            dao.applyMovementAtomic(productId = productId, type = MovementType.ADJUST, quantity = -1)
            null
        } catch (e: DomainException) {
            e
        }
        checkNotNull(exception) { "expected a DomainException" }

        assertTrue(exception!!.error is DomainError.InvalidMovementQuantity)
        assertEquals(-1, (exception.error as DomainError.InvalidMovementQuantity).quantity)
        assertEquals(10, dao.findByProductId(productId)?.stock)
        assertEquals(1, dao.movementsForProductFlow(productId).first().size) // only the IN movement
    }
}
