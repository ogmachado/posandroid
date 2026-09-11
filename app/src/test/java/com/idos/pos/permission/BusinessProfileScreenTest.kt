package com.idos.pos.permission

import android.os.Looper
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.business.BusinessProfileRepository
import com.idos.pos.core.db.PosDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Compose UI test written alongside (task 2.6; `BusinessProfileScreen`/
 * `BusinessProfileViewModel` excluded from strict TDD per
 * `openspec/config.yaml` `strict_tdd_scope` — design.md Testing Strategy
 * "Alongside (Compose)").
 *
 * `android-pos-auth-login-first` (task 2.2) renamed this from
 * `OnboardingScreenTest`: this screen is no longer first-run/gated — it is a
 * repeatable, optional, post-login ADMIN action reached from a header button
 * in `AppRoot()` (see [com.idos.pos.AppRoot]'s class doc). "Business profile
 * is not re-prompted after first capture" no longer applies — the screen is
 * reachable at any time, not gated; see [AuthGateTest] for the gate-level
 * login/authenticated derivation this screen no longer participates in.
 *
 * `android-pos-role-permissions` Phase 2 (design.md Decision B) reintroduces
 * an `authRepository` dependency on [BusinessProfileViewModel] — this time
 * for write-path enforcement (`business-profile`, MODIFIED — "The Profile Is
 * Optional And Editable At Any Time Via An ADMIN-Only Action"), so [setUp]
 * seeds+logs in an ADMIN session by default; the CASHIER-rejection scenario
 * below overrides that session before invoking [BusinessProfileViewModel.save].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class BusinessProfileScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: PosDatabase
    private lateinit var businessProfileRepository: BusinessProfileRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: BusinessProfileViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        businessProfileRepository = BusinessProfileRepository(db.businessProfileDao())
        authRepository = AuthRepository(db.userDao(), context)
        runBlocking {
            authRepository.ensureDefaultAdminSeeded()
            authRepository.login(AuthRepository.DEFAULT_ADMIN_USERNAME, AuthRepository.DEFAULT_ADMIN_PIN)
        }
        viewModel = BusinessProfileViewModel(businessProfileRepository, authRepository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun waitUntil(timeoutMs: Long = 2_000, block: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!block()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    // --- Requirement (business-profile): An ADMIN edits and saves the business profile after login ---

    @Test
    fun savingProfile_persistsNameAddressPhone() = runBlocking {
        var closedCount = 0

        composeTestRule.setContent {
            BusinessProfileScreen(onClose = { closedCount++ }, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag(BUSINESS_PROFILE_NAME_FIELD_TEST_TAG).performTextInput("Acme")
        composeTestRule.onNodeWithTag(BUSINESS_PROFILE_ADDRESS_FIELD_TEST_TAG).performTextInput("123 Main St")
        composeTestRule.onNodeWithTag(BUSINESS_PROFILE_PHONE_FIELD_TEST_TAG).performTextInput("555-0100")
        composeTestRule.onNodeWithTag(BUSINESS_PROFILE_CONFIRM_BUTTON_TEST_TAG).performClick()

        // The confirm button launches `save(...)` then `onClose()` sequentially in the same
        // `rememberCoroutineScope` coroutine (Dispatchers.Main-driven, no Compose
        // recomposition/collectAsState indirection) — waiting for `closedCount > 0` is
        // sufficient and implies the save already committed.
        waitUntil(timeoutMs = 10_000) { closedCount > 0 }

        val profile = businessProfileRepository.get()
        assertEquals("Acme", profile?.name)
        assertEquals("123 Main St", profile?.address)
        assertEquals("555-0100", profile?.phone)
        assertEquals(1, closedCount)
    }

    // --- Requirement (business-profile): opens pre-loaded with any existing saved values ---

    @Test
    fun existingProfile_preLoadsIntoFields() = runBlocking {
        businessProfileRepository.save("Acme", "123 Main St", "555-0100")

        composeTestRule.setContent {
            BusinessProfileScreen(viewModel = viewModel)
        }

        waitUntil(timeoutMs = 10_000) {
            runCatching {
                composeTestRule.onNodeWithTag(BUSINESS_PROFILE_NAME_FIELD_TEST_TAG)
                    .assertTextContains("Acme", substring = true)
            }.isSuccess
        }

        composeTestRule.onNodeWithTag(BUSINESS_PROFILE_ADDRESS_FIELD_TEST_TAG)
            .assertTextContains("123 Main St", substring = true)
        composeTestRule.onNodeWithTag(BUSINESS_PROFILE_PHONE_FIELD_TEST_TAG)
            .assertTextContains("555-0100", substring = true)
        Unit
    }

    // --- Requirement (business-profile, MODIFIED): ADMIN-Only Action, enforced at the write path ---

    @Test
    fun cashierSession_invokingSave_isRejected_screenStaysOpenWithError() = runBlocking {
        authRepository.createUser("cashier1", "1234", UserRole.CASHIER)
        authRepository.login("cashier1", "1234")

        var closedCount = 0
        composeTestRule.setContent {
            BusinessProfileScreen(onClose = { closedCount++ }, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag(BUSINESS_PROFILE_NAME_FIELD_TEST_TAG).performTextInput("Acme")
        composeTestRule.onNodeWithTag(BUSINESS_PROFILE_ADDRESS_FIELD_TEST_TAG).performTextInput("123 Main St")
        composeTestRule.onNodeWithTag(BUSINESS_PROFILE_PHONE_FIELD_TEST_TAG).performTextInput("555-0100")
        composeTestRule.onNodeWithTag(BUSINESS_PROFILE_CONFIRM_BUTTON_TEST_TAG).performClick()

        waitUntil(timeoutMs = 10_000) {
            composeTestRule.onAllNodesWithTag(BUSINESS_PROFILE_ERROR_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(0, closedCount)
        assertNull(businessProfileRepository.get())
    }
}
