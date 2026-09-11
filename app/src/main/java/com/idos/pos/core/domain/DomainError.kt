package com.idos.pos.core.domain

import com.idos.pos.licensing.LicenseStatus

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

    /**
     * **Added in Phase 3** (`licensing` — `LicenseRepository.install`):
     * signature/product/machine-binding verification failed for a candidate
     * license at install time (specs/license-activation/spec.md "A license
     * failing any check is not installed"). [status] carries the specific
     * [LicenseStatus] rejection reason
     * (INVALID_SIGNATURE/WRONG_PRODUCT/MACHINE_MISMATCH/MALFORMED/EXPIRED) so
     * a later UI layer can show an operator-actionable, distinguishable
     * message instead of one generic "install failed".
     */
    data class LicenseVerificationRejected(val status: LicenseStatus) : DomainError

    /**
     * **Added in Phase 3** (`licensing` — `LicenseRepository.install`): the
     * candidate's `issuedAt` was not strictly newer than the currently
     * installed license's `issuedAt` (specs/license-activation/spec.md
     * "Rollback/Replay Protection" — rejects `candidateIssuedAt <=
     * storedIssuedAt`; first activation with no stored license always
     * passes this guard).
     */
    data object LicenseRollbackRejected : DomainError

    /**
     * **Added in Phase 1 of `android-pos-auth`** (`permission` —
     * `AuthRepository.createUser`): a user-creation attempt named a
     * `username` that already exists (`user-identity` spec — unique per-user
     * identifier, enforced by `app_user`'s unique `username` index).
     */
    data class DuplicateUsername(val username: String) : DomainError

    /**
     * **Added in Phase 1 of `android-pos-auth`** (`permission` —
     * `AuthRepository.createUser`): a user-creation attempt supplied a blank
     * PIN. No PIN-length/character-set rule is specified beyond
     * non-blank (`user-identity` design-level open question).
     */
    data object BlankPin : DomainError

    /**
     * **Added in `android-pos-role-permissions` Phase 2** (`permission` —
     * `AuthRepository.createUser`/`changePin`, `BusinessProfileViewModel.save`):
     * the caller's session role failed the relevant `RolePermissions.kt`
     * capability predicate for the action attempted (`role-capability-model`
     * "Role-Gated Actions Are Enforced At The Action Layer, Not Only By UI
     * Reachability"). Payload-free — a central `Capability` enum is an
     * explicit non-goal, and a stringly-typed payload buys nothing on a path
     * an operator should never see (design.md Decision E).
     */
    data object NotPermitted : DomainError
}
