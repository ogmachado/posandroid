package com.idos.pos.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED (task 3.1): PBKDF2WithHmacSHA256 hash+verify round-trip for the manager
 * PIN — correct PIN verifies, incorrect PIN does not. Pure JVM test, no Android
 * Keystore dependency (see [PinHasher] doc for why this is split out of
 * [PinRepository] — EncryptedSharedPreferences/Android Keystore is not available
 * under Robolectric in this environment).
 */
class PinHasherTest {

    @Test
    fun hash_thenVerify_withCorrectPin_succeeds() {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hash("1234", salt)

        assertTrue(PinHasher.verify("1234", salt, hash))
    }

    @Test
    fun hash_thenVerify_withIncorrectPin_fails() {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hash("1234", salt)

        assertFalse(PinHasher.verify("9999", salt, hash))
    }

    @Test
    fun generateSalt_producesDifferentSaltEachCall() {
        val first = PinHasher.generateSalt()
        val second = PinHasher.generateSalt()

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun hash_isDeterministic_forSameSaltAndPin() {
        val salt = PinHasher.generateSalt()

        val first = PinHasher.hash("1234", salt)
        val second = PinHasher.hash("1234", salt)

        assertTrue(first.contentEquals(second))
    }
}
