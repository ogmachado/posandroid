package com.idos.pos.cashsession

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the cash-session open/movement/close screens (task 7.5).
 * Constructed via [com.idos.pos.core.di.posViewModel] with the sole
 * [AppContainer] constructor argument, matching
 * [com.idos.pos.inventory.InventoryViewModel]. Unlike inventory's ADJUST path,
 * none of the cash-session actions are PIN-gated (design.md "PIN-gate
 * mechanism" lists only product-price-edit and ADJUST movements as the two
 * Slice-A sensitive actions).
 */
class CashSessionViewModel(container: AppContainer) : ViewModel() {

    private val cashSessionRepository = container.cashSessionRepository

    val currentSession: StateFlow<CashSessionView?> = cashSessionRepository.currentSessionFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _lastError = MutableStateFlow<DomainError?>(null)
    val lastError: StateFlow<DomainError?> = _lastError.asStateFlow()

    private val _lastClosedSession = MutableStateFlow<CashSessionView?>(null)
    val lastClosedSession: StateFlow<CashSessionView?> = _lastClosedSession.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    fun openSession(openingBalance: BigDecimal) {
        viewModelScope.launch {
            val result = cashSessionRepository.open(openingBalance)
            _lastError.value = result.domainErrorOrNull()
        }
    }

    fun recordMovement(type: CashMovementType, amount: BigDecimal, reason: String?) {
        viewModelScope.launch {
            val result = cashSessionRepository.addMovement(type, amount, reason)
            _lastError.value = result.domainErrorOrNull()
        }
    }

    fun closeSession(counted: BigDecimal) {
        viewModelScope.launch {
            val result = cashSessionRepository.close(counted)
            _lastError.value = result.domainErrorOrNull()
            result.getOrNull()?.let { _lastClosedSession.value = it }
        }
    }
}
