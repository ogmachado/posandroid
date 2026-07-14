# Permission Gate Specification

## Purpose

> **Superseded (2026-07-13) by `android-pos-auth`.** This spec's original Purpose statement below — *"This is NOT a user/account/role system ... there is one PIN, checked at the point of the sensitive action, with no login flow and no persisted session-based auth state"* — no longer describes the app. `android-pos-auth` introduced a real per-user credential model, roles, and a login gate, and reverses that statement. The current source of truth for this gate is `openspec/changes/android-pos-auth/specs/permission-gate/spec.md`, which amends the requirements below to check the entered PIN against any user holding the `ADMIN` role (not a single standalone PIN), while retaining this gate's original point-in-time, no-session-carry-over character. The original Purpose text is kept below, struck through in spirit, purely for historical record of what this spec described before the amendment.
>
> ~~A single local manager-PIN primitive that gates two sensitive actions: editing a product's price, and recording an `ADJUST` inventory movement. This is NOT a user/account/role system — there is one PIN, checked at the point of the sensitive action, with no login flow and no persisted session-based auth state beyond "the PIN was just entered for this action."~~

## Requirements

### Requirement: PIN Gates Product Price Edits

The system MUST require the correct manager PIN to be entered before a product's price is changed. Without a correct PIN, the price change MUST NOT be applied.

#### Scenario: Correct PIN allows a price edit

- GIVEN a product currently priced at 100
- WHEN the operator enters the correct manager PIN and confirms a new price of 120
- THEN the product's price is updated to 120

#### Scenario: Incorrect PIN blocks a price edit

- GIVEN a product currently priced at 100
- WHEN the operator enters an incorrect PIN and attempts to confirm a new price of 120
- THEN the price change MUST be rejected
- AND the product's price remains 100

#### Scenario: Price edit attempted without entering a PIN

- GIVEN a product currently priced at 100
- WHEN the operator attempts to save a price change without being prompted through the PIN gate at all
- THEN the underlying edit path MUST NOT be reachable without the gate — no price change is possible without a PIN check occurring first

### Requirement: PIN Gates ADJUST Inventory Movements

The system MUST require the correct manager PIN before an `ADJUST` movement is recorded against any product's stock. `IN` and `OUT` movements are NOT gated by this requirement.

#### Scenario: Correct PIN allows an ADJUST movement

- GIVEN a product has stock of 15
- WHEN the operator enters the correct manager PIN and requests an ADJUST to 8
- THEN the movement is recorded and stock becomes 8

#### Scenario: Incorrect PIN blocks an ADJUST movement

- GIVEN a product has stock of 15
- WHEN the operator enters an incorrect PIN and requests an ADJUST to 8
- THEN the movement MUST be rejected
- AND stock remains 15, and no movement row is persisted

#### Scenario: IN and OUT movements are not gated

- GIVEN a product has stock of 15
- WHEN the operator records an `IN` movement of quantity 5 without any PIN prompt
- THEN the movement succeeds without a PIN check
- AND the same is true for an `OUT` movement within available stock

### Requirement: PIN Verification Is a Point-in-Time Check

The system MUST verify the PIN at the moment of the gated action and MUST NOT persist an authenticated "session" that exempts subsequent gated actions from re-entering the PIN.

#### Scenario: Two gated actions in a row each require the PIN

- GIVEN the operator just entered the correct PIN to complete a price edit
- WHEN the operator immediately attempts an ADJUST movement afterward
- THEN the PIN MUST be requested again for the ADJUST movement — the prior successful check does not carry over

## Design-Level Open Questions (for sdd-design)

- Exact PIN storage/hashing approach (e.g., hashed-at-rest local value vs. plain local config) is NOT decided here — `sdd-design` must choose and document it explicitly.
- Whether a PIN attempt has a retry limit or lockout/backoff behavior is NOT specified here; this spec only requires that an incorrect PIN blocks the action, not what happens on repeated failures.
- How the PIN value itself is initially configured/reset (first-run setup vs. hardcoded default) is a design-level decision, not fixed by this spec.
