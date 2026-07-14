package com.idos.pos.permission

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED/GREEN (task 1.13/1.14): [AuthRepository] — design.md "Identity model &
 * credential mechanics" + Decisions B/D/F/G. Robolectric + in-memory Room,
 * real [PinHasher] (pure, no Keystore dependency — see [PinHasher] doc).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthRepositoryTest {

    private lateinit var db: PosDatabase
    private lateinit var dao: UserDao
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.userDao()
        repository = AuthRepository(dao, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Scenario: ensureDefaultAdminSeeded() seeds admin/admin123 exactly once ---

    @Test
    fun ensureDefaultAdminSeeded_onEmptyTable_seedsTheDefaultAdmin() = runBlocking {
        assertEquals(0, dao.count())

        repository.ensureDefaultAdminSeeded()

        val admin = dao.findByUsername(AuthRepository.DEFAULT_ADMIN_USERNAME)
        assertEquals(AuthRepository.DEFAULT_ADMIN_USERNAME, admin?.username)
        assertEquals(UserRole.ADMIN.name, admin?.role)
        assertTrue(PinHasher.verify(AuthRepository.DEFAULT_ADMIN_PIN, admin!!.pinSalt, admin.pinHash))
    }

    @Test
    fun ensureDefaultAdminSeeded_isIdempotent_onRepeatCalls() = runBlocking {
        repository.ensureDefaultAdminSeeded()
        repository.ensureDefaultAdminSeeded()
        repository.ensureDefaultAdminSeeded()

        assertEquals(1, dao.count())
    }

    @Test
    fun ensureDefaultAdminSeeded_whenUsersAlreadyExist_doesNothing() = runBlocking {
        val salt = PinHasher.generateSalt()
        dao.insert(
            UserEntity(
                username = "existing",
                role = UserRole.CASHIER.name,
                pinSalt = salt,
                pinHash = PinHasher.hash("1111", salt),
            ),
        )

        repository.ensureDefaultAdminSeeded()

        assertEquals(1, dao.count())
        assertNull(dao.findByUsername(AuthRepository.DEFAULT_ADMIN_USERNAME))
    }

    // --- Scenario: login succeeds only for the matching user's own hash ---

    @Test
    fun login_withCorrectPin_forTheOwningUser_succeeds_andSetsSession() = runBlocking {
        val salt = PinHasher.generateSalt()
        dao.insert(UserEntity(username = "cashier1", role = UserRole.CASHIER.name, pinSalt = salt, pinHash = PinHasher.hash("2468", salt)))

        assertNull(repository.currentSession.value)

        val result = repository.login("cashier1", "2468")

        assertTrue(result)
        assertEquals("cashier1", repository.currentSession.value?.username)
        assertEquals(UserRole.CASHIER, repository.currentSession.value?.role)
    }

    @Test
    fun login_withAnotherUsersPin_fails() = runBlocking {
        val saltA = PinHasher.generateSalt()
        val saltB = PinHasher.generateSalt()
        dao.insert(UserEntity(username = "userA", role = UserRole.CASHIER.name, pinSalt = saltA, pinHash = PinHasher.hash("1111", saltA)))
        dao.insert(UserEntity(username = "userB", role = UserRole.CASHIER.name, pinSalt = saltB, pinHash = PinHasher.hash("2222", saltB)))

        val result = repository.login("userB", "1111")

        assertFalse(result)
        assertNull(repository.currentSession.value)
    }

    @Test
    fun login_withUnknownUsername_fails() = runBlocking {
        val result = repository.login("ghost", "1234")

        assertFalse(result)
        assertNull(repository.currentSession.value)
    }

    // --- Scenario: verifyAdminPin succeeds for any ADMIN row, fails for CASHIER rows with the same PIN ---

    @Test
    fun verifyAdminPin_succeedsForAnyAdminRow() = runBlocking {
        val salt = PinHasher.generateSalt()
        dao.insert(UserEntity(username = "admin2", role = UserRole.ADMIN.name, pinSalt = salt, pinHash = PinHasher.hash("9999", salt)))

        val result = repository.verifyAdminPin("9999")

        assertTrue(result)
    }

    @Test
    fun verifyAdminPin_failsForACashierRole_evenWithTheSamePin() = runBlocking {
        val salt = PinHasher.generateSalt()
        dao.insert(UserEntity(username = "cashier1", role = UserRole.CASHIER.name, pinSalt = salt, pinHash = PinHasher.hash("9999", salt)))

        val result = repository.verifyAdminPin("9999")

        assertFalse(result)
    }

    @Test
    fun verifyAdminPin_doesNotTouchTheSession() = runBlocking {
        val salt = PinHasher.generateSalt()
        dao.insert(UserEntity(username = "admin2", role = UserRole.ADMIN.name, pinSalt = salt, pinHash = PinHasher.hash("9999", salt)))

        repository.verifyAdminPin("9999")

        assertNull(repository.currentSession.value)
    }

    // --- Scenario: createUser rejects a duplicate username ---

    @Test
    fun createUser_withDuplicateUsername_isRejected() = runBlocking {
        repository.createUser("cashier1", "1234", UserRole.CASHIER)

        val result = repository.createUser("cashier1", "5678", UserRole.CASHIER)

        assertTrue(result.isFailure)
        assertEquals(DomainError.DuplicateUsername("cashier1"), result.domainErrorOrNull())
        assertEquals(1, dao.count())
    }

    // --- Scenario: createUser rejects a blank PIN ---

    @Test
    fun createUser_withBlankPin_isRejected() = runBlocking {
        val result = repository.createUser("cashier1", "", UserRole.CASHIER)

        assertTrue(result.isFailure)
        assertEquals(DomainError.BlankPin, result.domainErrorOrNull())
        assertEquals(0, dao.count())
    }

    @Test
    fun createUser_withValidInput_succeeds_andIsHashedAtRest() = runBlocking {
        val result = repository.createUser("cashier1", "1234", UserRole.CASHIER)

        assertTrue(result.isSuccess)
        val created = dao.findByUsername("cashier1")
        assertEquals(UserRole.CASHIER.name, created?.role)
        assertTrue(PinHasher.verify("1234", created!!.pinSalt, created.pinHash))
    }

    // --- Scenario: currentSession is null before login, set after ---

    @Test
    fun currentSession_isNullBeforeLogin_setAfterASuccessfulLogin() = runBlocking {
        val salt = PinHasher.generateSalt()
        dao.insert(UserEntity(username = "cashier1", role = UserRole.CASHIER.name, pinSalt = salt, pinHash = PinHasher.hash("1234", salt)))

        assertNull(repository.currentSession.value)

        repository.login("cashier1", "1234")

        assertEquals("cashier1", repository.currentSession.value?.username)
    }
}
