package com.idos.pos.core.domain

/**
 * Exhaustive set of business-rule failures surfaced by repositories/use-cases.
 *
 * Repository/use-case methods return `Result<T>` wrapping one of these on failure
 * (see design.md "Interfaces / Contracts"). There is no HTTP boundary here, so this
 * is the idiomatic Kotlin equivalent of the reference backend's exception hierarchy:
 * ViewModels `when`-match exhaustively over [DomainError] to map failures to UI state.
 *
 * [PinIncorrect] alone covers the PIN failure surface. The gate *requests* the PIN by
 * showing its dialog — there is no separate "PIN required" variant.
 */
sealed interface DomainError {
    data class InsufficientStock(val productId: Long, val available: Int, val requested: Int) : DomainError
    data class DuplicateCode(val code: String) : DomainError
    data class DuplicateBarcode(val barcode: String) : DomainError

    /**
     * **Added in Phase 5** (`inventory` — `InventoryDao.applyMovementAtomic`), the
     * same kind of DomainError-contract gap [DuplicateBarcode] closed in Phase 4:
     * the originally locked contract had no variant for "an ADJUST movement was
     * requested with a negative absolute quantity"
     * (specs/inventory-ledger/spec.md "Adjust with a negative quantity"). Reusing
     * an unrelated variant would have produced a misleading UI message, so this
     * 9th variant was added instead.
     */
    data class InvalidMovementQuantity(val quantity: Int) : DomainError
    data class PaymentMethodNotFound(val id: Long) : DomainError
    data class UnitMeasureNotFound(val id: Long) : DomainError
    data object NoOpenSession : DomainError
    data object SessionAlreadyOpen : DomainError
    data object PinIncorrect : DomainError
}
