package com.idos.pos.inventory

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
import com.idos.pos.permission.PinGate
import com.idos.pos.permission.PinGateDialog

/**
 * Movement-entry form for a single product (task 5.5): IN/OUT/ADJUST radio
 * choice + quantity + optional description, plus an independent
 * minimum-stock field with its own save action (spec "Minimum Stock
 * Tracking" — settable without a movement).
 *
 * Deliberately a plain [Column] rather than `Scaffold` — matches
 * [com.idos.pos.catalog.ProductFormScreen]'s note: `Scaffold` composed
 * alongside [PinGateDialog] was found to make Robolectric's Compose
 * idle-detection hang indefinitely (`AppNotIdleException`) in this
 * environment.
 */
@Composable
fun InventoryMovementFormScreen(
    product: ProductStockView,
    pinGate: PinGate,
    onSaved: () -> Unit,
    viewModel: InventoryViewModel = posViewModel(LocalAppContainer.current),
) {
    val lastError by viewModel.lastError.collectAsState()

    var selectedType by remember(product) { mutableStateOf(MovementType.IN) }
    var quantity by remember(product) { mutableStateOf("") }
    var description by remember(product) { mutableStateOf("") }
    var minimumStock by remember(product) { mutableStateOf(product.minimumStock.toString()) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("${product.productName} — current stock: ${product.stock}")

        MovementType.entries.forEach { type ->
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
            value = quantity,
            onValueChange = { quantity = it },
            label = { Text("Quantity") },
            modifier = Modifier.fillMaxWidth().testTag(QUANTITY_FIELD_TEST_TAG),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description (optional)") },
            modifier = Modifier.fillMaxWidth().testTag(DESCRIPTION_FIELD_TEST_TAG),
        )

        if (lastError != null) {
            Text(
                text = "Could not record movement: $lastError",
                modifier = Modifier.testTag(MOVEMENT_ERROR_TEST_TAG),
            )
        }

        Button(
            modifier = Modifier.testTag(SUBMIT_MOVEMENT_BUTTON_TEST_TAG),
            onClick = {
                val parsedQuantity = quantity.toIntOrNull() ?: return@Button
                viewModel.recordMovement(
                    pinGate = pinGate,
                    productId = product.productId,
                    type = selectedType,
                    quantity = parsedQuantity,
                    description = description.ifBlank { null },
                )
                onSaved()
            },
        ) {
            Text("Record movement")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // Minimum stock is editable independent of any movement (spec
        // "Minimum Stock Tracking") — its own field + save action, never
        // routed through a movement type or the PIN gate.
        OutlinedTextField(
            value = minimumStock,
            onValueChange = { minimumStock = it },
            label = { Text("Minimum stock") },
            modifier = Modifier.fillMaxWidth().testTag(MINIMUM_STOCK_FIELD_TEST_TAG),
        )
        Button(
            modifier = Modifier.testTag(SAVE_MINIMUM_STOCK_BUTTON_TEST_TAG),
            onClick = {
                val parsedMinimum = minimumStock.toIntOrNull() ?: return@Button
                viewModel.setMinimumStock(product.productId, parsedMinimum)
            },
        ) {
            Text("Save minimum stock")
        }
    }

    PinGateDialog(pinGate)
}

const val QUANTITY_FIELD_TEST_TAG = "inventory-movement-quantity"
const val DESCRIPTION_FIELD_TEST_TAG = "inventory-movement-description"
const val MOVEMENT_ERROR_TEST_TAG = "inventory-movement-error"
const val SUBMIT_MOVEMENT_BUTTON_TEST_TAG = "inventory-movement-submit"
const val MINIMUM_STOCK_FIELD_TEST_TAG = "inventory-minimum-stock"
const val SAVE_MINIMUM_STOCK_BUTTON_TEST_TAG = "inventory-save-minimum-stock"

fun movementTypeOptionTestTag(type: MovementType) = "inventory-movement-type-${type.name}"
