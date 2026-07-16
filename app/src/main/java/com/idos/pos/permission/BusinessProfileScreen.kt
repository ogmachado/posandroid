package com.idos.pos.permission

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel
import kotlinx.coroutines.launch

/**
 * Business-profile setup/edit screen (`business-profile` spec, amended by
 * `android-pos-auth-login-first`) — a repeatable, optional, post-login
 * ADMIN action reached from a second header button in [com.idos.pos.AppRoot],
 * next to "Manage users" (mirroring [UserManagementScreen]'s
 * boolean-swap-overlay + `onClose` pattern). No longer shown by [AuthGate]
 * (renamed and repurposed from the retired `OnboardingScreen`; task 1.4).
 *
 * Pre-loads any existing saved values via [BusinessProfileViewModel.load] on
 * first composition (`business-profile`'s "opens ... pre-loaded with any
 * existing saved values" scenario) instead of the previous
 * `ensureDefaultAdminSeeded()` `LaunchedEffect` — default-ADMIN seeding now
 * happens unconditionally in [com.idos.pos.PosApplication.onCreate].
 *
 * The confirm action is driven directly from a `rememberCoroutineScope`
 * launch (the same idiom [rememberPinGate] already uses for a suspend
 * verifier), rather than bridging through a `StateFlow`/`collectAsState`
 * one-shot-event signal — simpler, and calls `onClose` deterministically
 * right after the save resolves.
 *
 * Deliberately a plain [Column], matching this codebase's established
 * `Scaffold`-avoidance convention (see [com.idos.pos.licensing.ActivationScreen]'s
 * class doc for the underlying Robolectric-Compose reason).
 */
@Composable
fun BusinessProfileScreen(
    onClose: () -> Unit = {},
    viewModel: BusinessProfileViewModel = posViewModel(LocalAppContainer.current),
) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.load()?.let { profile ->
            name = profile.name
            address = profile.address
            phone = profile.phone
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Business profile", modifier = Modifier.testTag(BUSINESS_PROFILE_TITLE_TEST_TAG))
            Button(onClick = onClose, modifier = Modifier.testTag(BUSINESS_PROFILE_CLOSE_BUTTON_TEST_TAG)) {
                Text("Back to POS")
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Business name") },
            modifier = Modifier.fillMaxWidth().testTag(BUSINESS_PROFILE_NAME_FIELD_TEST_TAG),
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Address") },
            modifier = Modifier.fillMaxWidth().testTag(BUSINESS_PROFILE_ADDRESS_FIELD_TEST_TAG),
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone") },
            modifier = Modifier.fillMaxWidth().testTag(BUSINESS_PROFILE_PHONE_FIELD_TEST_TAG),
        )

        Button(
            onClick = {
                scope.launch {
                    viewModel.save(name, address, phone)
                    onClose()
                }
            },
            modifier = Modifier.testTag(BUSINESS_PROFILE_CONFIRM_BUTTON_TEST_TAG),
        ) {
            Text("Confirm")
        }
    }
}

const val BUSINESS_PROFILE_TITLE_TEST_TAG = "business-profile-title"
const val BUSINESS_PROFILE_CLOSE_BUTTON_TEST_TAG = "business-profile-close-button"
const val BUSINESS_PROFILE_NAME_FIELD_TEST_TAG = "business-profile-name-field"
const val BUSINESS_PROFILE_ADDRESS_FIELD_TEST_TAG = "business-profile-address-field"
const val BUSINESS_PROFILE_PHONE_FIELD_TEST_TAG = "business-profile-phone-field"
const val BUSINESS_PROFILE_CONFIRM_BUTTON_TEST_TAG = "business-profile-confirm-button"
