package com.idos.pos.cashsession

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.db.PosDatabaseSeeder
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import com.idos.pos.inventory.MovementType
import com.idos.pos.sales.Cart
import com.idos.pos.sales.CartLine
import com.idos.pos.sales.SalesRepository
import java.math.BigDecimal
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
 * RED/GREEN (tasks 7.3, 7.4, 8.3; Robolectric + in-memory Room + Turbine):
 * validates [CashSessionRepository]'s `Result`/[DomainError] wrapping around
 * [CashSessionDao]'s atomic primitives, and the live
 * [CashSessionRepository.currentSessionFlow].
 *
 * **Phase 7 → Phase 8 seam CLOSED (tasks.md 7.4/8.3 note)**: the two
 * cash-sale-inclusive `expectedBalance` scenarios deferred from Phase 7
 * ("Close with deposits, a withdrawal, and one cash sale" and "Close with a
 * non-cash-affecting sale excluded") are covered below, now that
 * [SalesRepository]/`orders` exist. [CashSessionDaoTest] still only covers
 * the movements-only arithmetic in isolation — see that class's doc.
 *
 * The in-memory database now seeds via [PosDatabaseSeeder] (unlike the
 * pre-Phase-8 version of this class) so `CASH`/`TRANSFER` payment-method rows
 * exist for the new sale-linked tests below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CashSessionRepositoryTest {

    private lateinit var db: PosDatabase
    private lateinit var repository: CashSessionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .addCallback(PosDatabaseSeeder.callback)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase // force onCreate/seed
        repository = CashSessionRepository(db.cashSessionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Requirement: Single Open Session Per Device ---

    @Test
    fun open_withNoSessionOpen_succeeds() = runBlocking {
        val result = repository.open(BigDecimal("100.00"))

        assertTrue(result.isSuccess)
    }

    @Test
    fun open_withSessionAlreadyOpen_returnsFailure_withSessionAlreadyOpenError() = runBlocking {
        repository.open(BigDecimal("100.00"))

        val result = repository.open(BigDecimal("50.00"))

        assertTrue(result.isFailure)
        assertEquals(DomainError.SessionAlreadyOpen, result.domainErrorOrNull())
    }

    // --- Requirement: Manual Cash Movements Only on an Open Session ---

    @Test
    fun addMovement_onOpenSession_succeeds() = runBlocking {
        repository.open(BigDecimal("100.00"))

        val result = repository.addMovement(CashMovementType.DEPOSIT, BigDecimal("50.00"), null)

        assertTrue(result.isSuccess)
    }

    @Test
    fun addMovement_withNoOpenSession_returnsFailure_withNoOpenSessionError() = runBlocking {
        val result = repository.addMovement(CashMovementType.DEPOSIT, BigDecimal("50.00"), null)

        assertTrue(result.isFailure)
        assertEquals(DomainError.NoOpenSession, result.domainErrorOrNull())
    }

    @Test
    fun addMovement_againstClosedSession_returnsFailure_withNoOpenSessionError() = runBlocking {
        repository.open(BigDecimal("100.00"))
        repository.close(BigDecimal("100.00"))

        val result = repository.addMovement(CashMovementType.WITHDRAWAL, BigDecimal("10.00"), "shrinkage")

        assertTrue(result.isFailure)
        assertEquals(DomainError.NoOpenSession, result.domainErrorOrNull())
    }

    // --- Requirement: Session Close Is Terminal ---

    @Test
    fun close_withNoOpenSession_returnsFailure_withNoOpenSessionError() = runBlocking {
        val result = repository.close(BigDecimal("100.00"))

        assertTrue(result.isFailure)
        assertEquals(DomainError.NoOpenSession, result.domainErrorOrNull())
    }

    @Test
    fun close_onAlreadyClosedSession_returnsFailure_withNoOpenSessionError() = runBlocking {
        repository.open(BigDecimal("100.00"))
        repository.close(BigDecimal("100.00"))

        val result = repository.close(BigDecimal("100.00"))

        assertTrue(result.isFailure)
        assertEquals(DomainError.NoOpenSession, result.domainErrorOrNull())
    }

    // --- Requirement: Expected Balance Formula on Close ---

    @Test
    fun close_withNoSalesAndNoMovements_snapshotsExpectedBalanceEqualToOpening() = runBlocking {
        repository.open(BigDecimal("50.00"))

        val result = repository.close(BigDecimal("50.00"))

        assertTrue(result.isSuccess)
        val view = result.getOrThrow()
        assertEquals(BigDecimal("50.00"), view.expectedBalance)
        assertEquals(CashSessionStatus.CLOSED, view.status)
    }

    private suspend fun seedProduct(price: BigDecimal): Long {
        val unitMeasureId = db.unitMeasureDao().insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        val productId = db.productDao().insert(
            ProductEntity(name = "Widget", code = "SKU-CS-1", price = price, costPrice = BigDecimal("1.00"), unitMeasureId = unitMeasureId),
        )
        db.inventoryDao().applyMovementAtomic(productId, MovementType.IN, 10)
        return productId
    }

    private fun salesRepository() = SalesRepository(db, db.salesDao(), db.inventoryDao(), db.paymentMethodDao(), db.cashSessionDao())

    // --- Requirement: Expected Balance Formula on Close (Phase 8 seam closure, task 8.3) ---

    @Test
    fun close_withDepositsWithdrawalAndOneCashSale_includesCashSaleInExpectedBalance() = runBlocking {
        // Given a session opened with balance 100, a deposit of 20, a withdrawal
        // of 10, and one order of total 30 created with payment method CASH
        // (specs/cash-session/spec.md "Close with deposits, a withdrawal, and one cash sale")
        val productId = seedProduct(BigDecimal("30.00"))
        val cashMethodId = db.paymentMethodDao().findByCode("CASH")!!.id
        repository.open(BigDecimal("100.00"))
        repository.addMovement(CashMovementType.DEPOSIT, BigDecimal("20.00"), null)
        repository.addMovement(CashMovementType.WITHDRAWAL, BigDecimal("10.00"), null)
        salesRepository().createOrder(
            Cart(listOf(CartLine(productId, "Widget", 1, BigDecimal("30.00"), BigDecimal("1.00")))),
            payment = BigDecimal("30.00"),
            methodId = cashMethodId,
        )

        // When the session is closed
        val result = repository.close(BigDecimal("140.00"))

        // Then expectedBalance = 100 + 20 - 10 + 30 = 140
        assertTrue(result.isSuccess)
        val view = result.getOrThrow()
        assertEquals(BigDecimal("140.00"), view.expectedBalance)
        assertEquals(BigDecimal.ZERO.setScale(2), view.difference)
    }

    @Test
    fun close_withNonCashAffectingSale_excludesItFromExpectedBalance() = runBlocking {
        // Given a session opened with balance 100 and no movements, and one
        // order of total 30 created with payment method TRANSFER
        // (affectsCashBalance = false) (specs/cash-session/spec.md "Close with
        // a non-cash-affecting sale excluded from the formula")
        val productId = seedProduct(BigDecimal("30.00"))
        val transferMethodId = db.paymentMethodDao().findByCode("TRANSFER")!!.id
        repository.open(BigDecimal("100.00"))
        salesRepository().createOrder(
            Cart(listOf(CartLine(productId, "Widget", 1, BigDecimal("30.00"), BigDecimal("1.00")))),
            payment = BigDecimal("30.00"),
            methodId = transferMethodId,
        )

        // When the session is closed
        val result = repository.close(BigDecimal("100.00"))

        // Then expectedBalance = 100 — the transfer sale does not contribute
        assertTrue(result.isSuccess)
        assertEquals(BigDecimal("100.00"), result.getOrThrow().expectedBalance)
    }

    // --- Live expectedBalance Flow (task 7.4) ---

    @Test
    fun currentSessionFlow_withNoOpenSession_emitsNull() = runBlocking {
        repository.currentSessionFlow().test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun currentSessionFlow_onOpen_emitsOpeningBalanceAsExpectedBalance() = runBlocking {
        repository.currentSessionFlow().test {
            assertNull(awaitItem())

            repository.open(BigDecimal("100.00"))

            val view = awaitItem()
            assertEquals(BigDecimal("100.00"), view?.expectedBalance)
            assertEquals(CashSessionStatus.OPEN, view?.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun currentSessionFlow_recomputesLiveOnEachMovement() = runBlocking {
        repository.open(BigDecimal("100.00"))

        repository.currentSessionFlow().test {
            assertEquals(BigDecimal("100.00"), awaitItem()?.expectedBalance)

            repository.addMovement(CashMovementType.DEPOSIT, BigDecimal("20.00"), null)
            assertEquals(BigDecimal("120.00"), awaitItem()?.expectedBalance)

            repository.addMovement(CashMovementType.WITHDRAWAL, BigDecimal("10.00"), null)
            assertEquals(BigDecimal("110.00"), awaitItem()?.expectedBalance)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun currentSessionFlow_afterClose_emitsNullAgain() = runBlocking {
        repository.open(BigDecimal("100.00"))

        repository.currentSessionFlow().test {
            assertEquals(BigDecimal("100.00"), awaitItem()?.expectedBalance)

            repository.close(BigDecimal("100.00"))
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
