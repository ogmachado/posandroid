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

/**
 * v2 -> v3: additive `app_user` (multi-user credentials) + `business_profile`
 * (single-row store profile) tables only (design.md "Room schema (v3,
 * additive)" / Decision A — one migration for both, since both are additive
 * and both belong to the same onboarding feature). Structural only — no seed
 * rows (Decision B: default-ADMIN seeding is a repository-level idempotent
 * check, not a migration-time INSERT, so this stays a clean schema check
 * matching [MIGRATION_1_2]). No existing table is touched. The `CREATE TABLE`
 * SQL below must match exactly what Room generates for
 * [com.idos.pos.permission.UserEntity] / [com.idos.pos.business.BusinessProfileEntity]
 * — verified by `Migration2To3Test.migration2to3_appliesCleanly_toAV2SchemaFixture`'s
 * `MigrationTestHelper.runMigrationsAndValidate(..., validateDroppedTables =
 * true, ...)` call against the exported v3 schema.
 *
 * Registering this migration with the production `Room.databaseBuilder` call
 * (`core/di/AppContainer.kt`) happens in this same PR (task 1.17) — unlike
 * [MIGRATION_1_2], which was registered in a later phase.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `app_user` (
                `id` INTEGER NOT NULL,
                `username` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `pinSalt` BLOB NOT NULL,
                `pinHash` BLOB NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_app_user_username` ON `app_user` (`username`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `business_profile` (
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `address` TEXT NOT NULL,
                `phone` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}
