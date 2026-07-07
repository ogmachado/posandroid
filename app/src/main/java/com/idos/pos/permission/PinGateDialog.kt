package com.idos.pos.permission

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * Dialog rendered by [PinGate] while [PinGate.isVisible] is true (task 3.3).
 * See [PinGate] doc for the no-session-carry-over invariant this dialog upholds
 * simply by being re-shown on every [PinGate.require] call.
 */
@Composable
fun PinGateDialog(gate: PinGate) {
    if (!gate.isVisible) return

    var pinInput by remember(gate.requestId) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = gate::dismiss,
        title = { Text("Manager PIN required") },
        text = {
            Column {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                    ),
                    modifier = Modifier.testTag(PIN_INPUT_TEST_TAG),
                )
                if (gate.lastError != null) {
                    Text("Incorrect PIN", modifier = Modifier.testTag(PIN_ERROR_TEST_TAG))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { gate.submit(pinInput) },
                modifier = Modifier.testTag(PIN_CONFIRM_TEST_TAG),
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = gate::dismiss) {
                Text("Cancel")
            }
        },
    )
}

const val PIN_INPUT_TEST_TAG = "pin-gate-input"
const val PIN_CONFIRM_TEST_TAG = "pin-gate-confirm"
const val PIN_ERROR_TEST_TAG = "pin-gate-error"
