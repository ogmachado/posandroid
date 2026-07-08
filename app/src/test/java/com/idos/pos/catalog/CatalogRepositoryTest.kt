package com.idos.pos.catalog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import com.idos.pos.inventory.InventoryRepository
import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED/GREEN (tasks 4.2, 4.3, 4.4, Robolectric + in-memory Room): validates
 * [CatalogRepository]'s uniqueness/FK guards and `findByBarcode` lookup against
 * specs/product-catalog/spec.md scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogRepositoryTest {

    private lateinit var db: PosDatabase
    private lateinit var repository: CatalogRepository
    private var unitMeasureId: Long = 0
    private var categoryId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CatalogRepository(db.productDao(), db.unitMeasureDao(), InventoryRepository(db.inventoryDao()))

        unitMeasureId = db.unitMeasureDao().insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        categoryId = db.categoryDao().insert(CategoryEntity(code = "CAT", name = "Category"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Scenario: Create a product with valid data ---

    @Test
    fun createProduct_withValidData_persistsAndReturnsId() = runBlocking {
        val result = repository.createProduct(
            name = "Widget",
            code = "SKU-001",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = categoryId,
        )

        assertTrue(result.isSuccess)
        val product = db.productDao().findById(result.getOrThrow())
        assertEquals("SKU-001", product?.code)
    }

    // --- Scenario: Create a product with valid data (zero-stock seed, Phase 5) ---

    @Test
    fun createProduct_seedsAZeroStockInventoryRow_withNoMovement() = runBlocking {
        val id = repository.createProduct(
            name = "Widget",
            code = "SKU-005",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = categoryId,
        ).getOrThrow()

        val inventory = db.inventoryDao().findByProductId(id)
        assertEquals(0, inventory?.stock)
        assertEquals(0, db.inventoryDao().movementsForProductFlow(id).first().size)
    }

    // --- Scenario: Create a product with a duplicate code ---

    @Test
    fun createProduct_withDuplicateCode_isRejected_andNoProductPersisted() = runBlocking {
        repository.createProduct(
            name = "Widget",
            code = "SKU-001",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )

        val result = repository.createProduct(
            name = "Other Widget",
            code = "SKU-001",
            barcode = null,
            price = BigDecimal("12.00"),
            costPrice = BigDecimal("6.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )

        assertTrue(result.isFailure)
        assertEquals(DomainError.DuplicateCode("SKU-001"), result.domainErrorOrNull())
        assertEquals(1, db.productDao().findAllOnce().size)
    }

    // --- Scenario: Create a product referencing a missing unit of measure ---

    @Test
    fun createProduct_withMissingUnitMeasure_isRejected() = runBlocking {
        val result = repository.createProduct(
            name = "Widget",
            code = "SKU-002",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = 99L,
            categoryId = null,
        )

        assertTrue(result.isFailure)
        assertEquals(DomainError.UnitMeasureNotFound(99L), result.domainErrorOrNull())
    }

    // --- Scenario: Create a product with no barcode ---

    @Test
    fun createProduct_withNoBarcode_persistsAbsentBarcode_andDoesNotBlockAnotherAbsentBarcode() = runBlocking {
        val first = repository.createProduct(
            name = "Widget A",
            code = "SKU-010",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )
        val second = repository.createProduct(
            name = "Widget B",
            code = "SKU-011",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertNull(db.productDao().findById(first.getOrThrow())?.barcode)
        assertNull(db.productDao().findById(second.getOrThrow())?.barcode)
    }

    // --- Scenario: Create a product with a blank barcode ---

    @Test
    fun createProduct_withBlankBarcode_isNormalizedToAbsent() = runBlocking {
        val result = repository.createProduct(
            name = "Widget",
            code = "SKU-020",
            barcode = "   ",
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )

        assertTrue(result.isSuccess)
        val product = db.productDao().findById(result.getOrThrow())
        assertNull(product?.barcode)
    }

    // --- Scenario: Create a product with a duplicate barcode ---

    @Test
    fun createProduct_withDuplicateBarcode_isRejected() = runBlocking {
        repository.createProduct(
            name = "Widget A",
            code = "SKU-030",
            barcode = "5901234123457",
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )

        val result = repository.createProduct(
            name = "Widget B",
            code = "SKU-031",
            barcode = "5901234123457",
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )

        assertTrue(result.isFailure)
        assertEquals(DomainError.DuplicateBarcode("5901234123457"), result.domainErrorOrNull())
    }

    // --- Scenario: Update keeping the same code ---

    @Test
    fun updateProduct_keepingSameCode_succeeds() = runBlocking {
        val id = repository.createProduct(
            name = "Widget",
            code = "SKU-001",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        ).getOrThrow()

        val result = repository.updateProduct(
            id = id,
            name = "Widget Renamed",
            code = "SKU-001",
            barcode = null,
            price = BigDecimal("11.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )

        assertTrue(result.isSuccess)
        assertEquals("Widget Renamed", db.productDao().findById(id)?.name)
    }

    // --- Scenario: Update to a code owned by another product ---

    @Test
    fun updateProduct_toCodeOwnedByAnotherProduct_isRejected() = runBlocking {
        repository.createProduct(
            name = "Widget A",
            code = "SKU-001",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )
        val productBId = repository.createProduct(
            name = "Widget B",
            code = "SKU-002",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        ).getOrThrow()

        val result = repository.updateProduct(
            id = productBId,
            name = "Widget B",
            code = "SKU-001",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )

        assertTrue(result.isFailure)
        assertEquals(DomainError.DuplicateCode("SKU-001"), result.domainErrorOrNull())
    }

    // --- Scenario: Update a product's barcode to one already used by another product ---

    @Test
    fun updateProduct_barcodeToOneOwnedByAnotherProduct_isRejected_butKeepingOwnBarcodeSucceeds() = runBlocking {
        val productAId = repository.createProduct(
            name = "Widget A",
            code = "SKU-A",
            barcode = "111",
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        ).getOrThrow()
        val productBId = repository.createProduct(
            name = "Widget B",
            code = "SKU-B",
            barcode = "222",
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        ).getOrThrow()

        val rejected = repository.updateProduct(
            id = productBId,
            name = "Widget B",
            code = "SKU-B",
            barcode = "111",
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )
        assertTrue(rejected.isFailure)
        assertEquals(DomainError.DuplicateBarcode("111"), rejected.domainErrorOrNull())

        // Keeping product A's own current barcode unchanged must still succeed.
        val keptOwn = repository.updateProduct(
            id = productAId,
            name = "Widget A Renamed",
            code = "SKU-A",
            barcode = "111",
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        )
        assertTrue(keptOwn.isSuccess)
    }

    // --- Requirement: Barcode Lookup via Camera Scan (task 4.4) ---

    @Test
    fun findByBarcode_withExistingBarcode_returnsTheMatchingProduct() = runBlocking {
        val id = repository.createProduct(
            name = "Widget",
            code = "SKU-040",
            barcode = "5901234123457",
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        ).getOrThrow()

        val found = repository.findByBarcode("5901234123457")

        assertEquals(id, found?.id)
    }

    @Test
    fun findByBarcode_withNoMatch_returnsNull() = runBlocking {
        val found = repository.findByBarcode("000000000000")

        assertNull(found)
    }
}

/** Test-only convenience: snapshot the current `findAllFlow()` emission. */
private suspend fun ProductDao.findAllOnce(): List<ProductEntity> =
    findAllFlow().first()
