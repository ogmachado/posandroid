# License Activation Specification

## Purpose

The operator-facing flow to import a vendor-issued license file (or pasted text), preview its parsed claims and verification outcome before committing, and install it. Adapted from the reference backend's preview/install split (`/license/preview`, `/license/install`), replacing the HTTP boundary with local file/clipboard input.

## Requirements

### Requirement: Preview Before Install

The system MUST allow the operator to preview a candidate license (via file import or pasted text) — showing its parsed claims and verification result — without persisting it as the active license.

#### Scenario: Preview shows claims without installing
- GIVEN the operator imports a license file
- WHEN the preview step parses and verifies it
- THEN the parsed claims and pass/fail verification result are shown
- AND no license state is persisted as a result of previewing alone

### Requirement: Install Persists a Verified License

The system MUST persist a license as the active one only after it passes signature verification, product-claim enforcement, and installation-ID binding, all evaluated together at install time.

#### Scenario: A fully valid license installs successfully
- GIVEN a candidate license passes signature, product, and binding checks and is unexpired
- WHEN the operator confirms install
- THEN the license is persisted as the active license and the app becomes usable

#### Scenario: A license failing any check is not installed
- GIVEN a candidate license fails signature, product, or binding verification
- WHEN the operator attempts to install it
- THEN installation MUST be rejected and no change is made to the currently active license (if any)

### Requirement: Rollback/Replay Protection

The system MUST reject installing a candidate license whose `issuedAt` is older than or equal to the `issuedAt` of the currently active license, even if the candidate otherwise verifies successfully.

#### Scenario: Replaying an older license is rejected
- GIVEN the currently active license has `issuedAt = 2026-06-01`
- WHEN the operator attempts to install a different, validly-signed license with `issuedAt = 2026-05-01`
- THEN installation MUST be rejected as a rollback/replay attempt

#### Scenario: A newer reissued license installs over an older one
- GIVEN the currently active license has `issuedAt = 2026-06-01`
- WHEN the operator installs a validly-signed, correctly-bound license with `issuedAt = 2026-07-01`
- THEN the new license replaces the active one

### Requirement: Both File Import and Paste Are Supported

The system MUST accept a candidate license through either a file picker (importing a `.lic` file) or a pasted-text field carrying the raw JWS string, treating both as equivalent input to preview/install.

#### Scenario: Pasted text produces the same outcome as file import
- GIVEN the same license content, once supplied as a file and once pasted as text
- WHEN each is previewed
- THEN both produce the same parsed claims and verification result

## Design-Level Open Questions (for sdd-design)

- Exact on-screen error messaging per rejection reason (signature/product/binding/replay/expired) is not fixed here — sdd-design should define distinguishable, operator-actionable messages, since the operator cannot self-diagnose a cryptographic failure.
