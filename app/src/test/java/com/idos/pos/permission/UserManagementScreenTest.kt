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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Compose UI test written alongside (task 4.3; `UserManagementScreen`/
 * `UserManagementViewModel` excluded from strict TDD per `openspec/config.yaml`
 * `strict_tdd_scope` — design.md Testing Strategy "Alongside (Compose)").
 *
 * "A CASHIER session has no reachable path to the screen" (`user-management`
 * scenario) is NOT covered here — this screen itself never checks the role
 * (see [UserManagementViewModel]'s class doc); that scenario is covered at
 * the `AppRoot`/header-action level by
 * [com.idos.pos.EnforcementGateTest.cashierSession_hasNoUserManagementHeaderAction],
 * mirroring how [BusinessProfileScreenTest] defers its own cross-cutting
 * scenario to [AuthGateTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class UserManagementScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: PosDatabase
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: UserManagementViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authRepository = AuthRepository(db.userDao(), context)
        runBlocking {
            authRepository.ensureDefaultAdminSeeded()
            authRepository.login(AuthRepository.DEFAULT_ADMIN_USERNAME, AuthRepository.DEFAULT_ADMIN_PIN)
        }
        viewModel = UserManagementViewModel(authRepository, db.userDao())
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

    // --- Requirement (user-management): Only ADMIN Can Create Accounts ---

    @Test
    fun anAdmin_createsANewCashierAccount() = runBlocking {
        composeTestRule.setContent { UserManagementScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag(NEW_USERNAME_FIELD_TEST_TAG).performTextInput("maria")
        composeTestRule.onNodeWithTag(NEW_USER_PIN_FIELD_TEST_TAG).performTextInput("1234")
        composeTestRule.onNodeWithTag(ROLE_CASHIER_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CREATE_USER_BUTTON_TEST_TAG).performClick()

        waitUntil { db.userDao().findByUsername("maria") != null }

        val created = db.userDao().findByUsername("maria")
        assertNotNull(created)
        assertEquals(UserRole.CASHIER.name, created?.role)
    }

    @Test
    fun anAdmin_createsANewAdminAccount() = runBlocking {
        composeTestRule.setContent { UserManagementScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag(NEW_USERNAME_FIELD_TEST_TAG).performTextInput("second-admin")
        composeTestRule.onNodeWithTag(NEW_USER_PIN_FIELD_TEST_TAG).performTextInput("5678")
        composeTestRule.onNodeWithTag(ROLE_ADMIN_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CREATE_USER_BUTTON_TEST_TAG).performClick()

        waitUntil { db.userDao().findByUsername("second-admin") != null }

        val created = db.userDao().findByUsername("second-admin")
        assertNotNull(created)
        assertEquals(UserRole.ADMIN.name, created?.role)
    }

    // --- Requirement (user-management): New Accounts Require A Unique Identifier And An Individually Set PIN ---

    @Test
    fun duplicateUsername_isRejected() = runBlocking {
        authRepository.createUser("maria", "1234", UserRole.CASHIER)

        composeTestRule.setContent { UserManagementScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag(NEW_USERNAME_FIELD_TEST_TAG).performTextInput("maria")
        composeTestRule.onNodeWithTag(NEW_USER_PIN_FIELD_TEST_TAG).performTextInput("9999")
        composeTestRule.onNodeWithTag(CREATE_USER_BUTTON_TEST_TAG).performClick()

        waitUntil {
            composeTestRule.onAllNodesWithTag(USER_MANAGEMENT_ERROR_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(1, db.userDao().findAll().count { it.username == "maria" })
    }

    @Test
    fun blankPin_isRejected() = runBlocking {
        composeTestRule.setContent { UserManagementScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag(NEW_USERNAME_FIELD_TEST_TAG).performTextInput("no-pin-user")
        composeTestRule.onNodeWithTag(CREATE_USER_BUTTON_TEST_TAG).performClick()

        waitUntil {
            composeTestRule.onAllNodesWithTag(USER_MANAGEMENT_ERROR_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        assertNull(db.userDao().findByUsername("no-pin-user"))
    }

    // --- Requirement (user-management): An ADMIN Can View The List Of Existing Accounts ---

    @Test
    fun anAdmin_viewsExistingAccounts_identifierAndRoleVisible() = runBlocking {
        authRepository.createUser("maria", "1234", UserRole.CASHIER)
        authRepository.createUser("carlos", "4321", UserRole.ADMIN)
        viewModel = UserManagementViewModel(authRepository, db.userDao())

        composeTestRule.setContent { UserManagementScreen(viewModel = viewModel) }

        waitUntil {
            composeTestRule.onAllNodesWithTag(userListItemTestTag("maria")).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag(userListItemTestTag(AuthRepository.DEFAULT_ADMIN_USERNAME)).assertExists()
        composeTestRule.onNodeWithTag(userListItemTestTag("maria")).assertExists()
        composeTestRule.onNodeWithTag(userListItemTestTag("carlos")).assertExists()
        Unit
    }

    // --- Requirement (first-run-onboarding): The Seeded Credential Must Be Changeable ---

    @Test
    fun theDefaultAdmin_changesTheirSeededPin() = runBlocking {
        composeTestRule.setContent { UserManagementScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag(CHANGE_PIN_FIELD_TEST_TAG).performTextInput("newpin1")
        composeTestRule.onNodeWithTag(CHANGE_PIN_BUTTON_TEST_TAG).performClick()

        waitUntil { viewModel.pinChanged.value > 0 }

        assertTrue(authRepository.login(AuthRepository.DEFAULT_ADMIN_USERNAME, "newpin1"))
        assertEquals(false, authRepository.login(AuthRepository.DEFAULT_ADMIN_USERNAME, AuthRepository.DEFAULT_ADMIN_PIN))
    }
}
