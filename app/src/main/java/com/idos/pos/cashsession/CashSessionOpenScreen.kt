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
 * Open-session screen (task 7.5) — shown when no cash session is open
 * (specs/cash-session/spec.md "Open the first session"). Deliberately a plain
 * [Column] rather than `Scaffold`, matching
 * [com.idos.pos.inventory.InventoryMovementFormScreen]'s Robolectric-Compose
 * idle-detection note.
 */
@Composable
fun CashSessionOpenScreen(
    onOpened: () -> Unit,
    viewModel: CashSessionViewModel = posViewModel(LocalAppContainer.current),
) {
    val lastError by viewModel.lastError.collectAsState()
    var openingBalance by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("No cash session is open")

        OutlinedTextField(
            value = openingBalance,
            onValueChange = { openingBalance = it },
            label = { Text("Opening balance") },
            modifier = Modifier.fillMaxWidth().testTag(OPENING_BALANCE_FIELD_TEST_TAG),
        )

        if (lastError != null) {
            Text(
                text = "Could not open session: $lastError",
                modifier = Modifier.testTag(OPEN_SESSION_ERROR_TEST_TAG),
            )
        }

        Button(
            modifier = Modifier.testTag(OPEN_SESSION_BUTTON_TEST_TAG),
            onClick = {
                val parsed = openingBalance.toBigDecimalOrNull() ?: return@Button
                viewModel.openSession(parsed)
                onOpened()
            },
        ) {
            Text("Open session")
        }
    }
}

private fun String.toBigDecimalOrNull(): BigDecimal? = try {
    BigDecimal(this)
} catch (e: NumberFormatException) {
    null
}

const val OPENING_BALANCE_FIELD_TEST_TAG = "cash-session-opening-balance"
const val OPEN_SESSION_BUTTON_TEST_TAG = "cash-session-open-button"
const val OPEN_SESSION_ERROR_TEST_TAG = "cash-session-open-error"
