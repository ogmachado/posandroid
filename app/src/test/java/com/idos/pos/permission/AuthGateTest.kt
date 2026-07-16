package com.idos.pos.permission

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.nav.BOTTOM_NAV_TEST_TAG
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Compose UI test written alongside (task 2.6; `AuthGate`/`AuthGateViewModel`
 * excluded from strict TDD per `openspec/config.yaml` `strict_tdd_scope` —
 * design.md Testing Strategy "Alongside (Compose)"). Exercises the real
 * [AuthGate] wired to a real [AppContainer.createInMemory] so
 * login/authenticated derivation is driven by the actual [AuthRepository],
 * not a fake — mirrors [com.idos.pos.EnforcementGateTest]'s real-container
 * pattern.
 *
 * `android-pos-auth-login-first` (task 2.1) drops the `Onboarding` scenario
 * entirely (`AuthGateState.Onboarding` no longer exists) and the
 * `businessProfileRepository.save(...)` preconditions from the remaining
 * tests — gate reachability no longer depends on business-profile existence
 * (`login-gate`'s amended "Gate Sits Inside AppRoot, After License, Before
 * PosNavHost"). `AppContainer.createInMemory()` bypasses
 * [com.idos.pos.PosApplication.onCreate]'s new unconditional seed call, so
 * each test still seeds explicitly via `ensureDefaultAdminSeeded()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class AuthGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force onCreate/seed
    }

    @After
    fun tearDown() {
        container.database.close()
    }

    private suspend fun waitUntil(timeoutMs: Long = 2_000, block: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!block()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    // --- Requirement (login-gate): Unauthenticated session shows the login screen, not the POS shell ---

    @Test
    fun noSession_showsLogin_notPosShell() = runBlocking {
        container.authRepository.ensureDefaultAdminSeeded()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AuthGate()
            }
        }

        waitUntil {
            composeTestRule.onAllNodesWithTag(userPickerItemTestTag(AuthRepository.DEFAULT_ADMIN_USERNAME))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag(userPickerItemTestTag(AuthRepository.DEFAULT_ADMIN_USERNAME)).assertExists()
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TEST_TAG).assertDoesNotExist()
    }

    // --- Requirement (login-gate): Authenticated session shows the POS shell ---

    @Test
    fun authenticatedSession_showsPosShell() = runBlocking {
        container.authRepository.ensureDefaultAdminSeeded()
        container.authRepository.login(AuthRepository.DEFAULT_ADMIN_USERNAME, AuthRepository.DEFAULT_ADMIN_PIN)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AuthGate()
            }
        }

        waitUntil { composeTestRule.onAllNodesWithTag(BOTTOM_NAV_TEST_TAG).fetchSemanticsNodes().isNotEmpty() }

        composeTestRule.onNodeWithTag(BOTTOM_NAV_TEST_TAG).assertExists()
        Unit
    }
}
