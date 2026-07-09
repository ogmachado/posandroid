package com.idos.pos.licensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED/GREEN (task 1.4): [InstallationId.derive] is a pure
 * `hex(sha256(androidId + uuid))` combination — design.md "Installation
 * identity". Deliberately has no Android/Keystore dependency; reading
 * `Settings.Secure.ANDROID_ID` and persisting the first-run UUID is
 * [InstallationIdStore]'s job (Phase 2, instrumented — not this class).
 */
class InstallationIdTest {

    @Test
    fun derive_isDeterministic_forTheSameAndroidIdAndUuid() {
        val first = InstallationId.derive("android-id-123", "uuid-abc")
        val second = InstallationId.derive("android-id-123", "uuid-abc")

        assertEquals(first, second)
    }

    @Test
    fun derive_producesDifferentIds_whenUuidDiffers() {
        val first = InstallationId.derive("android-id-123", "uuid-abc")
        val second = InstallationId.derive("android-id-123", "uuid-different")

        assertNotEquals(first, second)
    }

    @Test
    fun derive_producesDifferentIds_whenAndroidIdDiffers() {
        val first = InstallationId.derive("android-id-123", "uuid-abc")
        val second = InstallationId.derive("android-id-999", "uuid-abc")

        assertNotEquals(first, second)
    }

    @Test
    fun derive_producesLowercaseHexOfSha256Length() {
        val id = InstallationId.derive("android-id-123", "uuid-abc")

        assertEquals(64, id.length) // SHA-256 = 32 bytes = 64 hex chars
        assertTrue(id.all { it.isDigit() || it in 'a'..'f' })
    }
}
