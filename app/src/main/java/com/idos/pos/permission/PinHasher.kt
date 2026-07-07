package com.idos.pos.permission

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pure PBKDF2WithHmacSHA256 hash+verify primitive for the manager PIN (task 3.1/3.2).
 *
 * Deliberately has NO dependency on Android Keystore / EncryptedSharedPreferences —
 * per design.md's Testing Strategy table, "PIN hash verify" is Robolectric/JVM-unit-
 * testable, while "EncryptedSharedPreferences PIN storage" is instrumented/device-
 * only (Android Keystore is not available under Robolectric — confirmed by running
 * the test suite in this environment: `MasterKey`/`EncryptedSharedPreferences`
 * construction throws `KeyStoreException`/`NoSuchAlgorithmException` there). Splitting
 * the pure hashing algorithm out of [PinRepository] keeps the algorithm itself
 * covered by a fast JVM unit test regardless of that storage-layer limitation.
 */
object PinHasher {
    private const val SALT_LENGTH_BYTES = 16
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256

    fun generateSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec: KeySpec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    fun verify(pin: String, salt: ByteArray, expectedHash: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt), expectedHash)
}
