package com.idos.pos.sales

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.db.PosDatabaseSeeder
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED/GREEN (task 8.2/8.1, Robolectric + in-memory Room): validates
 * [SalesDao.insertOrderWithLines] persistence and
 * [SalesDao.maxOrderNumberInRange]'s range-boundary query directly — the
 * cross-DAO atomicity/rollback scenarios belong to [SalesRepositoryTest]
 * instead (mirrors `InventoryDaoTest` vs `InventoryRepositoryTest`'s split).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SalesDaoTest {

    private lateinit var db: PosDatabase
    private lateinit var dao: SalesDao
    private var sessionId: Long = 0
    private var cashMethodId: Long = 0
    private var productId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .addCallback(PosDatabaseSeeder.callback)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase // force onCreate/seed
        dao = db.salesDao()

        sessionId = db.cashSessionDao().openAtomic(BigDecimal("100.00"))
        cashMethodId = db.paymentMethodDao().findByCode("CASH")!!.id

        val unitMeasureId = db.unitMeasureDao().insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        productId = db.productDao().insert(
            ProductEntity(
                name = "Widget",
                code = "SKU-800",
                price = BigDecimal("10.00"),
                costPrice = BigDecimal("4.00"),
                unitMeasureId = unitMeasureId,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun order(orderNumber: Int, createdAt: Instant = Instant.now()) = OrderEntity(
        orderNumber = orderNumber,
        sessionId = sessionId,
        paymentMethodId = cashMethodId,
        total = BigDecimal("10.00"),
        payment = BigDecimal("10.00"),
        changeAmount = BigDecimal.ZERO,
        createdAt = createdAt,
    )

    // --- insertOrderWithLines ---

    @Test
    fun insertOrderWithLines_persistsOrderAndAllLines() = runBlocking {
        val lines = listOf(
            CartLine(productId, "Widget", 2, BigDecimal("10.00"), BigDecimal("4.00")),
        )

        val orderId = dao.insertOrderWithLines(order(orderNumber = 1), lines)

        val persistedOrder = dao.findById(orderId)
        assertEquals(1, persistedOrder?.orderNumber)
        val persistedLines = dao.findLinesForOrder(orderId)
        assertEquals(1, persistedLines.size)
        assertEquals(2, persistedLines[0].quantity)
        assertEquals(BigDecimal("20.00"), persistedLines[0].subtotal)
    }

    @Test
    fun insertOrderWithLines_withNoLines_persistsOrderOnly() = runBlocking {
        val orderId = dao.insertOrderWithLines(order(orderNumber = 1), emptyList())

        assertEquals(0, dao.findLinesForOrder(orderId).size)
    }

    // --- maxOrderNumberInRange (specs/sales-order/spec.md "Per-Day Sequential Order Number") ---

    @Test
    fun maxOrderNumberInRange_withNoOrdersInRange_returnsNull() = runBlocking {
        val result = dao.maxOrderNumberInRange(
            Instant.parse("2026-07-08T00:00:00Z"),
            Instant.parse("2026-07-09T00:00:00Z"),
        )

        assertNull(result)
    }

    @Test
    fun maxOrderNumberInRange_returnsHighestOrderNumber_amongOrdersWithinRange_excludingOutsideRange() = runBlocking {
        // GIVEN order #7 created yesterday, and orders #1/#2 created today
        dao.insertOrderWithLines(order(orderNumber = 7, createdAt = Instant.parse("2026-07-07T12:00:00Z")), emptyList())
        dao.insertOrderWithLines(order(orderNumber = 1, createdAt = Instant.parse("2026-07-08T09:00:00Z")), emptyList())
        dao.insertOrderWithLines(order(orderNumber = 2, createdAt = Instant.parse("2026-07-08T14:00:00Z")), emptyList())

        // WHEN querying only today's range
        val result = dao.maxOrderNumberInRange(
            Instant.parse("2026-07-08T00:00:00Z"),
            Instant.parse("2026-07-09T00:00:00Z"),
        )

        // THEN only today's max (2) is returned — yesterday's #7 is excluded
        assertEquals(2, result)
    }

    @Test
    fun maxOrderNumberInRange_endExclusive_excludesAnOrderCreatedExactlyAtTheBoundary() = runBlocking {
        dao.insertOrderWithLines(
            order(orderNumber = 5, createdAt = Instant.parse("2026-07-09T00:00:00Z")),
            emptyList(),
        )

        val result = dao.maxOrderNumberInRange(
            Instant.parse("2026-07-08T00:00:00Z"),
            Instant.parse("2026-07-09T00:00:00Z"),
        )

        assertNull(result)
    }
}
