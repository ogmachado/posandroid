package com.idos.pos.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idos.pos.business.BusinessProfileRepository
import com.idos.pos.core.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gate state derived by [AuthGateViewModel] (design.md "Gate stack & session
 * flow" / Decision E). [Loading] covers the brief window before the initial
 * business-profile existence check resolves — [AuthGate] renders nothing for
 * it, avoiding a flash of the wrong screen before the real answer is known.
 */
sealed interface AuthGateState {
    data object Loading : AuthGateState
    data object Onboarding : AuthGateState
    data object Login : AuthGateState
    data class Authenticated(val role: UserRole) : AuthGateState
}

/**
 * Derives [AuthGateState] from [BusinessProfileRepository.exists] and
 * [AuthRepository.currentSession] (design.md — "`AuthGateViewModel` ...
 * derives `AuthGateState` from two sources"). Excluded from strict TDD
 * (ViewModel classes, per `openspec/config.yaml` `strict_tdd_scope.exclude`) —
 * covered alongside by `AuthGateTest`.
 *
 * Reactive on [AuthRepository.currentSession] (a `StateFlow`, so a
 * login/session change automatically re-derives state); the business-profile
 * existence check is NOT itself a `StateFlow`-backed value
 * ([BusinessProfileRepository.exists] is a one-shot suspend query), so
 * [onOnboardingCompleted] must be called explicitly once onboarding finishes
 * persisting the profile — nothing else would ever trigger a re-check.
 */
class AuthGateViewModel(
    private val authRepository: AuthRepository,
    private val businessProfileRepository: BusinessProfileRepository,
) : ViewModel() {

    constructor(container: AppContainer) : this(container.authRepository, container.businessProfileRepository)

    private val _authGateState = MutableStateFlow<AuthGateState>(AuthGateState.Loading)
    val authGateState: StateFlow<AuthGateState> = _authGateState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.currentSession.collect { session -> recompute(session) }
        }
    }

    /**
     * Re-checks business-profile existence — called by [AuthGate] after
     * [OnboardingScreen] reports its save completed (design.md
     * "Onboarding → OnboardingScreen(onComplete = refresh)").
     */
    fun onOnboardingCompleted() {
        viewModelScope.launch { recompute(authRepository.currentSession.value) }
    }

    private suspend fun recompute(session: AuthSession?) {
        _authGateState.value = when {
            !businessProfileRepository.exists() -> AuthGateState.Onboarding
            session == null -> AuthGateState.Login
            else -> AuthGateState.Authenticated(session.role)
        }
    }
}
