package com.idos.pos.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel
import com.idos.pos.nav.PosNavHost

/**
 * 2-way login/authenticated gate, slotted inside `AppRoot()` in place of the
 * direct `PosNavHost()` call — the same boolean/state-composable-swap idiom
 * [com.idos.pos.EnforcementGate] already uses for the license gate above this
 * one.
 *
 * `android-pos-auth-login-first` (task 1.3; design.md "Decision:
 * `recompute()` becomes non-suspend; constructor drops one param") removes
 * the `Onboarding` branch: login is always the first screen reachable once
 * the license check passes — see `login-gate`'s amended "Gate Sits Inside
 * AppRoot, After License, Before PosNavHost". Business-profile setup is now a
 * post-login ADMIN action reached from `AppRoot()`'s header
 * ([com.idos.pos.permission.BusinessProfileScreen]), not rendered by this gate
 * at all.
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
        AuthGateState.Login -> LoginScreen()
        is AuthGateState.Authenticated -> PosNavHost(current.role)
    }
}
