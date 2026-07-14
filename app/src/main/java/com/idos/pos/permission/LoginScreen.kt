package com.idos.pos.permission

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * Post-onboarding login gate (`login-gate` spec) — shown by [AuthGate] while
 * a business profile exists but no session is authenticated. A user-picker
 * followed by a PIN pad (design.md "Judgment calls ... Login UX", chosen over
 * a typed username for no-keyboard ergonomics — `login-gate`'s own
 * Design-Level Open Questions left this unfixed).
 *
 * Authentication success is observed reactively by [AuthGateViewModel] via
 * [AuthRepository.currentSession] — this screen never navigates directly, it
 * only calls [LoginViewModel.login] and lets the gate above it react.
 *
 * Deliberately a plain [Column] (matches this codebase's `Scaffold`-avoidance
 * convention — see [com.idos.pos.licensing.ActivationScreen]'s class doc).
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel = posViewModel(LocalAppContainer.current)) {
    val users by viewModel.users.collectAsState()
    val loginFailed by viewModel.loginFailed.collectAsState()

    var selectedUsername by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Select a user")

        users.forEach { user ->
            Button(
                onClick = {
                    selectedUsername = user.username
                    pin = ""
                    viewModel.clearLoginFailed()
                },
                modifier = Modifier.testTag(userPickerItemTestTag(user.username)),
            ) {
                Text(user.username)
            }
        }

        val currentUsername = selectedUsername
        if (currentUsername != null) {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it },
                label = { Text("PIN") },
                modifier = Modifier.fillMaxWidth().testTag(LOGIN_PIN_FIELD_TEST_TAG),
            )

            if (loginFailed) {
                Text(
                    text = "Incorrect PIN",
                    modifier = Modifier.testTag(LOGIN_ERROR_TEST_TAG),
                )
            }

            Button(
                onClick = { viewModel.login(currentUsername, pin) },
                modifier = Modifier.testTag(LOGIN_SUBMIT_BUTTON_TEST_TAG),
            ) {
                Text("Log in")
            }
        }
    }
}

fun userPickerItemTestTag(username: String) = "login-user-picker-$username"

const val LOGIN_PIN_FIELD_TEST_TAG = "login-pin-field"
const val LOGIN_ERROR_TEST_TAG = "login-error"
const val LOGIN_SUBMIT_BUTTON_TEST_TAG = "login-submit-button"
