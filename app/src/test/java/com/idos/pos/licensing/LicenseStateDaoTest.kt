package com.idos.pos.licensing

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.idos.pos.core.db.MIGRATION_1_2
import com.idos.pos.core.db.PosDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TEST_DB_NAME = "license-state-migration-test.db"

/**
 * RED/GREEN (task 2.5/2.6): [LicenseStateDao] against the single-row
 * `license_state` table (design.md "Room schema (v2, additive)"), plus
 * [MIGRATION_1_2] against the real exported v1 schema fixture
 * (`app/schemas/com.idos.pos.core.db.PosDatabase/1.json`, task 0.4's
 * `exportSchema = true` — see `app/build.gradle.kts` task 2.6 sourceSets edit
 * for how that JSON becomes reachable from a Robolectric unit test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LicenseStateDaoTest {

    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PosDatabase::class.java,
    )

    private lateinit var db: PosDatabase
    private lateinit var dao: LicenseStateDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.licenseStateDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Scenario: insert the single row when none exists yet ---

    @Test
    fun upsert_withNoExistingRow_insertsIt() = runBlocking {
        assertNull(dao.find())

        dao.upsert(
            LicenseStateEntity(
                jws = "header.payload.signature",
                installedIssuedAt = 1_000L,
                lastHeartbeatAt = 1_000L,
                compromised = false,
            ),
        )

        val row = dao.find()
        assertEquals("header.payload.signature", row?.jws)
        assertEquals(1_000L, row?.installedIssuedAt)
        assertEquals(false, row?.compromised)
    }

    // --- Scenario: a second upsert replaces the same single row, never adds a second one ---

    @Test
    fun upsert_withExistingRow_replacesFields_andStaysASingleRow() = runBlocking {
        dao.upsert(
            LicenseStateEntity(jws = "first-jws", installedIssuedAt = 1_000L, lastHeartbeatAt = 1_000L, compromised = false),
        )

        dao.upsert(
            LicenseStateEntity(jws = "second-jws", installedIssuedAt = 2_000L, lastHeartbeatAt = 3_000L, compromised = true),
        )

        val row = dao.find()
        assertEquals("second-jws", row?.jws)
        assertEquals(2_000L, row?.installedIssuedAt)
        assertEquals(3_000L, row?.lastHeartbeatAt)
        assertTrue(row?.compromised == true)
        assertEquals(LicenseStateEntity.SINGLE_ROW_ID, row?.id)
    }

    // --- Scenario: MIGRATION_1_2 applies cleanly to a real v1 schema fixture ---

    @Test
    fun migration1to2_appliesCleanly_toAV1SchemaFixture() {
        // Given: a v1 database created from the exported v1 schema fixture (no license_state table)
        migrationTestHelper.createDatabase(TEST_DB_NAME, 1).close()

        // When: MIGRATION_1_2 runs and Room validates the resulting schema against the v2 export
        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            TEST_DB_NAME,
            2,
            true,
            MIGRATION_1_2,
        )

        // Then: the new table exists and accepts a row with the expected columns
        migratedDb.execSQL(
            "INSERT INTO license_state (id, jws, installedIssuedAt, lastHeartbeatAt, compromised) VALUES (1, NULL, NULL, NULL, 0)",
        )
        val cursor = migratedDb.query("SELECT COUNT(*) FROM license_state")
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
        cursor.close()
    }
}
