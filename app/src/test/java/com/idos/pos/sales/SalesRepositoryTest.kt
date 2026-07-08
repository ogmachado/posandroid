package com.idos.pos.sales

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.db.PosDatabaseSeeder
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import com.idos.pos.inventory.MovementType
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
 * RED/GREEN (task 8.2/8.3, Robolectric + in-memory Room): validates
 * [SalesRepository.createOrder] — the critical atomic path — against
 * specs/sales-order/spec.md scenarios. This is the highest-value test class
 * in the whole app per design.md's Testing Strategy ("the atomic order-create
 * ... are pure, deterministic, and high-value — write these tests first").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SalesRepositoryTest {

    private lateinit var db: PosDatabase
    private lateinit var repository: SalesRepository
    private var productAId: Long = 0
    private var productBId: Long = 0
    private var cashMethodId: Long = 0
    private var transferMethodId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .addCallback(PosDatabaseSeeder.callback)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase // force onCreate/seed

        repository = SalesRepository(db, db.salesDao(), db.inventoryDao(), db.paymentMethodDao(), db.cashSessionDao())

        val unitMeasureId = db.unitMeasureDao().insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        productAId = db.productDao().insert(
            ProductEntity(name = "Product A", code = "A", price = BigDecimal("10.00"), costPrice = BigDecimal("5.00"), unitMeasureId = unitMeasureId),
        )
        productBId = db.productDao().insert(
            ProductEntity(name = "Product B", code = "B", price = BigDecimal("20.00"), costPrice = BigDecimal("8.00"), unitMeasureId = unitMeasureId),
        )
        db.inventoryDao().applyMovementAtomic(productAId, MovementType.IN, 10)
        db.inventoryDao().applyMovementAtomic(productBId, MovementType.IN, 5)

        cashMethodId = db.paymentMethodDao().findByCode("CASH")!!.id
        transferMethodId = db.paymentMethodDao().findByCode("TRANSFER")!!.id
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun cartLine(productId: Long, quantity: Int, price: BigDecimal, costPrice: BigDecimal) =
        CartLine(productId, "Product", quantity, price, costPrice)

    // --- Requirement: Atomic Order Creation ---

    @Test
    fun createOrder_withTwoValidLines_persistsOrderAndLines_andDecrementsBothStocks() = runBlocking {
        // Given: product A stock 10, product B stock 5, an open session
        db.cashSessionDao().openAtomic(BigDecimal("100.00"))
        val cart = Cart(
            listOf(
                cartLine(productAId, 2, BigDecimal("10.00"), BigDecimal("5.00")),
                cartLine(productBId, 1, BigDecimal("20.00"), BigDecimal("8.00")),
            ),
        )

        // When: an order is created with line A x2 and line B x1
        val result = repository.createOrder(cart, payment = BigDecimal("40.00"), methodId = null)

        // Then: the order and both lines are persisted, and stock decrements correctly
        assertTrue(result.isSuccess)
        val orderId = result.getOrThrow()
        val lines = db.salesDao().findLinesForOrder(orderId)
        assertEquals(2, lines.size)
        assertEquals(8, db.inventoryDao().findByProductId(productAId)?.stock)
        assertEquals(4, db.inventoryDao().findByProductId(productBId)?.stock)

        // AND exactly two OUT movements are recorded, one per line (plus the setup IN each)
        val movementsA = db.inventoryDao().movementsForProductFlow(productAId).first()
        val movementsB = db.inventoryDao().movementsForProductFlow(productBId).first()
        assertEquals(2, movementsA.size) // IN (setup) + OUT (sale)
        assertEquals(2, movementsB.size)
    }

    @Test
    fun createOrder_withOneLineFailingStockValidation_rollsBackWholeOrder_andBothProductsStockUnchanged() = runBlocking {
        // Given: product A stock 10, product B stock reduced to 1
        db.inventoryDao().applyMovementAtomic(productBId, MovementType.ADJUST, 1)
        db.cashSessionDao().openAtomic(BigDecimal("100.00"))
        val cart = Cart(
            listOf(
                cartLine(productAId, 2, BigDecimal("10.00"), BigDecimal("5.00")),
                cartLine(productBId, 5, BigDecimal("20.00"), BigDecimal("8.00")),
            ),
        )

        // When: an order is created with line A x2 (valid) and line B x5 (exceeds stock)
        val result = repository.createOrder(cart, payment = BigDecimal("120.00"), methodId = null)

        // Then: the entire order creation fails with an insufficient-stock error
        assertTrue(result.isFailure)
        assertTrue(result.domainErrorOrNull() is DomainError.InsufficientStock)

        // AND no order and no lines are persisted at all, and stock for BOTH
        // products is unchanged from their setup values — including product A,
        // whose line came first and would otherwise have succeeded on its own
        // (the exact partial-rollback bug this test guards against)
        assertEquals(10, db.inventoryDao().findByProductId(productAId)?.stock)
        assertEquals(1, db.inventoryDao().findByProductId(productBId)?.stock)
        assertEquals(1, db.inventoryDao().movementsForProductFlow(productAId).first().size) // only the setup IN — no OUT
        assertEquals(2, db.inventoryDao().movementsForProductFlow(productBId).first().size) // setup IN + ADJUST — no OUT
        assertEquals(0, db.salesDao().findAllFlow().first().size)
    }

    // --- Requirement: Per-Day Sequential Order Number ---

    @Test
    fun createOrder_firstOrderOfTheDay_isOrderNumberOne() = runBlocking {
        db.cashSessionDao().openAtomic(BigDecimal("100.00"))
        val cart = Cart(listOf(cartLine(productAId, 1, BigDecimal("10.00"), BigDecimal("5.00"))))

        val result = repository.createOrder(cart, payment = BigDecimal("10.00"), methodId = null)

        val order = db.salesDao().findById(result.getOrThrow())
        assertEquals(1, order?.orderNumber)
    }

    @Test
    fun createOrder_secondOrderOfTheSameDay_isOrderNumberTwo() = runBlocking {
        db.cashSessionDao().openAtomic(BigDecimal("100.00"))
        val cart = Cart(listOf(cartLine(productAId, 1, BigDecimal("10.00"), BigDecimal("5.00"))))
        repository.createOrder(cart, payment = BigDecimal("10.00"), methodId = null)

        val result = repository.createOrder(cart, payment = BigDecimal("10.00"), methodId = null)

        val order = db.salesDao().findById(result.getOrThrow())
        assertEquals(2, order?.orderNumber)
    }

    @Test
    fun createOrder_dailySequence_resetsAcrossLocalDayBoundary_notNaiveUtcDayBoundary() = runBlocking {
        // Given: zone -05:00. Yesterday's order (orderNumber 7) was created at an
        // instant that is UTC calendar day 2026-07-08 (same UTC day as "now"),
        // but LOCAL calendar day 2026-07-07 (zone -05:00) — a naive
        // Instant.now().truncatedTo(DAYS) (UTC) implementation would incorrectly
        // treat it as part of "today" and continue the sequence at 8.
        val zone = ZoneOffset.ofHours(-5)
        val yesterdayCreatedAt = Instant.parse("2026-07-08T03:00:00Z") // local: 2026-07-07T22:00 (yesterday)
        val fixedNow = Instant.parse("2026-07-08T05:30:00Z") // local: 2026-07-08T00:30 (today, just past local midnight)
        val clock = Clock.fixed(fixedNow, zone)

        val sessionId = db.cashSessionDao().openAtomic(BigDecimal("100.00"))
        db.salesDao().insertOrderWithLines(
            OrderEntity(
                orderNumber = 7,
                sessionId = sessionId,
                paymentMethodId = cashMethodId,
                total = BigDecimal("10.00"),
                payment = BigDecimal("10.00"),
                changeAmount = BigDecimal.ZERO,
                createdAt = yesterdayCreatedAt,
            ),
            emptyList(),
        )

        val fixedClockRepository = SalesRepository(
            db,
            db.salesDao(),
            db.inventoryDao(),
            db.paymentMethodDao(),
            db.cashSessionDao(),
            clock = clock,
        )
        val cart = Cart(listOf(cartLine(productAId, 1, BigDecimal("10.00"), BigDecimal("5.00"))))

        // When: an order is created "now" (today, local)
        val result = fixedClockRepository.createOrder(cart, payment = BigDecimal("10.00"), methodId = null)

        // Then: order number resets to 1, not 8 — proves LOCAL day boundary is
        // used, not naive UTC truncation
        assertTrue(result.isSuccess)
        val order = db.salesDao().findById(result.getOrThrow())
        assertEquals(1, order?.orderNumber)
    }

    // --- Requirement: Payment Method Resolution Defaults to CASH ---

    @Test
    fun createOrder_withNoPaymentMethodSpecified_defaultsToCash() = runBlocking {
        db.cashSessionDao().openAtomic(BigDecimal("100.00"))
        val cart = Cart(listOf(cartLine(productAId, 1, BigDecimal("10.00"), BigDecimal("5.00"))))

        val result = repository.createOrder(cart, payment = BigDecimal("10.00"), methodId = null)

        val order = db.salesDao().findById(result.getOrThrow())
        assertEquals(cashMethodId, order?.paymentMethodId)
    }

    @Test
    fun createOrder_withExplicitPaymentMethod_usesTheSpecifiedMethod() = runBlocking {
        db.cashSessionDao().openAtomic(BigDecimal("100.00"))
        val cart = Cart(listOf(cartLine(productAId, 1, BigDecimal("10.00"), BigDecimal("5.00"))))

        val result = repository.createOrder(cart, payment = BigDecimal("10.00"), methodId = transferMethodId)

        val order = db.salesDao().findById(result.getOrThrow())
        assertEquals(transferMethodId, order?.paymentMethodId)
    }

    @Test
    fun createOrder_withNonExistentPaymentMethod_returnsFailure_withPaymentMethodNotFoundError() = runBlocking {
        db.cashSessionDao().openAtomic(BigDecimal("100.00"))
        val cart = Cart(listOf(cartLine(productAId, 1, BigDecimal("10.00"), BigDecimal("5.00"))))

        val result = repository.createOrder(cart, payment = BigDecimal("10.00"), methodId = 999L)

        assertTrue(result.isFailure)
        assertEquals(DomainError.PaymentMethodNotFound(999L), result.domainErrorOrNull())
        assertEquals(0, db.salesDao().findAllFlow().first().size)
    }

    // --- Requirement: Order Requires an Open Cash Session ---

    @Test
    fun createOrder_withNoOpenSession_returnsFailure_withNoOpenSessionError() = runBlocking {
        val cart = Cart(listOf(cartLine(productAId, 1, BigDecimal("10.00"), BigDecimal("5.00"))))

        val result = repository.createOrder(cart, payment = BigDecimal("10.00"), methodId = null)

        assertTrue(result.isFailure)
        assertEquals(DomainError.NoOpenSession, result.domainErrorOrNull())
        assertEquals(10, db.inventoryDao().findByProductId(productAId)?.stock)
        assertEquals(0, db.salesDao().findAllFlow().first().size)
    }

    @Test
    fun createOrder_withOpenSession_linksOrderToThatSession() = runBlocking {
        val sessionId = db.cashSessionDao().openAtomic(BigDecimal("100.00"))
        val cart = Cart(listOf(cartLine(productAId, 1, BigDecimal("10.00"), BigDecimal("5.00"))))

        val result = repository.createOrder(cart, payment = BigDecimal("10.00"), methodId = null)

        val order = db.salesDao().findById(result.getOrThrow())
        assertEquals(sessionId, order?.sessionId)
    }
}
