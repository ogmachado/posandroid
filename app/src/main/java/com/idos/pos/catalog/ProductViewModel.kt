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
 * **Pricing-callover PIN gating (`android-pos-role-permissions` design.md
 * Decisions G/H — supersedes the original task 4.6 price-only/edit-only
 * scope)**: [submitUpdate] and [createProduct] are the only two entry points
 * this ViewModel exposes for persisting a product's `price`/`costPrice`.
 * Neither has an unguarded path when [pricingRequiresCallover] reports a
 * change — both route the whole save through [PinGate.require] before
 * touching [CatalogRepository] at all. [submitUpdate] compares against
 * [original]'s current `price`/`costPrice`; [createProduct] compares against
 * a `ZERO` baseline (a brand-new product's implicit "before"), so a
 * non-zero-priced creation is gated exactly like a price/cost-price edit, and
 * a genuinely zero-priced creation is not. Non-pricing field edits (name,
 * category, unit, barcode) never require a PIN.
 */
class ProductViewModel(container: AppContainer) : ViewModel() {

    private val catalogRepository = container.catalogRepository

    /**
     * By-id lookup for the nav-shell's product-edit route (`nav/PosNavHost.kt`),
     * which only has a `productId` nav argument to resolve into a [ProductEntity]
     * before it can render [ProductFormScreen] in edit mode. Trivial delegate to
     * [CatalogRepository.findById] — added here (rather than calling the
     * repository directly from the nav composable) to keep the "UI never talks
     * to a repository directly" convention every other screen in this codebase
     * follows via [com.idos.pos.core.di.posViewModel].
     */
    suspend fun findById(id: Long): ProductEntity? = catalogRepository.findById(id)

    val products: StateFlow<List<ProductEntity>> = catalogRepository.findAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unitMeasures: StateFlow<List<UnitMeasureEntity>> = container.unitMeasureDao.findAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = container.categoryDao.findAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lastError = MutableStateFlow<DomainError?>(null)
    val lastError: StateFlow<DomainError?> = _lastError.asStateFlow()

    /**
     * One-shot-event counter (nav-shell addition — see `nav/PosNavHost.kt` and
     * [com.idos.pos.catalog.ProductFormScreen]'s `LaunchedEffect(saveCompleted)`).
     * Bumped ONLY on a successful (non-error) [createProduct]/[submitUpdate]
     * result — never on failure, and never merely on `pinGate.require` being
     * invoked. This is the single source of truth [ProductFormScreen] observes
     * to call `onSaved()`, covering BOTH the immediate ungated-save path and the
     * PIN-gated path, where the actual persistence only happens later, inside
     * [PinGate.submit]'s invocation of the pending action, after a correct PIN.
     * A plain `Int` (not `Boolean`) so each successive save — even to the same
     * screen instance — is a distinct, observable event for `LaunchedEffect`'s
     * key-based restart semantics.
     */
    private val _saveCompleted = MutableStateFlow(0)
    val saveCompleted: StateFlow<Int> = _saveCompleted.asStateFlow()

    /**
     * In-flight save guard (double-click regression fix). `createProduct`/
     * `submitUpdate` early-return as a no-op while a previous call's
     * coroutine hasn't finished yet — without this, a fast double-click on
     * Save could fire two concurrent `createProduct` calls with the same
     * `code`; [CatalogRepository.createProduct]'s uniqueness check is a
     * non-atomic check-then-insert (only backstopped by the DB's unique
     * index), so both calls can pass the pre-check and race to insert,
     * crashing with an uncaught `SQLiteConstraintException` on the loser.
     * [ProductFormScreen] also disables the Save button while this is
     * `true`, but the ViewModel-level guard is the actual fix — the UI
     * disable is defense in depth, not the mechanism relied on (a click that
     * lands in the single frame between "guard not yet true" and "button not
     * yet disabled" must still be a safe no-op).
     */
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    /**
     * Creates a new product. PIN-gated via [pricingRequiresCallover] against
     * a `ZERO` baseline (design.md Decision G/H) — a non-zero `price`/
     * `costPrice` at creation time requires the same any-ADMIN callover as an
     * edit; a genuinely zero-priced product is created directly.
     *
     * **In-flight guard placement (Invariant 2, mirrors [submitUpdate]'s
     * doc)**: [_isSaving] is set INSIDE [performCreate], never at the top of
     * this function — a dismissed PIN dialog never invokes the pending
     * action, so a guard set before the prompt would latch `_isSaving = true`
     * forever and permanently disable Save.
     */
    fun createProduct(
        pinGate: PinGate,
        name: String,
        code: String,
        barcode: String?,
        price: BigDecimal,
        costPrice: BigDecimal,
        unitMeasureId: Long,
        categoryId: Long?,
    ) {
        val performCreate: () -> Unit = {
            if (!_isSaving.value) {
                _isSaving.value = true
                viewModelScope.launch {
                    try {
                        val result = catalogRepository.createProduct(
                            name = name,
                            code = code,
                            barcode = barcode,
                            price = price,
                            costPrice = costPrice,
                            unitMeasureId = unitMeasureId,
                            categoryId = categoryId,
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

        if (pricingRequiresCallover(null, null, price, costPrice)) {
            pinGate.require(performCreate)
        } else {
            performCreate()
        }
    }

    /**
     * Updates an existing product. If `price` or `costPrice` differs from
     * [original]'s current values ([pricingRequiresCallover] — design.md
     * Decision G widens this from the original price-only scope), the update
     * is wrapped in [pinGate].require — no unguarded path to a pricing
     * change exists on this ViewModel.
     *
     * **The in-flight guard sits INSIDE `performUpdate`, not around this
     * whole function** — deliberately. `pinGate.require`/`pinGate.dismiss`
     * happen entirely on the UI side; if the guard were set the moment
     * [submitUpdate] is called (before a gated PIN prompt is even answered)
     * and the operator then CANCELS the dialog (`PinGate.dismiss`, which
     * never invokes `performUpdate`), nothing would ever clear the guard —
     * the Save button would be permanently disabled for the rest of that
     * screen instance's life. Guarding at `performUpdate` itself means
     * `_isSaving` is only ever set true right as real work starts (either
     * immediately for the ungated branch, or once via `PinGate.submit`
     * invoking the pending action after a correct PIN for the gated one),
     * so a dismissed/never-confirmed gate never sets it in the first place.
     * Calling `pinGate.require` again on a double-click while gated is
     * itself harmless (just re-arms the same dialog) — the actual crash
     * risk this fix closes is concurrent DB writes, which only `performUpdate`
     * can cause.
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
            if (!_isSaving.value) {
                _isSaving.value = true
                viewModelScope.launch {
                    try {
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

        if (pricingRequiresCallover(original.price, original.costPrice, price, costPrice)) {
            pinGate.require(performUpdate)
        } else {
            performUpdate()
        }
    }
}

/**
 * Shared pricing-callover predicate for both [ProductViewModel.createProduct]
 * and [ProductViewModel.submitUpdate] (design.md Decision G) — `true` when
 * `price` or `costPrice` differs from its "before" value. `originalPrice`/
 * `originalCostPrice` are `null` for a creation, in which case a `ZERO`
 * baseline is used, making a non-zero-priced creation a special case of the
 * exact same rule an edit follows (one predicate, one shared meaning of
 * "pricing changed") while leaving a genuinely zero-priced new product
 * ungated. [BigDecimal.compareTo] (not `equals`) is used deliberately —
 * `equals` also compares scale, so `100` and `100.00` would otherwise be
 * treated as a change.
 */
internal fun pricingRequiresCallover(
    originalPrice: BigDecimal?,
    originalCostPrice: BigDecimal?,
    price: BigDecimal,
    costPrice: BigDecimal,
): Boolean =
    price.compareTo(originalPrice ?: BigDecimal.ZERO) != 0 ||
        costPrice.compareTo(originalCostPrice ?: BigDecimal.ZERO) != 0
