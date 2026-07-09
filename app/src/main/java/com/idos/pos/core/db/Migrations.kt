package com.idos.pos.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: additive singleton `license_state` table only (design.md
 * "Migration / Rollout": "Additive Room `MIGRATION_1_2`; revert = drop
 * commits + migration, app returns to unlicensed-MVP state"). No existing
 * table is touched. The `CREATE TABLE` SQL below must match exactly what Room
 * generates for [com.idos.pos.licensing.LicenseStateEntity] — verified by
 * `LicenseStateDaoTest.migration1to2_appliesCleanly_toAV1SchemaFixture`'s
 * `MigrationTestHelper.runMigrationsAndValidate(..., validateDroppedTables =
 * true, ...)` call against the exported v2 schema.
 *
 * Registering this migration with the production `Room.databaseBuilder` call
 * (`core/di/AppContainer.kt`) is Phase 3 (PR3) work — out of scope here.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `license_state` (
                `id` INTEGER NOT NULL,
                `jws` TEXT,
                `installedIssuedAt` INTEGER,
                `lastHeartbeatAt` INTEGER,
                `compromised` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}
