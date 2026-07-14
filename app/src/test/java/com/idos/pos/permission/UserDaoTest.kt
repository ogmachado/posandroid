package com.idos.pos.permission

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.db.PosDatabase
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
 * RED/GREEN (task 1.4/1.5): [UserDao] against the `app_user` table
 * (design.md "Room schema (v3, additive)") — `user-identity` spec
 * "Users Are Named, Roled, and Individually Credentialed" ("no shared
 * credential" via the unique `username` index).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserDaoTest {

    private lateinit var db: PosDatabase
    private lateinit var dao: UserDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.userDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun user(
        username: String = "admin",
        role: String = UserRole.ADMIN.name,
        salt: ByteArray = byteArrayOf(1, 2, 3),
        hash: ByteArray = byteArrayOf(4, 5, 6),
    ) = UserEntity(username = username, role = role, pinSalt = salt, pinHash = hash)

    // --- Scenario: insert + findByUsername ---

    @Test
    fun insert_thenFindByUsername_returnsTheInsertedRow() = runBlocking {
        assertNull(dao.findByUsername("admin"))

        dao.insert(user(username = "admin"))

        val found = dao.findByUsername("admin")
        assertEquals("admin", found?.username)
        assertEquals(UserRole.ADMIN.name, found?.role)
    }

    // --- Scenario: findByRole ---

    @Test
    fun findByRole_returnsOnlyUsersWithThatRole() = runBlocking {
        dao.insert(user(username = "admin", role = UserRole.ADMIN.name))
        dao.insert(user(username = "cashier1", role = UserRole.CASHIER.name))
        dao.insert(user(username = "cashier2", role = UserRole.CASHIER.name))

        val cashiers = dao.findByRole(UserRole.CASHIER.name)
        val admins = dao.findByRole(UserRole.ADMIN.name)

        assertEquals(2, cashiers.size)
        assertTrue(cashiers.all { it.role == UserRole.CASHIER.name })
        assertEquals(1, admins.size)
        assertEquals("admin", admins.single().username)
    }

    // --- Scenario: findAll ---

    @Test
    fun findAll_returnsEveryUser() = runBlocking {
        dao.insert(user(username = "admin", role = UserRole.ADMIN.name))
        dao.insert(user(username = "cashier1", role = UserRole.CASHIER.name))

        val all = dao.findAll()

        assertEquals(2, all.size)
    }

    // --- Scenario: count ---

    @Test
    fun count_reflectsNumberOfRows() = runBlocking {
        assertEquals(0, dao.count())

        dao.insert(user(username = "admin"))

        assertEquals(1, dao.count())
    }

    // --- Scenario: updatePin ---

    @Test
    fun updatePin_changesTheStoredSaltAndHash_forThatUser() = runBlocking {
        val id = dao.insert(user(username = "admin", salt = byteArrayOf(1), hash = byteArrayOf(2)))

        dao.updatePin(id, salt = byteArrayOf(9, 9), hash = byteArrayOf(8, 8))

        val updated = dao.findByUsername("admin")
        assertTrue(updated?.pinSalt.contentEquals(byteArrayOf(9, 9)))
        assertTrue(updated?.pinHash.contentEquals(byteArrayOf(8, 8)))
    }

    // --- Scenario: two users have independent credentials (no shared credential) ---

    @Test
    fun twoUsers_haveIndependentCredentials() = runBlocking {
        dao.insert(user(username = "admin", role = UserRole.ADMIN.name, salt = byteArrayOf(1), hash = byteArrayOf(11)))
        dao.insert(user(username = "cashier", role = UserRole.CASHIER.name, salt = byteArrayOf(2), hash = byteArrayOf(22)))

        val admin = dao.findByUsername("admin")
        val cashier = dao.findByUsername("cashier")

        assertTrue(admin?.pinHash.contentEquals(byteArrayOf(11)))
        assertTrue(cashier?.pinHash.contentEquals(byteArrayOf(22)))
    }

    // --- Scenario: unique-username constraint rejects a duplicate insert ---

    @Test
    fun insert_withDuplicateUsername_isRejected() = runBlocking {
        dao.insert(user(username = "admin"))

        try {
            dao.insert(user(username = "admin", role = UserRole.CASHIER.name))
            fail("expected a unique-constraint violation for a duplicate username")
        } catch (expected: SQLiteConstraintException) {
            // ABORT on the unique index — expected
        }

        assertEquals(1, dao.count())
    }
}
