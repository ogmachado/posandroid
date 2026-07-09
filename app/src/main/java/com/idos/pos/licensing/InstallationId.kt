package com.idos.pos.licensing

import java.security.MessageDigest

/**
 * Pure installation-ID derivation (design.md "Installation identity"):
 * `hex(sha256(androidId + uuid))`. Deliberately Context-free — reading
 * `Settings.Secure.ANDROID_ID` and generating/persisting the first-run UUID
 * in `EncryptedSharedPreferences` is [InstallationIdStore]'s job (Phase 2,
 * instrumented). This object only combines the two sources deterministically,
 * matching design's Decision E (pure-vs-Keystore split) — same shape as
 * `com.idos.pos.permission.PinHasher`.
 */
object InstallationId {

    fun derive(androidId: String, uuid: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((androidId + uuid).toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
