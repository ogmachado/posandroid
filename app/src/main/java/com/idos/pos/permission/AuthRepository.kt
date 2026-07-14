package com.idos.pos.permission

import android.content.Context
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.DomainException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Identity/credential orchestration (design.md "Identity model & credential
 * mechanics" + Decisions B/C/D/F/G). Fully Robolectric-testable: users live
 * in Room, hashing is the pure [PinHasher] — no Android Keystore dependency,
 * unlike the retired [PinRepository].
 */
class AuthRepository(private val userDao: UserDao, private val context: Context) {

    private val _currentSession = MutableStateFlow<AuthSession?>(null)

    /** In-memory only (Decision F) — `user-identity` "Session Identity Is Held In-Memory Only". */
    val currentSession: StateFlow<AuthSession?> = _currentSession.asStateFlow()

    /**
     * Idempotent default-ADMIN seed (Decision B): only inserts when the
     * `app_user` table is empty, so this is safe to call unconditionally on
     * every onboarding composition (fresh install AND upgrading MVP
     * devices). Best-effort deletes the retired singleton PIN prefs file
     * (Decision D) on the seeding run — `user-identity` "no parallel store
     * consulted".
     */
    suspend fun ensureDefaultAdminSeeded() {
        if (userDao.count() > 0) return

        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hash(DEFAULT_ADMIN_PIN, salt)
        userDao.insert(
            UserEntity(
                username = DEFAULT_ADMIN_USERNAME,
                role = UserRole.ADMIN.name,
                pinSalt = salt,
                pinHash = hash,
            ),
        )

        // Best-effort cleanup of the retired singleton PIN store (Decision D) —
        // failure here must never block seeding, so no exception is allowed to escape.
        runCatching { context.deleteSharedPreferences(OLD_PIN_PREFS_FILE_NAME) }
    }

    /**
     * Per-user credential verification (`user-identity` "Credential
     * Verification Is Per-User"). On success, sets the in-memory session to
     * that user + role.
     */
    suspend fun login(username: String, pin: String): Boolean {
        val user = userDao.findByUsername(username) ?: return false
        val verified = PinHasher.verify(pin, user.pinSalt, user.pinHash)
        if (verified) {
            _currentSession.value = AuthSession(userId = user.id, username = user.username, role = UserRole.valueOf(user.role))
        }
        return verified
    }

    /**
     * `permission-gate` point-in-time check against ALL `ADMIN`-role rows
     * (Decision G) — never the session identity. Does not touch
     * [currentSession].
     */
    suspend fun verifyAdminPin(pin: String): Boolean {
        val admins = userDao.findByRole(UserRole.ADMIN.name)
        return admins.any { PinHasher.verify(pin, it.pinSalt, it.pinHash) }
    }

    /**
     * ADMIN-authored account creation. Rejects a duplicate `username`
     * ([DomainError.DuplicateUsername]) and a blank PIN
     * ([DomainError.BlankPin]) before ever touching the database.
     */
    suspend fun createUser(username: String, pin: String, role: UserRole): Result<Unit> {
        if (pin.isBlank()) return Result.failure(DomainException(DomainError.BlankPin))
        if (userDao.findByUsername(username) != null) {
            return Result.failure(DomainException(DomainError.DuplicateUsername(username)))
        }

        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hash(pin, salt)
        userDao.insert(UserEntity(username = username, role = role.name, pinSalt = salt, pinHash = hash))
        return Result.success(Unit)
    }

    /** Credential-change flow — overwrites the given user's PIN with a freshly salted hash. */
    suspend fun changePin(userId: Long, newPin: String) {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hash(newPin, salt)
        userDao.updatePin(userId, salt, hash)
    }

    companion object {
        const val DEFAULT_ADMIN_USERNAME = "admin"
        const val DEFAULT_ADMIN_PIN = "admin123"

        /** The retired singleton PIN store's prefs file name (Decision D) — see [PinRepository]. */
        private const val OLD_PIN_PREFS_FILE_NAME = "idos-pos-pin-prefs"
    }
}
