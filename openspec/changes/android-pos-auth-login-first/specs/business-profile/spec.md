# Business Profile Specification (Amended by `android-pos-auth-login-first`)

## Amendment Note

This is a **delta/amendment** to `openspec/changes/android-pos-auth/specs/business-profile/spec.md`. It reverses "captured as part of first-run onboarding, not separately reachable" to "optional, empty by default, editable at any time via an ADMIN-only in-app action." The single-row and readable-after-save requirements are **retained** — `BusinessProfileRepository.save()`'s upsert behavior is untouched by this proposal; only the surrounding capture-flow language changes.

## Purpose

Originally: *"A single-row business profile ... captured once during first-run onboarding."* **The "captured during onboarding" part is reversed.** The profile remains a single row per device (unchanged), but it is now an ordinary, optional, always-editable ADMIN action reachable via a second ADMIN-only header button in `AppRoot()` (next to "Manage users") — not part of any first-run or blocking flow.

## MODIFIED Requirements

### Requirement: The Profile Is Optional And Editable At Any Time Via An ADMIN-Only Action

The system MUST make business-profile setup reachable at any time, after login, via an ADMIN-only header action in `AppRoot()` — not gated behind, or bundled with, any first-run flow. The profile MUST be optional: an ADMIN MAY operate the full POS without ever setting it, with no blocking gate, nag, or banner enforcing capture. Fields default to empty/nullable until an ADMIN explicitly saves values.
(Previously: "The Profile Is Captured As Part Of First-Run Onboarding" — required capture within the same blocking flow that seeded the default ADMIN, before the login gate was reachable.)

#### Scenario: An ADMIN opens business-profile setup from the header action

- GIVEN a logged-in ADMIN viewing the POS shell
- WHEN they select the business-profile header action (next to "Manage users")
- THEN the business-profile screen opens, pre-loaded with any existing saved values

#### Scenario: An ADMIN edits and saves the business profile after login

- GIVEN a logged-in ADMIN on the business-profile screen
- WHEN they change the name, address, or phone and save
- THEN the single business-profile row is updated with the new values (via the existing upsert-on-save behavior)

#### Scenario: The POS is fully usable with no business profile ever set

- GIVEN a logged-in ADMIN and no business-profile row has ever been saved
- WHEN the ADMIN uses products, inventory, sales, or cash-session screens
- THEN none of them are blocked, nagged, or banner-interrupted by the absence of a business profile

#### Scenario: A CASHIER cannot reach business-profile setup

- GIVEN a logged-in CASHIER
- WHEN the POS shell header is composed
- THEN no business-profile action is shown or reachable to that session

### Requirement: The Captured Profile Is Readable After Save

The system MUST make the business profile (name, address, phone) readable after it has been saved.
(Previously: "The Captured Profile Is Readable After Capture" — worded around one-shot onboarding capture; behavior — reading the persisted row — is unchanged.)

#### Scenario: The business profile can be read after being saved

- GIVEN a business-profile row has been saved by an ADMIN
- WHEN the profile is queried
- THEN its name, address, and phone are returned as saved

## Unchanged (carried forward from the base spec, not restated here)

- "Exactly One Business Profile Row Exists" — single-row/no-duplicate-row behavior is untouched.
- "No Multi-Store / No `storeId` Reintroduction" — untouched.
