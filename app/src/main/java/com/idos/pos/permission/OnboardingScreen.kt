package com.idos.pos.permission

import androidx.compose.foundation.layout.Column
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
 * First-run onboarding (`first-run-onboarding` / `business-profile` specs) —
 * shown by [AuthGate] while no business profile has been captured yet.
 * Idempotently seeds the default ADMIN on first composition (`admin`/`admin123`)
 * and captures the single business profile (name/address/phone); confirming
 * awaits [OnboardingViewModel.saveBusinessProfile] and then calls [onComplete]
 * so [AuthGate] re-checks business-profile existence and advances to the
 * login gate (design.md "Onboarding → OnboardingScreen(onComplete = refresh)").
 *
 * The confirm action is driven directly from a `rememberCoroutineScope`
 * launch (the same idiom [rememberPinGate] already uses for a suspend
 * verifier), rather than bridging through a `StateFlow`/`collectAsState`
 * one-shot-event signal — simpler, and calls `onComplete` deterministically
 * right after the save resolves.
 *
 * Deliberately a plain [Column], matching this codebase's established
 * `Scaffold`-avoidance convention (see [com.idos.pos.licensing.ActivationScreen]'s
 * class doc for the underlying Robolectric-Compose reason).
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit = {},
    viewModel: OnboardingViewModel = posViewModel(LocalAppContainer.current),
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.ensureDefaultAdminSeeded()
    }

    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Set up your business")

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Business name") },
            modifier = Modifier.fillMaxWidth().testTag(ONBOARDING_NAME_FIELD_TEST_TAG),
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Address") },
            modifier = Modifier.fillMaxWidth().testTag(ONBOARDING_ADDRESS_FIELD_TEST_TAG),
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone") },
            modifier = Modifier.fillMaxWidth().testTag(ONBOARDING_PHONE_FIELD_TEST_TAG),
        )

        Button(
            onClick = {
                scope.launch {
                    viewModel.saveBusinessProfile(name, address, phone)
                    onComplete()
                }
            },
            modifier = Modifier.testTag(ONBOARDING_CONFIRM_BUTTON_TEST_TAG),
        ) {
            Text("Confirm")
        }
    }
}

const val ONBOARDING_NAME_FIELD_TEST_TAG = "onboarding-name-field"
const val ONBOARDING_ADDRESS_FIELD_TEST_TAG = "onboarding-address-field"
const val ONBOARDING_PHONE_FIELD_TEST_TAG = "onboarding-phone-field"
const val ONBOARDING_CONFIRM_BUTTON_TEST_TAG = "onboarding-confirm-button"
