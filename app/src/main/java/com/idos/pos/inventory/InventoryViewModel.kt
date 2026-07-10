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

    /**
     * One-shot-event counter (nav-shell addition — mirrors
     * [com.idos.pos.catalog.ProductViewModel.saveCompleted]'s rationale exactly).
     * Bumped ONLY on a successful (non-error) [recordMovement] result — never
     * on failure, and never merely on `pinGate.require` being invoked (the
     * ADJUST-gated case only actually persists later, inside [PinGate.submit]).
     * [InventoryMovementFormScreen] observes this to call `onSaved()`, covering
     * both the immediate IN/OUT path and the PIN-gated ADJUST path.
     */
    private val _saveCompleted = MutableStateFlow(0)
    val saveCompleted: StateFlow<Int> = _saveCompleted.asStateFlow()

    /**
     * In-flight save guard (double-click regression fix — mirrors
     * [com.idos.pos.catalog.ProductViewModel.isSaving] exactly, including WHY
     * it lives inside [recordMovement]'s `perform` lambda rather than around
     * the whole function: setting it before `pinGate.require` would leave it
     * stuck `true` forever if the operator cancels the PIN dialog, since
     * `PinGate.dismiss` never invokes the pending action). Without this, a
     * fast double-click on Save/Record could fire two concurrent
     * [InventoryRepository.applyMovement] calls for the same product.
     */
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    fun movementsForProduct(productId: Long): Flow<List<InventoryMovementEntity>> =
        inventoryRepository.movementsForProductFlow(productId)

    /**
     * By-id lookup for the nav-shell's movement-form route (`nav/PosNavHost.kt`)
     * — trivial delegate to [InventoryRepository.findProductStockView], mirroring
     * [com.idos.pos.catalog.ProductViewModel.findById]'s rationale.
     */
    suspend fun findProductStockView(productId: Long): ProductStockView? =
        inventoryRepository.findProductStockView(productId)

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
            if (!_isSaving.value) {
                _isSaving.value = true
                viewModelScope.launch {
                    try {
                        val result = inventoryRepository.applyMovement(
                            productId = productId,
                            type = type,
                            quantity = quantity,
                            description = description,
                        )
                        val error = result.domainErrorOrNull()
                        _lastError.value = error
                        if (error == null) {
                            _saveCompleted.value += 1
                        }
                    } finally {
                        _isSaving.value = false
                    }
                }
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
