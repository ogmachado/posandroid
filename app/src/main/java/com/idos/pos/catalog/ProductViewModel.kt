package com.idos.pos.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import com.idos.pos.permission.PinGate
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the product catalog list/form (task 4.5). Constructed via
 * [com.idos.pos.core.di.posViewModel] with the sole [AppContainer] constructor
 * argument (see [com.idos.pos.core.di.Locals]).
 *
 * **Price-edit PIN gating (task 4.6)**: [submitUpdate] is the ONLY entry point
 * this ViewModel exposes for changing an existing product's fields. There is
 * no other public method that can persist a price change — when the submitted
 * `price` differs from [original]'s current price, [submitUpdate] internally
 * routes the whole update through [PinGate.require] before calling
 * [CatalogRepository.updateProduct] at all (specs/permission-gate/spec.md "PIN
 * Gates Product Price Edits" / "Price edit attempted without entering a PIN").
 * Non-price field edits (name, category, unit, barcode) do not require a PIN.
 * [createProduct] is never gated — the spec only covers *editing* an existing
 * product's price, not setting the initial price at creation time.
 */
class ProductViewModel(container: AppContainer) : ViewModel() {

    private val catalogRepository = container.catalogRepository

    val products: StateFlow<List<ProductEntity>> = catalogRepository.findAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unitMeasures: StateFlow<List<UnitMeasureEntity>> = container.unitMeasureDao.findAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = container.categoryDao.findAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lastError = MutableStateFlow<DomainError?>(null)
    val lastError: StateFlow<DomainError?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    /** Creates a new product. Never PIN-gated — see class doc. */
    fun createProduct(
        name: String,
        code: String,
        barcode: String?,
        price: BigDecimal,
        costPrice: BigDecimal,
        unitMeasureId: Long,
        categoryId: Long?,
    ) {
        viewModelScope.launch {
            val result = catalogRepository.createProduct(
                name = name,
                code = code,
                barcode = barcode,
                price = price,
                costPrice = costPrice,
                unitMeasureId = unitMeasureId,
                categoryId = categoryId,
            )
            _lastError.value = result.domainErrorOrNull()
        }
    }

    /**
     * Updates an existing product. If `price` differs from [original]'s
     * current price, the update is wrapped in [pinGate].require — no
     * unguarded path to a price change exists on this ViewModel (task 4.6).
     */
    fun submitUpdate(
        pinGate: PinGate,
        original: ProductEntity,
        name: String,
        code: String,
        barcode: String?,
        price: BigDecimal,
        costPrice: BigDecimal,
        unitMeasureId: Long,
        categoryId: Long?,
    ) {
        val performUpdate: () -> Unit = {
            viewModelScope.launch {
                val result = catalogRepository.updateProduct(
                    id = original.id,
                    name = name,
                    code = code,
                    barcode = barcode,
                    price = price,
                    costPrice = costPrice,
                    unitMeasureId = unitMeasureId,
                    categoryId = categoryId,
                )
                _lastError.value = result.domainErrorOrNull()
            }
        }

        val priceChanged = price.compareTo(original.price) != 0
        if (priceChanged) {
            pinGate.require(performUpdate)
        } else {
            performUpdate()
        }
    }
}
