# License Enforcement Gate Specification

## Purpose

The startup gate that blocks access to the POS until the license status is usable. Implemented as a top-level boolean composable swap inside `MainActivity` — there is no navigation graph in this codebase, and this change does not introduce one. Functionally equivalent to the reference backend's `LicenseEnforcementFilter`, without an HTTP boundary.

## Requirements

### Requirement: Gate Is a Top-Level Composable Swap, Not Navigation

The system MUST decide, at `MainActivity` composition, between the licensed app entry composable and the activation composable using a plain boolean condition (e.g., `if (licensed) AppRoot() else ActivationScreen()`), and MUST NOT rely on a navigation graph, start destination, or back-stack semantics to enforce this gate.

#### Scenario: Licensed status renders the app entry composable
- GIVEN license status is `VALID` or `IN_GRACE_PERIOD`
- WHEN `MainActivity` composes its content
- THEN the app entry composable (`AppRoot()`, whatever it is at implementation time) is shown, not the activation screen

#### Scenario: Unlicensed status renders the activation composable
- GIVEN license status is `NOT_CONFIGURED`, `EXPIRED`, or `COMPROMISED`
- WHEN `MainActivity` composes its content
- THEN the activation composable is shown, and no path exists from it to the app entry composable other than a successful license install

### Requirement: Hard Block Outside VALID/GRACE

The system MUST treat any status other than `VALID` or `IN_GRACE_PERIOD` as fully blocking — the operator cannot reach any POS functionality (no read-only escape hatch).

#### Scenario: Expired-beyond-grace license blocks all POS access
- GIVEN license status is `EXPIRED`
- WHEN the app is launched
- THEN only the activation screen is reachable; no POS screen, product data, or order history is accessible

### Requirement: Grace Period Grants Full Access With a Warning

The system MUST grant full, unrestricted POS access while status is `IN_GRACE_PERIOD`, and MUST display a persistent warning banner distinguishing this state from `VALID`.

#### Scenario: In-grace license runs normally with a banner
- GIVEN license status is `IN_GRACE_PERIOD`
- WHEN the operator uses the POS
- THEN every POS feature functions normally
- AND a visible warning banner indicates the license is in its grace period

### Requirement: On-Device Data Survives Re-Blocking and Re-Activation

The system MUST preserve all on-device POS data (products, inventory, orders, cash sessions) while the gate is blocking, and MUST make that data fully accessible again immediately after a successful re-activation, without data loss or migration.

#### Scenario: Data is intact after the license transitions to EXPIRED and back
- GIVEN a store has existing products, inventory, and order history, and its license expires beyond grace
- WHEN the operator later installs a new valid license
- THEN all previously existing POS data is present and unchanged after the app unlocks

## Design-Level Open Questions (for sdd-design)

- The exact identity of "the app entry composable" (`AppRoot()`) is intentionally left to implementation time per the proposal; this spec only requires that whatever it is, the swap is boolean and gate-level, not a navigation transition.
