package com.idos.pos.cashsession

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
import java.math.BigDecimal

/**
 * Close-session screen (task 7.5; specs/cash-session/spec.md "Expected
 * Balance Formula on Close", "Session Close Is Terminal"). Operator enters
 * the physically counted amount; on success shows the snapshotted
 * `expectedBalance`/`difference` from [CashSessionViewModel.lastClosedSession].
 */
@Composable
fun CashSessionCloseScreen(
    onClosed: () -> Unit,
    viewModel: CashSessionViewModel = posViewModel(LocalAppContainer.current),
) {
    val lastError by viewModel.lastError.collectAsState()
    val lastClosed by viewModel.lastClosedSession.collectAsState()
    var counted by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Close cash session")

        OutlinedTextField(
            value = counted,
            onValueChange = { counted = it },
            label = { Text("Counted amount") },
            modifier = Modifier.fillMaxWidth().testTag(COUNTED_AMOUNT_FIELD_TEST_TAG),
        )

        if (lastError != null) {
            Text(
                text = "Could not close session: $lastError",
                modifier = Modifier.testTag(CLOSE_SESSION_ERROR_TEST_TAG),
            )
        }

        lastClosed?.let { closed ->
            Text(
                text = "Expected: ${closed.expectedBalance} — Difference: ${closed.difference}",
                modifier = Modifier.testTag(CLOSE_SESSION_RESULT_TEST_TAG),
            )
        }

        Button(
            modifier = Modifier.testTag(CLOSE_SESSION_BUTTON_TEST_TAG),
            onClick = {
                val parsed = counted.toBigDecimalOrNullSafe() ?: return@Button
                viewModel.closeSession(parsed)
                onClosed()
            },
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

const val COUNTED_AMOUNT_FIELD_TEST_TAG = "cash-session-counted-amount"
const val CLOSE_SESSION_ERROR_TEST_TAG = "cash-session-close-error"
const val CLOSE_SESSION_RESULT_TEST_TAG = "cash-session-close-result"
const val CLOSE_SESSION_BUTTON_TEST_TAG = "cash-session-close-button"
