package com.idos.pos.permission

import androidx.lifecycle.ViewModel
import com.idos.pos.business.BusinessProfileEntity
import com.idos.pos.business.BusinessProfileRepository
import com.idos.pos.core.di.AppContainer

/**
 * Backs [BusinessProfileScreen] (`business-profile` spec, amended by
 * `android-pos-auth-login-first`). Excluded from strict TDD (ViewModel
 * classes, per `openspec/config.yaml` `strict_tdd_scope.exclude`) — covered
 * alongside by `BusinessProfileScreenTest`.
 *
 * `android-pos-auth-login-first` (task 1.4; design.md "Decision:
 * `BusinessProfileScreen` pre-loads via `get()`") drops the `authRepository`
 * dependency and `ensureDefaultAdminSeeded()` entirely — default-ADMIN
 * seeding now happens unconditionally in [com.idos.pos.PosApplication.onCreate],
 * not on this screen's composition. This is now a repeatable, optional,
 * post-login ADMIN edit action, not a one-shot first-run capture.
 */
class BusinessProfileViewModel(
    private val businessProfileRepository: BusinessProfileRepository,
) : ViewModel() {

    constructor(container: AppContainer) : this(container.businessProfileRepository)

    /**
     * Reads the single business-profile row, if any (`business-profile`
     * "Optional And Editable At Any Time" — pre-load scenario). Returns
     * `null` when no profile has ever been saved; the screen leaves its
     * fields empty in that case.
     */
    suspend fun load(): BusinessProfileEntity? = businessProfileRepository.get()

    /**
     * Persists the single business-profile row (`business-profile` "no
     * second row"). Suspend, not fire-and-forget — [BusinessProfileScreen]
     * awaits this directly (via `rememberCoroutineScope`, the same idiom
     * [rememberPinGate] already uses) and calls `onClose` only after it
     * resolves, rather than bridging through a `StateFlow`/`collectAsState`
     * one-shot-event signal.
     */
    suspend fun save(name: String, address: String, phone: String) {
        businessProfileRepository.save(name, address, phone)
    }
}
