package com.idos.pos.cashsession

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
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
 * RED/GREEN (tasks 7.3, 7.4; Robolectric + in-memory Room + Turbine): validates
 * [CashSessionRepository]'s `Result`/[DomainError] wrapping around
 * [CashSessionDao]'s atomic primitives, and the live
 * [CashSessionRepository.currentSessionFlow].
 *
 * **Deferred to Phase 8** (tasks.md 7.4 note): the cash-sale-inclusive
 * `expectedBalance` scenarios are not covered here — see [CashSessionDaoTest]'s
 * class doc for the same note.
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
            .allowMainThreadQueries()
            .build()
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
