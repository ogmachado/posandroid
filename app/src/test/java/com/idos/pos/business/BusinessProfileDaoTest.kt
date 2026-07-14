package com.idos.pos.business

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.db.PosDatabase
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
 * RED/GREEN (task 1.6/1.7): [BusinessProfileDao] against the single-row
 * `business_profile` table (design.md "Room schema (v3, additive)") —
 * `business-profile` spec "Exactly One Business Profile Row Exists" / "No
 * second profile row can be created".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BusinessProfileDaoTest {

    private lateinit var db: PosDatabase
    private lateinit var dao: BusinessProfileDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.businessProfileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Scenario: find() returns null before capture ---

    @Test
    fun find_withNoCapturedProfile_returnsNull() = runBlocking {
        assertNull(dao.find())
    }

    // --- Scenario: find() returns non-null after upsert ---

    @Test
    fun find_afterUpsert_returnsTheCapturedProfile() = runBlocking {
        dao.upsert(BusinessProfileEntity(name = "Acme Bar", address = "123 Main St", phone = "555-0100"))

        val found = dao.find()

        assertEquals("Acme Bar", found?.name)
        assertEquals("123 Main St", found?.address)
        assertEquals("555-0100", found?.phone)
        assertEquals(BusinessProfileEntity.SINGLE_ROW_ID, found?.id)
    }

    // --- Scenario: a second upsert REPLACEs the single row rather than creating a second one ---

    @Test
    fun secondUpsert_replacesTheSingleRow_neverCreatesASecondOne() = runBlocking {
        dao.upsert(BusinessProfileEntity(name = "First Name", address = "First Addr", phone = "111"))

        dao.upsert(BusinessProfileEntity(name = "Second Name", address = "Second Addr", phone = "222"))

        val found = dao.find()
        assertEquals("Second Name", found?.name)
        assertEquals("Second Addr", found?.address)
        assertEquals("222", found?.phone)

        val cursor = db.query("SELECT COUNT(*) FROM business_profile", null)
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
        cursor.close()
    }
}
