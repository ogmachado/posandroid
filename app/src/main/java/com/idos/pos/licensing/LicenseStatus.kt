package com.idos.pos.licensing

/**
 * Exhaustive license status, evaluated by [LicenseRepository] (Phase 3) from
 * [LicenseVerifier.verify]'s result plus stored-license/compromised state.
 * `licensed = status in setOf(VALID, IN_GRACE_PERIOD)` — the enforcement gate
 * (Phase 5) reads only that boolean derivation, never a single variant.
 *
 * Ported from the reference backend's desktop status shape
 * (`LicenseValidatorV2`/`LicenseEnforcer`) with 4 Android-specific additions
 * that distinguish *why* [LicenseVerifier.verify] rejected a `.lic` — the
 * desktop side collapses all of these into one generic invalid-signature
 * rejection, but Android's activation screen (Phase 4) needs a distinguishable
 * reason per specs/license-verification/spec.md ("Desktop-issued license is
 * rejected ... with a reason distinguishable from a signature failure").
 */
enum class LicenseStatus {
    /** Signature, product, and machine binding all valid; today is on or before `exp`. */
    VALID,

    /** Valid otherwise, but `exp` has passed within the configured grace window. */
    IN_GRACE_PERIOD,

    /** `exp` plus the grace window has passed. */
    EXPIRED,

    /** Clock-rollback detected by [ClockRollbackDetector] (Phase 2) — sticky, overrides expiry. */
    COMPROMISED,

    /** No license has ever been installed on this device. */
    NOT_CONFIGURED,

    /** JWS signature does not verify against the vendor public key. */
    INVALID_SIGNATURE,

    /** `iss` or `product` claim does not match this Android build (e.g. a desktop `.lic`). */
    WRONG_PRODUCT,

    /** `machineId` claim does not match this device's [InstallationId]. */
    MACHINE_MISMATCH,

    /** Structurally invalid JWS: wrong part count, undecodable base64url, invalid JSON, or unsupported `alg`. */
    MALFORMED
}

/**
 * The single derived boolean the enforcement gate (Phase 5, `MainActivity`)
 * reads — specs/license-enforcement-gate/spec.md "Hard Block Outside
 * VALID/GRACE". Callers MUST branch on this property, never inline the
 * `setOf(VALID, IN_GRACE_PERIOD)` check themselves, so the two licensed
 * variants stay defined in exactly one place.
 */
val LicenseStatus.licensed: Boolean
    get() = this == LicenseStatus.VALID || this == LicenseStatus.IN_GRACE_PERIOD
