package com.idos.pos.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idos.pos.core.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gate state derived by [AuthGateViewModel]. [Loading] covers the brief
 * window before [AuthRepository.currentSession]'s first value has been
 * collected — [AuthGate] renders nothing for it, avoiding a flash of the
 * wrong screen before the real answer is known.
 *
 * `android-pos-auth-login-first` (task 1.2; design.md "Decision: `recompute()`
 * becomes non-suspend; constructor drops one param") removes the `Onboarding`
 * state entirely — see `login-gate`'s amended "Gate Sits Inside AppRoot,
 * After License, Before PosNavHost" (no onboarding step). The gate is now
 * exactly `Loading | Login | Authenticated`.
 */
sealed interface AuthGateState {
    data object Loading : AuthGateState
    data object Login : AuthGateState
    data class Authenticated(val role: UserRole) : AuthGateState
}

/**
 * Derives [AuthGateState] from [AuthRepository.currentSession] alone.
 * Excluded from strict TDD (ViewModel classes, per `openspec/config.yaml`
 * `strict_tdd_scope.exclude`) — covered alongside by `AuthGateTest`.
 *
 * Reactive on [AuthRepository.currentSession] (a `StateFlow`, so a
 * login/session change automatically re-derives state) — `recompute()` is a
 * plain, non-suspend function since business-profile existence is no longer
 * part of the derivation (`android-pos-auth-login-first` task 1.2; design.md
 * "Decision: `recompute()` becomes non-suspend; constructor drops one
 * param"). This is a reversal of `android-pos-auth` Decision E, which
 * previously gated login behind [com.idos.pos.business.BusinessProfileRepository.exists];
 * that dependency and its `onOnboardingCompleted()` refresh hook are removed
 * — see `first-run-onboarding`'s amended "Default ADMIN Is Auto-Seeded
 * Unconditionally At Process Start" for where seeding now happens instead
 * ([com.idos.pos.PosApplication.onCreate]).
 */
class AuthGateViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    constructor(container: AppContainer) : this(container.authRepository)

    private val _authGateState = MutableStateFlow<AuthGateState>(AuthGateState.Loading)
    val authGateState: StateFlow<AuthGateState> = _authGateState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.currentSession.collect { session -> recompute(session) }
        }
    }

    private fun recompute(session: AuthSession?) {
        _authGateState.value =
            if (session == null) AuthGateState.Login
            else AuthGateState.Authenticated(session.role)
    }
}
