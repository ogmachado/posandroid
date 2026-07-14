package com.idos.pos.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idos.pos.business.BusinessProfileRepository
import com.idos.pos.core.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Backs [OnboardingScreen] (`first-run-onboarding` / `business-profile`
 * specs). Excluded from strict TDD (ViewModel classes, per
 * `openspec/config.yaml` `strict_tdd_scope.exclude`) — covered alongside by
 * `OnboardingScreenTest`.
 */
class OnboardingViewModel(
    private val authRepository: AuthRepository,
    private val businessProfileRepository: BusinessProfileRepository,
) : ViewModel() {

    constructor(container: AppContainer) : this(container.authRepository, container.businessProfileRepository)

    /**
     * Idempotent (`first-run-onboarding` "Seeding happens exactly once") —
     * safe to call on every onboarding composition, matching
     * [AuthRepository.ensureDefaultAdminSeeded]'s own idempotency guarantee.
     */
    fun ensureDefaultAdminSeeded() {
        viewModelScope.launch { authRepository.ensureDefaultAdminSeeded() }
    }

    /**
     * Persists the single business-profile row (`business-profile` "no
     * second row"). Suspend, not fire-and-forget — [OnboardingScreen] awaits
     * this directly (via `rememberCoroutineScope`, the same idiom
     * [rememberPinGate] already uses) and calls `onComplete` only after it
     * resolves, rather than bridging through a `StateFlow`/`collectAsState`
     * one-shot-event signal.
     */
    suspend fun saveBusinessProfile(name: String, address: String, phone: String) {
        businessProfileRepository.save(name, address, phone)
    }
}
