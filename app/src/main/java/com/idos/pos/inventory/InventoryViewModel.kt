package com.idos.pos.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import com.idos.pos.permission.PinGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the inventory movement-entry screen (task 5.5). Constructed
 * via [com.idos.pos.core.di.posViewModel] with the sole [AppContainer]
 * constructor argument, matching [com.idos.pos.catalog.ProductViewModel].
 *
 * **ADJUST PIN gating**: [recordMovement] is the only entry point this
 * ViewModel exposes for recording a movement. When `type == MovementType.ADJUST`,
 * it routes the call through [PinGate.require] before the repository is ever
 * touched — mirroring [com.idos.pos.catalog.ProductViewModel.submitUpdate]
 * (task 4.6) exactly (see [InventoryRepository]'s class doc for why the gate
 * lives at this layer and not inside the repository). `IN`/`OUT` movements
 * are never gated.
 */
class InventoryViewModel(container: AppContainer) : ViewModel() {

    private val inventoryRepository = container.inventoryRepository

    val productStock: StateFlow<List<ProductStockView>> = inventoryRepository.productStockFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lastError = MutableStateFlow<DomainError?>(null)
    val lastError: StateFlow<DomainError?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    fun movementsForProduct(productId: Long): Flow<List<InventoryMovementEntity>> =
        inventoryRepository.movementsForProductFlow(productId)

    /**
     * Records an IN/OUT/ADJUST movement. ADJUST is routed through [pinGate];
     * IN/OUT run directly against [InventoryRepository.applyMovement] — there
     * is no other public method on this ViewModel that can record a movement,
     * so an ADJUST can never bypass the gate.
     */
    fun recordMovement(
        pinGate: PinGate,
        productId: Long,
        type: MovementType,
        quantity: Int,
        description: String?,
    ) {
        val perform: () -> Unit = {
            viewModelScope.launch {
                val result = inventoryRepository.applyMovement(
                    productId = productId,
                    type = type,
                    quantity = quantity,
                    description = description,
                )
                _lastError.value = result.domainErrorOrNull()
            }
        }

        if (type == MovementType.ADJUST) {
            pinGate.require(perform)
        } else {
            perform()
        }
    }

    /**
     * Sets minimum stock independent of any movement
     * (specs/inventory-ledger/spec.md "Minimum Stock Tracking"). Never
     * PIN-gated — it is not one of the two Slice-A sensitive actions
     * (design.md "PIN-gate mechanism": product price edit + ADJUST movements).
     */
    fun setMinimumStock(productId: Long, minimumStock: Int) {
        viewModelScope.launch {
            inventoryRepository.setMinimumStock(productId, minimumStock)
        }
    }
}
