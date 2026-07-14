package com.idos.pos.permission

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Compose UI test written alongside (task 2.6; `OnboardingScreen`/
 * `OnboardingViewModel` excluded from strict TDD per `openspec/config.yaml`
 * `strict_tdd_scope` — design.md Testing Strategy "Alongside (Compose)").
 *
 * "Business profile is not re-prompted after first capture" is covered at the
 * [AuthGate] level, not here — see
 * [AuthGateTest.businessProfileExists_noSession_showsLogin_notOnboardingOrPosShell]
 * — because that behavior is [AuthGateViewModel]'s derivation
 * (`businessProfileRepository.exists()`), not something this screen itself
 * decides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: PosDatabase
    private lateinit var authRepository: AuthRepository
    private lateinit var businessProfileRepository: BusinessProfileRepository
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authRepository = AuthRepository(db.userDao(), context)
        businessProfileRepository = BusinessProfileRepository(db.businessProfileDao())
        viewModel = OnboardingViewModel(authRepository, businessProfileRepository)
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

    // --- Requirements: Default ADMIN Is Auto-Seeded / Business Profile Is Captured Once ---

    @Test
    fun confirmingOnboarding_seedsDefaultAdmin_persistsProfileOnce_andNotifiesCompleteExactlyOnce() = runBlocking {
        var completedCount = 0

        composeTestRule.setContent {
            OnboardingScreen(onComplete = { completedCount++ }, viewModel = viewModel)
        }

        // ensureDefaultAdminSeeded() runs in a LaunchedEffect(Unit) on first composition.
        // Generous timeout: PBKDF2 (120k iterations, PinHasher) pays a one-time JVM/crypto-provider
        // warm-up cost the first time it runs in a fresh test JVM.
        waitUntil(timeoutMs = 10_000) { db.userDao().count() > 0 }

        composeTestRule.onNodeWithTag(ONBOARDING_NAME_FIELD_TEST_TAG).performTextInput("Acme")
        composeTestRule.onNodeWithTag(ONBOARDING_ADDRESS_FIELD_TEST_TAG).performTextInput("123 Main St")
        composeTestRule.onNodeWithTag(ONBOARDING_PHONE_FIELD_TEST_TAG).performTextInput("555-0100")
        composeTestRule.onNodeWithTag(ONBOARDING_CONFIRM_BUTTON_TEST_TAG).performClick()

        // The confirm button launches `saveBusinessProfile(...)` then `onComplete()` sequentially
        // in the same `rememberCoroutineScope` coroutine (Dispatchers.Main-driven, no Compose
        // recomposition/collectAsState indirection) — waiting for `completedCount > 0` is
        // sufficient and implies the save already committed.
        waitUntil(timeoutMs = 10_000) { completedCount > 0 }

        val admin = db.userDao().findByUsername(AuthRepository.DEFAULT_ADMIN_USERNAME)
        assertEquals(AuthRepository.DEFAULT_ADMIN_USERNAME, admin?.username)
        assertEquals(UserRole.ADMIN.name, admin?.role)

        val profile = businessProfileRepository.get()
        assertEquals("Acme", profile?.name)
        assertEquals("123 Main St", profile?.address)
        assertEquals("555-0100", profile?.phone)
        assertEquals(1, completedCount)
    }
}
