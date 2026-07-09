# License Verification Specification

## Purpose

On-device parsing and cryptographic verification of a signed license (JWS, RSA-SHA256) and evaluation of the resulting time-bound status. Ported from the reference backend's `LicenseValidatorV2`, adapted to a single offline installation with an Android-specific `product` claim gate and no `limits{}` enforcement.

## Requirements

### Requirement: JWS Signature Verification

The system MUST verify a license's RSA-SHA256 signature against a baked-in vendor public key (`res/raw`) using `java.security.Signature` and `org.json`, without any third-party JWT/JSON library.

#### Scenario: Valid signature parses successfully
- GIVEN a `.lic` file signed with the vendor's private key
- WHEN it is parsed and verified
- THEN the signature check passes and its claims (licenseId, product, issuedAt, expirationDate) are extracted

#### Scenario: Tampered payload fails verification
- GIVEN a `.lic` file whose payload was altered after signing
- WHEN it is verified
- THEN the signature check MUST fail and the license MUST be rejected before any claim is trusted

### Requirement: Product Claim Enforcement

The system MUST require the claim `product = "android-pos"` and MUST reject any license lacking this claim or carrying a different value (e.g., a desktop-issued `.lic`), independent of signature validity.

#### Scenario: Desktop-issued license is rejected
- GIVEN a validly-signed `.lic` with `product` absent or set to a non-Android value
- WHEN it is verified
- THEN the license MUST be rejected with a reason distinguishable from a signature failure

### Requirement: Status Evaluation From Expiration

The system MUST evaluate a signature-valid, product-valid license's status by comparing today's date (device local date) against `expirationDate` (date-only, no time component): `VALID` while today is on or before `expirationDate`, `IN_GRACE_PERIOD` for a configurable number of days after, `EXPIRED` beyond the grace window.

#### Scenario: License is valid on its expiration day
- GIVEN a license with `expirationDate` equal to today
- WHEN status is evaluated
- THEN the status is `VALID`

#### Scenario: License enters grace the day after expiration
- GIVEN a license whose `expirationDate` was yesterday
- WHEN status is evaluated
- THEN the status is `IN_GRACE_PERIOD`

#### Scenario: License expires beyond the grace window
- GIVEN a license whose `expirationDate` plus the grace window has passed
- WHEN status is evaluated
- THEN the status is `EXPIRED`

### Requirement: NOT_CONFIGURED When No License Exists

The system MUST report `NOT_CONFIGURED` when no license file has ever been installed on the device, distinct from an installed-but-invalid/expired license.

#### Scenario: Fresh install has no license
- GIVEN a freshly installed app that has never completed activation
- WHEN license status is queried
- THEN the status is `NOT_CONFIGURED`

### Requirement: `limits{}` Claim Is Ignored

The system MUST NOT enforce any `limits{}` claim (maxStores/maxUsers/maxProducts) present in a license payload; Android licensing is expiration-only.

#### Scenario: License carrying a legacy limits claim still verifies
- GIVEN a signed, product-valid license that includes a `limits{}` object
- WHEN it is verified
- THEN verification succeeds and the limits values have no effect on status or enforcement

## Design-Level Open Questions (for sdd-design)

- The exact grace-period length (days) is not fixed by this spec; sdd-design MUST choose and document a configurable value.
- `COMPROMISED` is set by `anti-tamper-heartbeat`, not by this domain's expiration logic; sdd-design must specify how the two are combined into the single status the enforcement gate reads.
