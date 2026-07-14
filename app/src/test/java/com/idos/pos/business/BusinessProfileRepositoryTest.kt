package com.idos.pos.business

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.db.PosDatabase
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
 * RED/GREEN (task 1.15/1.16): [BusinessProfileRepository] — design.md
 * "Interfaces" + `business-profile` spec "Exactly One Business Profile Row
 * Exists" / "The Captured Profile Is Readable After Capture".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BusinessProfileRepositoryTest {

    private lateinit var db: PosDatabase
    private lateinit var repository: BusinessProfileRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BusinessProfileRepository(db.businessProfileDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Scenario: exists() false before capture / true after ---

    @Test
    fun exists_isFalseBeforeCapture_trueAfter() = runBlocking {
        assertFalse(repository.exists())

        repository.save("Acme Bar", "123 Main St", "555-0100")

        assertTrue(repository.exists())
    }

    // --- Scenario: get() returns captured values ---

    @Test
    fun get_returnsTheCapturedValues() = runBlocking {
        assertNull(repository.get())

        repository.save("Acme Bar", "123 Main St", "555-0100")

        val profile = repository.get()
        assertEquals("Acme Bar", profile?.name)
        assertEquals("123 Main St", profile?.address)
        assertEquals("555-0100", profile?.phone)
    }

    // --- Scenario: save() called twice never produces a second row ---

    @Test
    fun save_calledTwice_neverProducesASecondRow() = runBlocking {
        repository.save("First Name", "First Addr", "111")
        repository.save("Second Name", "Second Addr", "222")

        val cursor = db.query("SELECT COUNT(*) FROM business_profile", null)
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
        cursor.close()

        assertEquals("Second Name", repository.get()?.name)
    }
}
