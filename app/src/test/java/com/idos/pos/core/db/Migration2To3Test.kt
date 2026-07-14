package com.idos.pos.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TEST_DB_NAME = "app-user-business-profile-migration-test.db"

/**
 * RED/GREEN (task 1.8/1.9): [MIGRATION_2_3] against the real exported v2
 * schema fixture (`app/src/main/assets/com.idos.pos.core.db.PosDatabase/2.json`)
 * — design.md "Room schema (v3, additive)". Mirrors
 * `com.idos.pos.licensing.LicenseStateDaoTest.migration1to2_appliesCleanly_toAV1SchemaFixture`'s
 * pattern for [MIGRATION_1_2].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration2To3Test {

    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PosDatabase::class.java,
    )

    // --- Scenario: MIGRATION_2_3 applies cleanly to a real v2 schema fixture ---

    @Test
    fun migration2to3_appliesCleanly_toAV2SchemaFixture() {
        // Given: a v2 database created from the exported v2 schema fixture (no app_user/business_profile tables)
        migrationTestHelper.createDatabase(TEST_DB_NAME, 2).close()

        // When: MIGRATION_2_3 runs and Room validates the resulting schema against the v3 export
        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            TEST_DB_NAME,
            3,
            true,
            MIGRATION_2_3,
        )

        // Then: both new tables exist and accept a row with the expected columns
        migratedDb.execSQL(
            "INSERT INTO app_user (id, username, role, pinSalt, pinHash) VALUES (1, 'admin', 'ADMIN', X'01', X'02')",
        )
        val userCursor = migratedDb.query("SELECT COUNT(*) FROM app_user")
        userCursor.moveToFirst()
        assertEquals(1, userCursor.getInt(0))
        userCursor.close()

        migratedDb.execSQL(
            "INSERT INTO business_profile (id, name, address, phone) VALUES (1, 'Acme Bar', '123 Main St', '555-0100')",
        )
        val profileCursor = migratedDb.query("SELECT COUNT(*) FROM business_profile")
        profileCursor.moveToFirst()
        assertEquals(1, profileCursor.getInt(0))
        profileCursor.close()
    }
}
