package com.idos.pos.cashsession

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel
import java.math.BigDecimal

/**
 * Main open-session screen (task 7.5): shows the LIVE `expectedBalance`
 * (recomputed on every movement per [CashSessionRepository.currentSessionFlow],
 * task 7.4) plus a DEPOSIT/WITHDRAWAL entry form and a "Close session" action.
 * Renders nothing meaningful ([session] is null) once the caller should be
 * routing to [CashSessionOpenScreen] instead — that routing decision belongs
 * to the caller (no navigation graph exists yet in Slice A, matching every
 * other screen's note).
 */
@Composable
fun CashSessionScreen(
    onCloseRequested: () -> Unit,
    viewModel: CashSessionViewModel = posViewModel(LocalAppContainer.current),
) {
    val session by viewModel.currentSession.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    var selectedType by remember { mutableStateOf(CashMovementType.DEPOSIT) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        val current = session
        if (current == null) {
            Text("No open session", modifier = Modifier.testTag(NO_SESSION_TEST_TAG))
            return@Column
        }

        Text(
            text = "Expected balance: ${current.expectedBalance}",
            modifier = Modifier.testTag(EXPECTED_BALANCE_TEST_TAG),
        )
        Text("Opening balance: ${current.openingBalance}")

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        CashMovementType.entries.forEach { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedType = type }
                    .testTag(movementTypeOptionTestTag(type)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedType == type, onClick = { selectedType = type })
                Text(type.name)
            }
        }

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount") },
            modifier = Modifier.fillMaxWidth().testTag(MOVEMENT_AMOUNT_FIELD_TEST_TAG),
        )
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            label = { Text("Reason (optional)") },
            modifier = Modifier.fillMaxWidth().testTag(MOVEMENT_REASON_FIELD_TEST_TAG),
        )

        if (lastError != null) {
            Text(
                text = "Could not record movement: $lastError",
                modifier = Modifier.testTag(MOVEMENT_ERROR_TEST_TAG),
            )
        }

        Button(
            modifier = Modifier.testTag(RECORD_MOVEMENT_BUTTON_TEST_TAG),
            onClick = {
                val parsedAmount = amount.toBigDecimalOrNullSafe() ?: return@Button
                viewModel.recordMovement(selectedType, parsedAmount, reason.ifBlank { null })
                amount = ""
                reason = ""
            },
        ) {
            Text("Record movement")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Button(
            modifier = Modifier.testTag(GO_TO_CLOSE_BUTTON_TEST_TAG),
            onClick = onCloseRequested,
        ) {
            Text("Close session")
        }
    }
}

private fun String.toBigDecimalOrNullSafe(): BigDecimal? = try {
    BigDecimal(this)
} catch (e: NumberFormatException) {
    null
}

const val NO_SESSION_TEST_TAG = "cash-session-none-open"
const val EXPECTED_BALANCE_TEST_TAG = "cash-session-expected-balance"
const val MOVEMENT_AMOUNT_FIELD_TEST_TAG = "cash-session-movement-amount"
const val MOVEMENT_REASON_FIELD_TEST_TAG = "cash-session-movement-reason"
const val MOVEMENT_ERROR_TEST_TAG = "cash-session-movement-error"
const val RECORD_MOVEMENT_BUTTON_TEST_TAG = "cash-session-movement-submit"
const val GO_TO_CLOSE_BUTTON_TEST_TAG = "cash-session-go-to-close"

fun movementTypeOptionTestTag(type: CashMovementType) = "cash-session-movement-type-${type.name}"
