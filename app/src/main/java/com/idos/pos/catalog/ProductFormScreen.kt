package com.idos.pos.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.idos.pos.permission.PinGate
import com.idos.pos.permission.PinGateDialog
import java.math.BigDecimal

/**
 * Create/edit form for a single product (task 4.5), with category/unit-of-measure
 * pickers. When [product] is `null` this is create mode (no PIN gate — see
 * [ProductViewModel] class doc); when non-null this is edit mode, and saving a
 * changed price routes through [pinGate] via [ProductViewModel.submitUpdate]
 * (task 4.6).
 *
 * **[initialBarcode] (task 6.3)**: when [product] is `null` (create mode),
 * [com.idos.pos.scan.BarcodeScanScreen]'s "unknown barcode" callback navigates
 * here passing the scanned code as [initialBarcode] so the operator doesn't
 * have to retype it. Ignored in edit mode — an existing product's own
 * [ProductEntity.barcode] always wins.
 *
 * Deliberately uses a plain [Column] rather than `Scaffold` — this screen has
 * no top bar/FAB/snackbar to justify it, and `Scaffold` composed alongside
 * [PinGateDialog] was found to make Robolectric's Compose idle-detection hang
 * indefinitely (`AppNotIdleException`) in this environment while writing the
 * task 4.6 UI test (see apply-progress notes). Confirmed independent of
 * scrolling — `Scaffold` + `AlertDialog` together is the trigger.
 */
@Composable
fun ProductFormScreen(
    product: ProductEntity?,
    pinGate: PinGate,
    onSaved: () -> Unit,
    initialBarcode: String? = null,
    viewModel: ProductViewModel = posViewModel(LocalAppContainer.current),
) {
    val unitMeasures by viewModel.unitMeasures.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val saveCompleted by viewModel.saveCompleted.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()

    // `onSaved()` fires exactly once per SUCCESSFUL save — covers both the
    // immediate ungated path (create, or edit with no price change) and the
    // PIN-gated path, where the actual persistence only happens later, inside
    // PinGate.submit's invocation of the pending action after a correct PIN.
    // See ProductViewModel.saveCompleted's doc for why a naive
    // "!pinGate.isVisible" check in the Save button's onClick is NOT enough —
    // it only ever fires onSaved() for the immediate path and never at all
    // once a gated save actually completes, leaving the operator stuck on
    // this screen with no way back after a correct PIN entry.
    LaunchedEffect(saveCompleted) {
        if (saveCompleted > 0) {
            onSaved()
        }
    }

    var name by remember(product) { mutableStateOf(product?.name.orEmpty()) }
    var code by remember(product) { mutableStateOf(product?.code.orEmpty()) }
    var barcode by remember(product) { mutableStateOf(product?.barcode ?: initialBarcode.orEmpty()) }
    var price by remember(product) { mutableStateOf(product?.price?.toPlainString().orEmpty()) }
    var costPrice by remember(product) { mutableStateOf(product?.costPrice?.toPlainString().orEmpty()) }
    var selectedUnitMeasureId by remember(product) { mutableStateOf(product?.unitMeasureId) }
    var selectedCategoryId by remember(product) { mutableStateOf(product?.categoryId) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().testTag(NAME_FIELD_TEST_TAG),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Code") },
                modifier = Modifier.fillMaxWidth().testTag(CODE_FIELD_TEST_TAG),
            )
            OutlinedTextField(
                value = barcode,
                onValueChange = { barcode = it },
                label = { Text("Barcode (optional)") },
                modifier = Modifier.fillMaxWidth().testTag(BARCODE_FIELD_TEST_TAG),
            )
            OutlinedTextField(
                value = price,
                onValueChange = { price = it },
                label = { Text("Price") },
                modifier = Modifier.fillMaxWidth().testTag(PRICE_FIELD_TEST_TAG),
            )
            OutlinedTextField(
                value = costPrice,
                onValueChange = { costPrice = it },
                label = { Text("Cost price") },
                modifier = Modifier.fillMaxWidth().testTag(COST_PRICE_FIELD_TEST_TAG),
            )

            PickerDropdown(
                label = "Unit of measure",
                options = unitMeasures,
                optionLabel = { it.name },
                optionId = { it.id },
                selectedId = selectedUnitMeasureId,
                onSelected = { selectedUnitMeasureId = it },
                testTag = UNIT_MEASURE_PICKER_TEST_TAG,
            )

            PickerDropdown(
                label = "Category (optional)",
                options = categories,
                optionLabel = { it.name },
                optionId = { it.id },
                selectedId = selectedCategoryId,
                onSelected = { selectedCategoryId = it },
                testTag = CATEGORY_PICKER_TEST_TAG,
            )

            if (lastError != null) {
                Text(
                    text = "Could not save: $lastError",
                    modifier = Modifier.testTag(FORM_ERROR_TEST_TAG),
                )
            }

            Button(
                modifier = Modifier.testTag(SAVE_BUTTON_TEST_TAG),
                // Defense in depth against a fast double-click — see
                // ProductViewModel.isSaving's doc for why the REAL fix is the
                // ViewModel-level guard, not this disable (a click landing in
                // the single frame before this recomposes must still be safe).
                enabled = !isSaving,
                onClick = {
                    val unitMeasureId = selectedUnitMeasureId ?: return@Button
                    val parsedPrice = price.toBigDecimalOrNull() ?: return@Button
                    val parsedCostPrice = costPrice.toBigDecimalOrNull() ?: return@Button
                    val barcodeOrNull = barcode.ifBlank { null }

                    if (product == null) {
                        viewModel.createProduct(
                            name = name,
                            code = code,
                            barcode = barcodeOrNull,
                            price = parsedPrice,
                            costPrice = parsedCostPrice,
                            unitMeasureId = unitMeasureId,
                            categoryId = selectedCategoryId,
                        )
                    } else {
                        // The ONLY call site that can persist a price change —
                        // ProductViewModel.submitUpdate internally decides
                        // whether pinGate.require is needed (task 4.6). There
                        // is no direct call to CatalogRepository from this
                        // screen, so a price change can never bypass the gate.
                        viewModel.submitUpdate(
                            pinGate = pinGate,
                            original = product,
                            name = name,
                            code = code,
                            barcode = barcodeOrNull,
                            price = parsedPrice,
                            costPrice = parsedCostPrice,
                            unitMeasureId = unitMeasureId,
                            categoryId = selectedCategoryId,
                        )
                    }
                    // onSaved() is NOT called here — see the
                    // LaunchedEffect(saveCompleted) above. Calling it
                    // unconditionally right here (an earlier revision of this
                    // nav-shell wiring did exactly that) is wrong two ways:
                    // it fires even when a gated save is only PENDING (never
                    // confirmed), tearing PinGateDialog down before the
                    // operator can answer it; and it never fires at all once
                    // a gated save actually DOES complete after a correct
                    // PIN, since nothing else calls onSaved() after that
                    // point — the operator would be stuck on this screen
                    // indefinitely with no way back.
                },
            ) {
                Text("Save")
            }
        }

    PinGateDialog(pinGate)
}

/**
 * Minimal dropdown picker built on the stable (non-experimental)
 * [DropdownMenu] — a read-only [OutlinedTextField] toggles a menu of
 * `options` on click. Deliberately avoids `ExposedDropdownMenuBox` (an
 * experimental Material3 API) to keep this composable dependency-light.
 */
@Composable
private fun <T> PickerDropdown(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    optionId: (T) -> Long,
    selectedId: Long?,
    onSelected: (Long) -> Unit,
    testTag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { optionId(it) == selectedId }?.let(optionLabel).orEmpty()

    Box(modifier = Modifier.testTag(testTag)) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(optionId(option))
                        expanded = false
                    },
                )
            }
        }
    }
}

const val NAME_FIELD_TEST_TAG = "product-form-name"
const val CODE_FIELD_TEST_TAG = "product-form-code"
const val BARCODE_FIELD_TEST_TAG = "product-form-barcode"
const val PRICE_FIELD_TEST_TAG = "product-form-price"
const val COST_PRICE_FIELD_TEST_TAG = "product-form-cost-price"
const val UNIT_MEASURE_PICKER_TEST_TAG = "product-form-unit-measure-picker"
const val CATEGORY_PICKER_TEST_TAG = "product-form-category-picker"
const val SAVE_BUTTON_TEST_TAG = "product-form-save-button"
const val FORM_ERROR_TEST_TAG = "product-form-error"

private fun String.toBigDecimalOrNull(): BigDecimal? = try {
    BigDecimal(this)
} catch (e: NumberFormatException) {
    null
}
