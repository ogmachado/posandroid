package com.idos.pos.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel
import com.idos.pos.nav.PosNavHost

/**
 * 3-way onboarding/login/authenticated gate (design.md Decision E), slotted
 * inside `AppRoot()` in place of the direct `PosNavHost()` call — the same
 * boolean/state-composable-swap idiom [com.idos.pos.EnforcementGate] already
 * uses for the license gate above this one.
 *
 * **`Authenticated(role) -> PosNavHost(role)`** (design.md Decision E/H):
 * `android-pos-auth` Phase 3 task 3.4 added the `role`-aware
 * [com.idos.pos.nav.PosNavHost] overload plus `visibleTabsFor(role)` tab
 * filtering, so this branch now passes the session's role straight through —
 * no more Work-Unit-2-era placeholder no-arg call.
 */
@Composable
fun AuthGate(viewModel: AuthGateViewModel = posViewModel(LocalAppContainer.current)) {
    val state by viewModel.authGateState.collectAsState()

    when (val current = state) {
        AuthGateState.Loading -> Unit
        AuthGateState.Onboarding -> OnboardingScreen(onComplete = viewModel::onOnboardingCompleted)
        AuthGateState.Login -> LoginScreen()
        is AuthGateState.Authenticated -> PosNavHost(current.role)
    }
}
