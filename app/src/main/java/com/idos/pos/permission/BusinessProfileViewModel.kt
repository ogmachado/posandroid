package com.idos.pos.permission

import androidx.lifecycle.ViewModel
import com.idos.pos.business.BusinessProfileEntity
import com.idos.pos.business.BusinessProfileRepository
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.DomainException

/**
 * Backs [BusinessProfileScreen] (`business-profile` spec, amended by
 * `android-pos-auth-login-first` and `android-pos-role-permissions`).
 * Excluded from strict TDD (ViewModel classes, per `openspec/config.yaml`
 * `strict_tdd_scope.exclude`) — covered alongside by `BusinessProfileScreenTest`.
 *
 * `android-pos-auth-login-first` (task 1.4; design.md "Decision:
 * `BusinessProfileScreen` pre-loads via `get()`") dropped the
 * `ensureDefaultAdminSeeded()` call entirely — default-ADMIN seeding now
 * happens unconditionally in [com.idos.pos.PosApplication.onCreate], not on
 * this screen's composition. This is now a repeatable, optional, post-login
 * ADMIN edit action, not a one-shot first-run capture.
 *
 * `android-pos-role-permissions` Phase 2 (design.md Decision B) re-introduces
 * an [authRepository] dependency — this time to enforce the write path
 * itself (`business-profile`, MODIFIED — "The Profile Is Optional And
 * Editable At Any Time Via An ADMIN-Only Action"), not to seed. [save] reads
 * the caller's session fresh and rejects a non-ADMIN caller even when
 * invoked directly, bypassing the UI.
 */
class BusinessProfileViewModel(
    private val businessProfileRepository: BusinessProfileRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    constructor(container: AppContainer) : this(container.businessProfileRepository, container.authRepository)

    /**
     * Reads the single business-profile row, if any (`business-profile`
     * "Optional And Editable At Any Time" — pre-load scenario). Returns
     * `null` when no profile has ever been saved; the screen leaves its
     * fields empty in that case.
     */
    suspend fun load(): BusinessProfileEntity? = businessProfileRepository.get()

    /**
     * Persists the single business-profile row (`business-profile` "no
     * second row"), after rejecting a non-ADMIN caller
     * ([DomainError.NotPermitted], resolved fresh from
     * [AuthRepository.currentSession] — design.md Decision A). Suspend, not
     * fire-and-forget — [BusinessProfileScreen] awaits this directly (via
     * `rememberCoroutineScope`, the same idiom [rememberPinGate] already
     * uses) and calls `onClose` only on a successful result, rather than
     * bridging through a `StateFlow`/`collectAsState` one-shot-event signal.
     */
    suspend fun save(name: String, address: String, phone: String): Result<Unit> {
        if (!authRepository.currentSession.value?.role.canAccessBusinessProfile()) {
            return Result.failure(DomainException(DomainError.NotPermitted))
        }
        businessProfileRepository.save(name, address, phone)
        return Result.success(Unit)
    }
}
