# Business Profile Specification

## Purpose

A single-row business profile (name, address, phone) captured once during first-run onboarding. This is explicitly **not** the multi-tenant `STORE` concept `android-pos-mvp` removed — there is exactly one business profile per device, with no re-creatable "store" entity and no `storeId` foreign key on any domain entity.

## Requirements

### Requirement: Exactly One Business Profile Row Exists

The system MUST persist exactly one business-profile row per device, holding name, address, and phone.

#### Scenario: Onboarding creates the single business-profile row
- GIVEN first-run onboarding captures a business name, address, and phone
- WHEN onboarding completes
- THEN exactly one business-profile row exists with those values

#### Scenario: No second profile row can be created
- GIVEN a business-profile row already exists
- WHEN any code path attempts to create another business-profile row
- THEN no second row is created — the store remains single-row

### Requirement: The Profile Is Captured As Part Of First-Run Onboarding

The system MUST capture the business profile during the same first-run flow that seeds the default ADMIN (see `first-run-onboarding`), not as a separately reachable setup step before or after it.

#### Scenario: Business profile capture happens within onboarding
- GIVEN a fresh install proceeding through first-run onboarding
- WHEN the operator reaches the business-profile step
- THEN it occurs within the same blocking onboarding flow, before the login gate is reachable

### Requirement: The Captured Profile Is Readable After Capture

The system MUST make the captured business profile (name, address, phone) readable after onboarding completes.

#### Scenario: The business profile can be read after onboarding
- GIVEN onboarding has completed and a business profile exists
- WHEN the profile is queried
- THEN its name, address, and phone are returned as captured

### Requirement: No Multi-Store / No `storeId` Reintroduction

The system MUST NOT reintroduce a re-creatable multi-store entity or a `storeId` foreign key on any POS domain entity (Product, Inventory, Order, CashSession, Currency, etc.) as part of this capability.

#### Scenario: No store-creation screen exists after onboarding
- GIVEN onboarding has completed
- WHEN the app's reachable screens are enumerated
- THEN no "create store" or "add store" screen is reachable anywhere

#### Scenario: No domain entity carries a storeId column
- GIVEN the POS domain schema after this change
- WHEN each `@Entity` is inspected
- THEN none of them carry a `storeId` (or equivalent multi-store) column

## Design-Level Open Questions (for sdd-design)

- The exact screen/surface where the captured profile is later readable (e.g., a settings screen) is not fixed here — the requirement is only that it remains readable, not where.
- Whether the profile is ever editable after initial capture (vs. capture-once-and-freeze) is not committed by this proposal; if pursued, it is a design-level addition.
