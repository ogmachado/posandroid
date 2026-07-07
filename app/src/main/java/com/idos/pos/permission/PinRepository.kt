package com.idos.pos.permission

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Base64

/**
 * Hashed manager-PIN storage, per design.md "PIN-gate mechanism":
 * - Salted hash ([PinHasher], PBKDF2WithHmacSHA256, random per-install salt)
 *   persisted in EncryptedSharedPreferences (Jetpack Security) — NOT Room,
 *   because the PIN is app-config/security data, not domain data.
 * - Point-in-time [verify] only; this class holds no "authenticated session"
 *   state — see specs/permission-gate/spec.md "PIN Verification Is a
 *   Point-in-Time Check". [com.idos.pos.permission.PinGate] is what enforces the
 *   no-carry-over behavior on top of this class's stateless [verify].
 * - Explicit non-goals (Slice A): no PIN reset/recovery, no multiple PINs, no
 *   lockout/throttling — see design.md "Explicit non-goals (Slice A)".
 *
 * Note on testability: this class touches Android Keystore via
 * [EncryptedSharedPreferences]/[MasterKey] as soon as [prefs] is first accessed,
 * which is NOT available under Robolectric (confirmed in this environment — see
 * [PinHasher]'s doc). Per design.md's Testing Strategy table this class's storage
 * behavior is instrumented/device-only; the hashing algorithm it delegates to
 * ([PinHasher]) is what carries the Robolectric/JVM unit coverage.
 */
class PinRepository(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun hasPin(): Boolean = prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)

    /** First-run "set manager PIN" path (design.md). Overwrites any existing PIN. */
    fun setPin(pin: String) {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hash(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.getEncoder().encodeToString(salt))
            .putString(KEY_HASH, Base64.getEncoder().encodeToString(hash))
            .apply()
    }

    /**
     * Point-in-time check — does NOT persist or cache a positive result. Every
     * caller (e.g. [com.idos.pos.permission.PinGate.require]) must call this
     * again for each gated action.
     */
    fun verify(pin: String): Boolean {
        if (!hasPin()) return false
        val storedSalt = Base64.getDecoder().decode(prefs.getString(KEY_SALT, null) ?: return false)
        val storedHash = Base64.getDecoder().decode(prefs.getString(KEY_HASH, null) ?: return false)
        return PinHasher.verify(pin, storedSalt, storedHash)
    }

    private companion object {
        const val PREFS_FILE_NAME = "idos-pos-pin-prefs"
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
    }
}
