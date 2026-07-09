package com.idos.pos.licensing

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row for the whole license lifecycle state (design.md "Room
 * schema (v2, additive)"): `jws` is the last persisted license text (or
 * `null` before the first `install`), `installedIssuedAt`/`lastHeartbeatAt`
 * are epoch-second anchors consumed by [ClockRollbackDetector], and
 * `compromised` is the sticky flag specs/anti-tamper-heartbeat/spec.md
 * requires. [LicenseStateDao.upsert] always writes [SINGLE_ROW_ID], so this
 * table never holds more than one row.
 */
@Entity(tableName = "license_state")
data class LicenseStateEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val jws: String? = null,
    val installedIssuedAt: Long? = null,
    val lastHeartbeatAt: Long? = null,
    val compromised: Boolean = false,
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}
