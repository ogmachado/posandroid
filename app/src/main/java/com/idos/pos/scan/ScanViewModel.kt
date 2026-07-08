package com.idos.pos.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.core.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the barcode-scan flow (task 6.3). Exposed by [ScanViewModel]
 * and consumed by [BarcodeScanScreen].
 */
sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Found(val product: ProductEntity) : ScanUiState
    data class NotFound(val barcode: String) : ScanUiState
}

/**
 * Owns the decode → [com.idos.pos.catalog.CatalogRepository.findByBarcode]
 * call and exposes the result as [uiState] (task 6.3). Constructed via
 * [com.idos.pos.core.di.posViewModel] with the sole [AppContainer]
 * constructor argument, same convention as [com.idos.pos.catalog.ProductViewModel].
 *
 * **Cart-wiring ordering deviation (tasks.md 6.3)**: tasks.md's literal
 * wording is "wire decode → CatalogRepository.findByBarcode → cart-add hit /
 * unknown-barcode create-product prompt on miss", but `sales/CartViewModel`
 * does not exist yet — it lands in Phase 8 (PR7), which comes after this PR
 * (PR5). This class therefore has NO dependency on any sales/cart type: on a
 * hit it only exposes [ScanUiState.Found] with the resolved [ProductEntity];
 * the caller ([BarcodeScanScreen]'s `onProductFound` callback) is where a
 * future Phase 8 call site plugs in "add to cart" — see [BarcodeScanScreen]'s
 * class doc for the exact wiring point. Same deferred-TODO pattern
 * [com.idos.pos.catalog.CatalogRepository]'s class doc used for its Phase 4 →
 * Phase 5 inventory-seed TODO.
 *
 * The "unknown barcode → offer create product" path on a miss ([ScanUiState.NotFound])
 * IS real right now (no forward TODO needed) — `catalog/ProductFormScreen.kt`
 * already exists (Phase 4) and [BarcodeScanScreen]'s `onUnknownBarcode`
 * callback is wired to navigate there with the scanned code pre-filled.
 */
class ScanViewModel(container: AppContainer) : ViewModel() {

    private val catalogRepository = container.catalogRepository

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    /** Called by [BarcodeAnalyzer] (via [BarcodeScanScreen]) on every debounced decode. */
    fun onBarcodeDecoded(barcode: String) {
        viewModelScope.launch {
            val product = catalogRepository.findByBarcode(barcode)
            _uiState.value = if (product != null) {
                ScanUiState.Found(product)
            } else {
                ScanUiState.NotFound(barcode)
            }
        }
    }

    /**
     * Returns to [ScanUiState.Idle] so the screen is ready to resolve the
     * next scan. Called by [BarcodeScanScreen] right after it has acted on a
     * [ScanUiState.Found]/[ScanUiState.NotFound] result (invoked the
     * corresponding callback) — otherwise the screen would never leave that
     * terminal state for a second scan.
     */
    fun resetToIdle() {
        _uiState.value = ScanUiState.Idle
    }
}
