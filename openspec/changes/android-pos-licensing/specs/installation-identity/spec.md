# Installation Identity Specification

## Purpose

Derivation and persistence of a stable per-installation identifier used to bind a license to a specific device installation, and the check that a license's bound ID matches the current installation. Adapted from the reference backend's machine-fingerprint concept; Android has no server-observable hardware ID, so identity is a composite of a device-level ID plus a locally-generated, encrypted-at-rest random component.

## Requirements

### Requirement: Composite Installation-ID Derivation

The system MUST derive the installation ID as `sha256(ANDROID_ID + first-run random UUID)`, generating the UUID once on first app run and never regenerating it while the same app data persists.

#### Scenario: First run generates and persists the UUID
- GIVEN the app has never run on this device before
- WHEN the app starts for the first time
- THEN a new random UUID is generated, stored in EncryptedSharedPreferences, and combined with `ANDROID_ID` to produce the installation ID

#### Scenario: Subsequent runs reuse the same ID
- GIVEN the app has already generated its UUID on a previous run
- WHEN the app starts again without data loss
- THEN the same installation ID is produced as before

### Requirement: Installation-ID Is Displayed for Vendor Reporting

The system MUST display the current installation ID on the activation screen so the operator can report it to the vendor for license issuance.

#### Scenario: Activation screen shows the installation ID
- GIVEN the app is unlicensed
- WHEN the activation screen is shown
- THEN the current installation ID is visible in a form the operator can copy or read aloud

### Requirement: License Binding Check

The system MUST reject a license whose bound installation-ID claim does not match the current installation's derived ID, independent of signature and expiration validity.

#### Scenario: License bound to a different installation is rejected
- GIVEN a signed, product-valid, unexpired license bound to installation-ID `A`
- WHEN the current installation's derived ID is `B` (different from `A`)
- THEN the license MUST be rejected as not bound to this installation

### Requirement: Reinstall or Factory Reset Invalidates Prior Binding

The system MUST derive a new installation ID after any event that wipes the persisted UUID (app reinstall with `allowBackup=false`, factory reset, or manual data clear), and MUST NOT provide any automated mechanism to re-bind a previously issued license to the new ID.

#### Scenario: Reinstall produces a new ID and the old license no longer binds
- GIVEN a device was activated with installation ID `A` and a license bound to `A`
- WHEN the app is uninstalled and reinstalled (or the device is factory reset), producing new installation ID `B`
- THEN the previously installed license file, if reapplied, MUST be rejected by the binding check because it is bound to `A`, not `B`
- AND unlocking the app requires the vendor to manually issue a new license bound to `B` — there is no automated re-binding or grace path across the identity change

## Design-Level Open Questions (for sdd-design)

- `ANDROID_ID` can itself change across a factory reset on some OEM/Android-version combinations; this spec accepts that instability as a known tradeoff (mirrors the desktop `machine.id`-copy tradeoff) but does not prescribe a fallback source — sdd-design may document one without weakening the binding check.
