package com.idos.pos.licensing

import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Keystore-backed first-run UUID gen/persist + `ANDROID_ID` read (design.md
 * "Installation identity" / task 2.1-2.2) —
 * specs/installation-identity/spec.md "Composite Installation-ID Derivation".
 * Same `MasterKey`/`EncryptedSharedPreferences` pattern as
 * [com.idos.pos.permission.PinRepository] — deliberately mirrors it field for
 * field (Decision E, pure-vs-Keystore split) rather than introducing a new
 * storage idiom.
 *
 * Note on testability: Android Keystore is NOT available under Robolectric in
 * this environment (confirmed by running an instrumented-style test against
 * this exact class here — `MasterKey.Builder(...).build()` throws
 * `java.security.KeyStoreException: Keystore not initialized` on the JVM;
 * `EncryptedSharedPreferences` construction never even gets called). This is
 * the same limitation [com.idos.pos.permission.PinHasher]'s KDoc documents and
 * why no `PinRepositoryTest` exists in this codebase — [InstallationIdStore]
 * follows that exact precedent: no Robolectric/JVM test of its own.
 * [InstallationId.derive] (Phase 1, `InstallationIdTest`) carries the pure
 * combination-logic coverage; this class's storage/read behavior would need a
 * real device or emulator (`androidTest`) to exercise, which is out of scope
 * for this PR (mirrors PinRepository's status quo — see report for the
 * `BarcodeScanPipelineTest`-precedent honesty note).
 */
class InstallationIdStore(private val context: Context) {

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

    /**
     * The stable per-installation identifier: [InstallationId.derive] applied
     * to this device's [androidId] and the persisted first-run [uuid].
     */
    fun installationId(): String = InstallationId.derive(androidId(), uuid())

    /**
     * First-run generates and persists a random UUID
     * (specs/installation-identity/spec.md "First run generates and persists
     * the UUID"); later calls reuse the same stored value ("Subsequent runs
     * reuse the same ID") until the app's data is wiped (reinstall/factory
     * reset — spec "Reinstall or Factory Reset Invalidates Prior Binding").
     */
    private fun uuid(): String {
        val existing = prefs.getString(KEY_UUID, null)
        if (existing != null) return existing

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_UUID, generated).apply()
        return generated
    }

    private fun androidId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    private companion object {
        const val PREFS_FILE_NAME = "idos-pos-license-prefs"
        const val KEY_UUID = "installation_uuid"
    }
}
