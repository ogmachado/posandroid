package com.idos.pos.cashsession

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.DomainException
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED/GREEN (tasks 7.2, 7.3, 7.4; Robolectric + in-memory Room): validates
 * [CashSessionDao]'s atomic primitives against specs/cash-session/spec.md
 * scenarios.
 *
 * **Deferred to Phase 8** (see tasks.md 7.4 note): the two
 * "Expected Balance Formula on Close" scenarios that require an actual order
 * ("Close with deposits, a withdrawal, and one cash sale" and "Close with a
 * non-cash-affecting sale excluded") are NOT covered here — `orders` does not
 * exist until Phase 8. This class covers "Close with no sales and no
 * movements" and the deposit/withdrawal arithmetic in isolation instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CashSessionDaoTest {

    private lateinit var db: PosDatabase
    private lateinit var dao: CashSessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.cashSessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Requirement: Single Open Session Per Device ---

    @Test
    fun openAtomic_withNoSessionOpen_createsOpenSession() = runBlocking {
        val id = dao.openAtomic(BigDecimal("100.00"))

        val session = dao.findById(id)
        assertNotNull(session)
        assertEquals(CashSessionStatus.OPEN, session?.status)
        assertEquals(BigDecimal("100.00"), session?.openingBalance)
    }

    @Test
    fun openAtomic_withSessionAlreadyOpen_isRejected_andLeavesExistingSessionUnaffected() = runBlocking {
        val existingId = dao.openAtomic(BigDecimal("100.00"))

        val exception = try {
            dao.openAtomic(BigDecimal("50.00"))
            null
        } catch (e: DomainException) {
            e
        }
        checkNotNull(exception) { "expected a DomainException" }

        assertEquals(DomainError.SessionAlreadyOpen, exception!!.error)
        val existing = dao.findById(existingId)
        assertEquals(CashSessionStatus.OPEN, existing?.status)
        assertEquals(BigDecimal("100.00"), existing?.openingBalance)
    }

    // --- Requirement: Manual Cash Movements Only on an Open Session ---

    @Test
    fun addMovementAtomic_onOpenSession_persistsMovement() = runBlocking {
        val sessionId = dao.openAtomic(BigDecimal("100.00"))

        dao.addMovementAtomic(CashMovementType.DEPOSIT, BigDecimal("50.00"), null)

        val movements = dao.movementsForSession(sessionId)
        assertEquals(1, movements.size)
        assertEquals(CashMovementType.DEPOSIT, movements[0].type)
        assertEquals(BigDecimal("50.00"), movements[0].amount)
    }

    @Test
    fun addMovementAtomic_withNoOpenSession_isRejected() = runBlocking {
        val exception = try {
            dao.addMovementAtomic(CashMovementType.DEPOSIT, BigDecimal("50.00"), null)
            null
        } catch (e: DomainException) {
            e
        }
        checkNotNull(exception) { "expected a DomainException" }

        assertEquals(DomainError.NoOpenSession, exception!!.error)
    }

    @Test
    fun addMovementAtomic_againstClosedSession_isRejected() = runBlocking {
        val sessionId = dao.openAtomic(BigDecimal("100.00"))
        dao.closeSessionAtomic(BigDecimal("100.00"))

        val exception = try {
            dao.addMovementAtomic(CashMovementType.WITHDRAWAL, BigDecimal("10.00"), null)
            null
        } catch (e: DomainException) {
            e
        }
        checkNotNull(exception) { "expected a DomainException" }

        assertEquals(DomainError.NoOpenSession, exception!!.error)
        assertEquals(0, dao.movementsForSession(sessionId).size)
    }

    // --- Requirement: Session Close Is Terminal ---

    @Test
    fun closeSessionAtomic_withNoOpenSession_isRejected() = runBlocking {
        val exception = try {
            dao.closeSessionAtomic(BigDecimal("100.00"))
            null
        } catch (e: DomainException) {
            e
        }
        checkNotNull(exception) { "expected a DomainException" }

        assertEquals(DomainError.NoOpenSession, exception!!.error)
    }

    @Test
    fun closeSessionAtomic_onAlreadyClosedSession_isRejected() = runBlocking {
        dao.openAtomic(BigDecimal("100.00"))
        dao.closeSessionAtomic(BigDecimal("100.00"))

        val exception = try {
            dao.closeSessionAtomic(BigDecimal("100.00"))
            null
        } catch (e: DomainException) {
            e
        }
        checkNotNull(exception) { "expected a DomainException" }

        assertEquals(DomainError.NoOpenSession, exception!!.error)
    }

    // --- Requirement: Expected Balance Formula on Close ---

    @Test
    fun closeSessionAtomic_withNoSalesAndNoMovements_expectedBalanceEqualsOpening() = runBlocking {
        dao.openAtomic(BigDecimal("50.00"))

        val closed = dao.closeSessionAtomic(BigDecimal("50.00"))

        assertEquals(BigDecimal("50.00"), closed.expectedBalance)
        assertEquals(BigDecimal.ZERO.setScale(2), closed.difference)
        assertEquals(CashSessionStatus.CLOSED, closed.status)
    }

    @Test
    fun closeSessionAtomic_withDepositAndWithdrawal_computesNetExpectedBalance() = runBlocking {
        // GIVEN a session opened with balance 100, a deposit of 20 and a withdrawal of 10
        // (specs/cash-session/spec.md's arithmetic in isolation — the +30 cash-sale term
        // is deferred to Phase 8, see this class's doc comment)
        dao.openAtomic(BigDecimal("100.00"))
        dao.addMovementAtomic(CashMovementType.DEPOSIT, BigDecimal("20.00"), null)
        dao.addMovementAtomic(CashMovementType.WITHDRAWAL, BigDecimal("10.00"), null)

        // WHEN the session is closed
        val closed = dao.closeSessionAtomic(BigDecimal("110.00"))

        // THEN expectedBalance = 100 + 20 - 10 = 110
        assertEquals(BigDecimal("110.00"), closed.expectedBalance)
        assertEquals(BigDecimal.ZERO.setScale(2), closed.difference)
    }

    @Test
    fun closeSessionAtomic_withCountedDifferentFromExpected_persistsDifference() = runBlocking {
        dao.openAtomic(BigDecimal("100.00"))
        dao.addMovementAtomic(CashMovementType.DEPOSIT, BigDecimal("20.00"), null)

        val closed = dao.closeSessionAtomic(BigDecimal("115.00"))

        assertEquals(BigDecimal("120.00"), closed.expectedBalance)
        assertEquals(BigDecimal("-5.00"), closed.difference)
    }

    @Test
    fun closeSessionAtomic_leavesNoOpenSessionBehind() = runBlocking {
        dao.openAtomic(BigDecimal("100.00"))

        dao.closeSessionAtomic(BigDecimal("100.00"))

        assertNull(dao.findOpenSession())
    }
}
