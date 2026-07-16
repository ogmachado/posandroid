# Business Profile Specification

## Amendment History

**Amendment (2026-07-16, `android-pos-auth-login-first`)**: Reversed the onboarding capture requirement. The profile is no longer captured as part of first-run onboarding; instead, it is optional and editable at any time via an ADMIN-only header action (post-login). Single-row and readable-after-save behaviors are retained.

## Purpose

A single-row business profile (name, address, phone) that is optional and editable at any time via an ADMIN-only header action. This is explicitly **not** the multi-tenant `STORE` concept `android-pos-mvp` removed — there is exactly one business profile per device, with no re-creatable "store" entity and no `storeId` foreign key on any domain entity.

## Requirements

### Requirement: Exactly One Business Profile Row Exists

The system MUST persist exactly one business-profile row per device, holding name, address, and phone.

#### Scenario: Exactly one profile row persists
- GIVEN the business-profile repository
- WHEN the app has been in use
- THEN exactly zero or one business-profile row exists at any time (empty/nullable is valid until an ADMIN explicitly saves)

#### Scenario: No second profile row can be created
- GIVEN a business-profile row already exists
- WHEN any code path attempts to create another business-profile row
- THEN no second row is created — the profile remains single-row

### Requirement: The Profile Is Optional And Editable At Any Time Via An ADMIN-Only Action

The system MUST make business-profile setup reachable at any time, after login, via an ADMIN-only header action in `AppRoot()` — not gated behind, or bundled with, any first-run flow. The profile MUST be optional: an ADMIN MAY operate the full POS without ever setting it, with no blocking gate, nag, or banner enforcing capture. Fields default to empty/nullable until an ADMIN explicitly saves values.

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

#### Scenario: The business profile can be read after being saved
- GIVEN a business-profile row has been saved by an ADMIN
- WHEN the profile is queried
- THEN its name, address, and phone are returned as saved

### Requirement: No Multi-Store / No `storeId` Reintroduction

The system MUST NOT reintroduce a re-creatable multi-store entity or a `storeId` foreign key on any POS domain entity (Product, Inventory, Order, CashSession, Currency, etc.) as part of this capability.

#### Scenario: No store-creation screen exists
- GIVEN the app after this change
- WHEN the app's reachable screens are enumerated
- THEN no "create store" or "add store" screen is reachable anywhere

#### Scenario: No domain entity carries a storeId column
- GIVEN the POS domain schema after this change
- WHEN each `@Entity` is inspected
- THEN none of them carry a `storeId` (or equivalent multi-store) column

## Design-Level Open Questions (for sdd-design)

- Whether the profile is versioned or audited for change history is not specified here.
- Recovery or persistence guarantees for a lost/corrupted business-profile row (vs. recreate via header action) are not committed by this proposal.
