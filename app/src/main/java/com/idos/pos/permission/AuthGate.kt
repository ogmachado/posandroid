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
 * **Deviation from design.md's literal `Authenticated(role) -> PosNavHost(role)`
 * line**: [com.idos.pos.nav.PosNavHost] does not yet take a `role` parameter —
 * that overload, plus `visibleTabsFor(role)` tab filtering, is
 * `android-pos-auth` Phase 3 task 3.4 (out of scope for this Work Unit 2 / PR
 * 2 batch, which is scoped to tasks 2.1-2.6 only). This composable therefore
 * calls the existing no-arg `PosNavHost()` for now; Phase 3 task 3.4 will
 * change this call site to `PosNavHost(current.role)` once that overload
 * exists. Nothing about the gate ordering/derivation this task owns changes
 * as a result — only the render target's arity.
 */
@Composable
fun AuthGate(viewModel: AuthGateViewModel = posViewModel(LocalAppContainer.current)) {
    val state by viewModel.authGateState.collectAsState()

    when (state) {
        AuthGateState.Loading -> Unit
        AuthGateState.Onboarding -> OnboardingScreen(onComplete = viewModel::onOnboardingCompleted)
        AuthGateState.Login -> LoginScreen()
        is AuthGateState.Authenticated -> PosNavHost()
    }
}
