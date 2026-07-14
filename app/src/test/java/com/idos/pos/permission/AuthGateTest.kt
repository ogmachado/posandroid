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
 * onboarding/login/authenticated derivation is driven by the actual
 * [AuthRepository] / [com.idos.pos.business.BusinessProfileRepository], not
 * fakes — mirrors [com.idos.pos.EnforcementGateTest]'s real-container pattern.
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

    // --- Requirement (first-run-onboarding): Onboarding Blocks Access Until Complete ---

    @Test
    fun noBusinessProfile_showsOnboarding_posShellUnreachable() = runBlocking {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AuthGate()
            }
        }

        waitUntil { composeTestRule.onAllNodesWithTag(ONBOARDING_NAME_FIELD_TEST_TAG).fetchSemanticsNodes().isNotEmpty() }

        composeTestRule.onNodeWithTag(ONBOARDING_NAME_FIELD_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TEST_TAG).assertDoesNotExist()

        // OnboardingScreen's LaunchedEffect(Unit) fires ensureDefaultAdminSeeded()
        // on first composition (same as OnboardingScreenTest). Wait for that
        // write to actually land before @After's tearDown() closes the
        // in-memory database — otherwise the seed coroutine can still be
        // mid-write when close() runs, intermittently surfacing as a
        // CloseGuard "Explicit termination method 'close' not called" warning
        // (no assertion failure, but a real teardown race). Generous timeout:
        // PBKDF2 (120k iterations, PinHasher) pays a one-time JVM/crypto-provider
        // warm-up cost the first time it runs in a fresh test JVM.
        waitUntil(timeoutMs = 10_000) { container.database.userDao().count() > 0 }
    }

    // --- Requirement (login-gate): Unauthenticated session shows the login screen, not the POS shell ---

    @Test
    fun businessProfileExists_noSession_showsLogin_notOnboardingOrPosShell() = runBlocking {
        container.businessProfileRepository.save("Acme", "123 Main St", "555-0100")
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
        composeTestRule.onNodeWithTag(ONBOARDING_NAME_FIELD_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TEST_TAG).assertDoesNotExist()
    }

    // --- Requirement (login-gate): Authenticated session shows the POS shell ---

    @Test
    fun authenticatedSession_showsPosShell() = runBlocking {
        container.businessProfileRepository.save("Acme", "123 Main St", "555-0100")
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
