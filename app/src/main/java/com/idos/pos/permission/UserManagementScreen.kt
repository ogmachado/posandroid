package com.idos.pos.permission

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel

/**
 * ADMIN-only user list + create-user form + change-own-PIN flow
 * (`user-management` / `first-run-onboarding` "credential must be changeable"
 * specs; design.md Decision J). Reached from a slim header action in
 * [com.idos.pos.AppRoot] via a boolean-swap overlay — [onClose] returns to the
 * POS shell. Reachability (ADMIN-only) is enforced by that header action, not
 * by this screen itself (see [UserManagementViewModel]'s class doc).
 *
 * Role selection uses two plain [Button]s (ADMIN / CASHIER) rather than a
 * `DropdownMenu`-backed picker — matching [LoginScreen]'s user-picker
 * convention and avoiding the `DropdownMenu`/`Popup` mid-test-open testability
 * limitation documented in `nav/PosNavHostTest.kt`'s class doc.
 *
 * Deliberately a plain [Column] (matches this codebase's `Scaffold`-avoidance
 * convention — see [com.idos.pos.licensing.ActivationScreen]'s class doc).
 */
@Composable
fun UserManagementScreen(
    onClose: () -> Unit = {},
    viewModel: UserManagementViewModel = posViewModel(LocalAppContainer.current),
) {
    val users by viewModel.users.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    var newUsername by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf(UserRole.CASHIER) }
    var changePinValue by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("User Management", modifier = Modifier.testTag(USER_MANAGEMENT_TITLE_TEST_TAG))
            Button(onClick = onClose, modifier = Modifier.testTag(USER_MANAGEMENT_CLOSE_BUTTON_TEST_TAG)) {
                Text("Back to POS")
            }
        }

        Text("Existing users")
        LazyColumn(modifier = Modifier.testTag(USER_LIST_TEST_TAG)) {
            items(users) { user ->
                Text(
                    text = "${user.username} (${user.role})",
                    modifier = Modifier.testTag(userListItemTestTag(user.username)),
                )
            }
        }

        Text("Create a new user")
        OutlinedTextField(
            value = newUsername,
            onValueChange = { newUsername = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth().testTag(NEW_USERNAME_FIELD_TEST_TAG),
        )
        OutlinedTextField(
            value = newPin,
            onValueChange = { newPin = it },
            label = { Text("PIN") },
            modifier = Modifier.fillMaxWidth().testTag(NEW_USER_PIN_FIELD_TEST_TAG),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { newRole = UserRole.CASHIER },
                modifier = Modifier.testTag(ROLE_CASHIER_BUTTON_TEST_TAG),
            ) {
                Text(if (newRole == UserRole.CASHIER) "* CASHIER" else "CASHIER")
            }
            Button(
                onClick = { newRole = UserRole.ADMIN },
                modifier = Modifier.testTag(ROLE_ADMIN_BUTTON_TEST_TAG),
            ) {
                Text(if (newRole == UserRole.ADMIN) "* ADMIN" else "ADMIN")
            }
        }

        Button(
            onClick = {
                viewModel.createUser(newUsername, newPin, newRole)
                newUsername = ""
                newPin = ""
            },
            modifier = Modifier.testTag(CREATE_USER_BUTTON_TEST_TAG),
        ) {
            Text("Create user")
        }

        if (lastError != null) {
            Text(
                text = "Could not create user: $lastError",
                modifier = Modifier.testTag(USER_MANAGEMENT_ERROR_TEST_TAG),
            )
        }

        Text("Change my PIN")
        OutlinedTextField(
            value = changePinValue,
            onValueChange = { changePinValue = it },
            label = { Text("New PIN") },
            modifier = Modifier.fillMaxWidth().testTag(CHANGE_PIN_FIELD_TEST_TAG),
        )
        Button(
            onClick = {
                viewModel.changeOwnPin(changePinValue)
                changePinValue = ""
            },
            modifier = Modifier.testTag(CHANGE_PIN_BUTTON_TEST_TAG),
        ) {
            Text("Change PIN")
        }
    }
}

fun userListItemTestTag(username: String) = "user-management-user-$username"

const val USER_MANAGEMENT_TITLE_TEST_TAG = "user-management-title"
const val USER_MANAGEMENT_CLOSE_BUTTON_TEST_TAG = "user-management-close-button"
const val USER_LIST_TEST_TAG = "user-management-user-list"
const val NEW_USERNAME_FIELD_TEST_TAG = "user-management-new-username-field"
const val NEW_USER_PIN_FIELD_TEST_TAG = "user-management-new-pin-field"
const val ROLE_ADMIN_BUTTON_TEST_TAG = "user-management-role-admin-button"
const val ROLE_CASHIER_BUTTON_TEST_TAG = "user-management-role-cashier-button"
const val CREATE_USER_BUTTON_TEST_TAG = "user-management-create-button"
const val USER_MANAGEMENT_ERROR_TEST_TAG = "user-management-error"
const val CHANGE_PIN_FIELD_TEST_TAG = "user-management-change-pin-field"
const val CHANGE_PIN_BUTTON_TEST_TAG = "user-management-change-pin-button"
