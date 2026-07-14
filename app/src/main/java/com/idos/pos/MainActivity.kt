package com.idos.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel
import com.idos.pos.licensing.ActivationScreen
import com.idos.pos.licensing.ActivationViewModel
import com.idos.pos.licensing.LicenseStatus
import com.idos.pos.licensing.licensed
import com.idos.pos.permission.AuthGate
import kotlinx.coroutines.launch

/**
 * App entry point + license enforcement gate (`android-pos-licensing` Phase 5;
 * specs/license-enforcement-gate/spec.md; design.md Decision C/D).
 *
 * **Gate (Decision C)**: a plain boolean composable swap, NOT a nav-graph
 * route — `if (licenseStatus.licensed) AppRoot() else ActivationScreen()`.
 * [licenseStatus] starts at [PosApplication.initialLicenseStatus] (evaluated
 * synchronously before this activity's first composition — task 5.2) and is
 * re-evaluated:
 * - on every [onResume] (Decision D: single-activity foreground heartbeat
 *   trigger), after [com.idos.pos.licensing.LicenseRepository.heartbeat] runs, and
 * - immediately after [ActivationScreen] reports a successful install
 *   (`onInstalled`), so the gate flips without restarting the activity.
 *
 * **`AppRoot()` originally was just the extracted Phase-0 bootstrap content**
 * (`Surface { Box(center) { BootstrapPlaceholder() } }`, per design.md
 * Decision C — "`AppRoot()` = extract of today's Phase-0 `MainActivity`
 * content, renamed — NO shell/nav introduced"). That gap (no navigation
 * graph, none of the standalone `android-pos-mvp` feature screens wired in)
 * was closed by a later, separate piece of work: `AppRoot()`'s content slot
 * rendered [com.idos.pos.nav.PosNavHost] directly — a 4-tab bottom-navigation
 * shell (Venta/Productos/Inventario/Caja) — instead of [BootstrapPlaceholder].
 * `android-pos-auth` Phase 2 (task 2.5; design.md Decision E) inserts the
 * identity gate stack in front of that shell: `AppRoot()`'s content slot now
 * renders [com.idos.pos.permission.AuthGate] — a nested `when` swap
 * (Onboarding → Login → `PosNavHost`) — instead of calling `PosNavHost()`
 * directly. See that composable's class doc for the onboarding/login design.
 * The license-gate wiring above this composable is untouched.
 */
class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer
    private var licenseStatus by mutableStateOf(LicenseStatus.NOT_CONFIGURED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val posApplication = application as PosApplication
        appContainer = posApplication.appContainer
        licenseStatus = posApplication.initialLicenseStatus

        setContent {
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                MaterialTheme {
                    EnforcementGate(licenseStatus = licenseStatus, onInstalled = { refreshLicenseStatus() })
                }
            }
        }
    }

    /** Decision D: single-activity foreground heartbeat trigger, plus a fresh status re-evaluation. */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            appContainer.licenseRepository.heartbeat()
            licenseStatus = appContainer.licenseRepository.currentStatus()
        }
    }

    private fun refreshLicenseStatus() {
        lifecycleScope.launch {
            licenseStatus = appContainer.licenseRepository.currentStatus()
        }
    }
}

/**
 * The gate itself (design.md Decision C / specs/license-enforcement-gate/spec.md
 * "Gate Is a Top-Level Composable Swap, Not Navigation"), extracted out of
 * [MainActivity.onCreate] into its own composable so it is directly testable
 * with each [LicenseStatus] — the same testability idiom this codebase already
 * uses for its other boolean-swap gate,
 * [com.idos.pos.scan.CameraPermissionGate].
 *
 * [activationViewModel] defaults to [posViewModel] (reading the real
 * [AppContainer] via [LocalAppContainer]) for production use; tests override
 * it directly with a fake-collaborator [ActivationViewModel] (mirroring
 * [com.idos.pos.licensing.ActivationScreenTest]'s pattern) so they never need a
 * real [AppContainer] — [com.idos.pos.licensing.InstallationIdStore]'s Android
 * Keystore dependency is unavailable under Robolectric in this environment
 * (see that class's KDoc), so the NOT_CONFIGURED/EXPIRED/COMPROMISED branches
 * here cannot be exercised against the real DI graph in a JVM test.
 */
@Composable
fun EnforcementGate(
    licenseStatus: LicenseStatus,
    onInstalled: () -> Unit,
    activationViewModel: ActivationViewModel = posViewModel(LocalAppContainer.current),
) {
    if (licenseStatus.licensed) {
        AppRoot(isInGracePeriod = licenseStatus == LicenseStatus.IN_GRACE_PERIOD)
    } else {
        ActivationScreen(onInstalled = onInstalled, viewModel = activationViewModel)
    }
}

/**
 * Licensed app entry composable (design.md Decision C). [isInGracePeriod]
 * renders a persistent warning banner (task 5.3;
 * specs/license-enforcement-gate/spec.md "Grace Period Grants Full Access
 * With a Warning") above the main content; the content itself is
 * [com.idos.pos.permission.AuthGate] (`android-pos-auth` Phase 2 task 2.5 —
 * onboarding/login gate stack in front of [com.idos.pos.nav.PosNavHost], the
 * bottom-navigation shell wiring the real POS screens — see that composable's
 * class doc). Grace status never restricts POS functionality, only surfaces
 * the warning above it.
 */
@Composable
fun AppRoot(isInGracePeriod: Boolean = false) {
    Surface {
        Column(modifier = Modifier.fillMaxSize()) {
            if (isInGracePeriod) {
                GracePeriodBanner()
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(APP_ROOT_CONTENT_TEST_TAG),
                contentAlignment = Alignment.Center,
            ) {
                AuthGate()
            }
        }
    }
}

@Composable
private fun GracePeriodBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().testTag(GRACE_PERIOD_BANNER_TEST_TAG),
    ) {
        Text(
            text = "License is in its grace period. Install a new license soon to avoid losing POS access.",
            modifier = Modifier.padding(12.dp),
        )
    }
}

const val GRACE_PERIOD_BANNER_TEST_TAG = "app-root-grace-period-banner"
const val APP_ROOT_CONTENT_TEST_TAG = "app-root-content"
