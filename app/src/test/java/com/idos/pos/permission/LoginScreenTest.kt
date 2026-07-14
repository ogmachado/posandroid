package com.idos.pos.permission

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * Compose UI test written alongside (task 2.6; `LoginScreen`/`LoginViewModel`
 * excluded from strict TDD per `openspec/config.yaml` `strict_tdd_scope` —
 * design.md Testing Strategy "Alongside (Compose)").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: PosDatabase
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authRepository = AuthRepository(db.userDao(), context)
        runBlocking { authRepository.ensureDefaultAdminSeeded() }
        viewModel = LoginViewModel(authRepository, db.userDao())
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

    // --- Requirement (login-gate): Correct User + PIN Authenticates ---

    @Test
    fun correctUserAndPin_authenticates_andRevealsRole() = runBlocking {
        composeTestRule.setContent { LoginScreen(viewModel = viewModel) }

        waitUntil {
            composeTestRule.onAllNodesWithTag(userPickerItemTestTag(AuthRepository.DEFAULT_ADMIN_USERNAME))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag(userPickerItemTestTag(AuthRepository.DEFAULT_ADMIN_USERNAME)).performClick()
        composeTestRule.onNodeWithTag(LOGIN_PIN_FIELD_TEST_TAG).performTextInput(AuthRepository.DEFAULT_ADMIN_PIN)
        composeTestRule.onNodeWithTag(LOGIN_SUBMIT_BUTTON_TEST_TAG).performClick()

        waitUntil { authRepository.currentSession.value != null }

        assertEquals(UserRole.ADMIN, authRepository.currentSession.value?.role)
    }

    // --- Requirement (login-gate): Incorrect PIN blocks authentication ---

    @Test
    fun incorrectPin_rejectsAuthentication_keepsLoginScreenShown() = runBlocking {
        composeTestRule.setContent { LoginScreen(viewModel = viewModel) }

        waitUntil {
            composeTestRule.onAllNodesWithTag(userPickerItemTestTag(AuthRepository.DEFAULT_ADMIN_USERNAME))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag(userPickerItemTestTag(AuthRepository.DEFAULT_ADMIN_USERNAME)).performClick()
        composeTestRule.onNodeWithTag(LOGIN_PIN_FIELD_TEST_TAG).performTextInput("wrong-pin")
        composeTestRule.onNodeWithTag(LOGIN_SUBMIT_BUTTON_TEST_TAG).performClick()

        waitUntil {
            composeTestRule.onAllNodesWithTag(LOGIN_ERROR_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        assertNull(authRepository.currentSession.value)
        composeTestRule.onNodeWithTag(LOGIN_PIN_FIELD_TEST_TAG).assertExists()
        Unit
    }
}
