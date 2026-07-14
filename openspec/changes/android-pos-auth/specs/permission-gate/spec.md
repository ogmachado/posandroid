# Permission Gate Specification (Amended by `android-pos-auth`)

## Amendment Note

This is a **delta/amendment** to `openspec/changes/android-pos-mvp/specs/permission-gate/spec.md`. It supersedes that spec's Purpose statement and re-expresses its requirements against the unified per-user credential store introduced by `user-identity`. The gated-action *behavior* (point-in-time PIN check, price-edit + `ADJUST` coverage, no session carry-over) is **retained**; only the identity of "the PIN" changes — from a single standalone secret to a per-user hashed credential filtered by role.

## Purpose

The original spec stated: *"This is NOT a user/account/role system — there is one PIN, checked at the point of the sensitive action, with no login flow and no persisted session-based auth state."* **That statement is reversed by `android-pos-auth`.** The app now has named users, roles, and a login gate (see `user-identity`, `login-gate`).

However, this specific gate keeps its original point-in-time character deliberately: editing a product's price or recording an `ADJUST` inventory movement requires **a PIN matching any user holding the `ADMIN` role**, entered at the moment of the action — regardless of who (if anyone with a different role) is currently logged in. This gate is an **ADMIN-credential check**, not a login-session check. It does not read or trust the login session's role; it independently verifies a PIN against the unified per-user credential store at the moment of the sensitive action.

## Firm Resolutions To The Two Open Questions (this amendment settles both)

| # | Question | Resolution |
|---|----------|------------|
| 1 | Does the gate accept **any** ADMIN's PIN, or specifically the **currently logged-in** ADMIN's? | **Any user holding the `ADMIN` role.** The gate verifies the entered PIN against the unified credential store filtered to `role = ADMIN`; it does not compare against the session's logged-in username. This preserves a real-world "manager override" pattern: a CASHIER-logged-in operator can complete a price edit by having any ADMIN type their own PIN, with no logout/login required. |
| 2 | Is a logged-in ADMIN **exempt** from re-entering the PIN per action, or still **re-prompted**? | **Still re-prompted, every time.** The login session grants no carry-over for this gate — consistent with (1): since the check is against "any ADMIN's PIN" rather than "the session's identity," the session's role is irrelevant to this gate and cannot exempt it. This directly retains the original spec's "point-in-time check, no session" requirement without contradiction. |

## Requirements

### Requirement: PIN Gates Product Price Edits

The system MUST require a PIN matching a user with the `ADMIN` role to be entered before a product's price is changed. Without a matching PIN, the price change MUST NOT be applied.

#### Scenario: A matching ADMIN PIN allows a price edit
- GIVEN a product currently priced at 100
- WHEN the operator enters a PIN matching an existing ADMIN user and confirms a new price of 120
- THEN the product's price is updated to 120

#### Scenario: A non-matching PIN blocks a price edit
- GIVEN a product currently priced at 100
- WHEN the operator enters a PIN that does not match any ADMIN user's credential and attempts to confirm a new price of 120
- THEN the price change MUST be rejected
- AND the product's price remains 100

#### Scenario: A CASHIER's own PIN does not satisfy the gate
- GIVEN a product currently priced at 100
- WHEN the operator enters a PIN that correctly matches a `CASHIER` user (not an `ADMIN`)
- THEN the price change MUST be rejected — the credential must belong to an `ADMIN`-role user, not merely be a valid credential for someone

#### Scenario: Price edit attempted without entering a PIN
- GIVEN a product currently priced at 100
- WHEN the operator attempts to save a price change without being prompted through the PIN gate at all
- THEN the underlying edit path MUST NOT be reachable without the gate — no price change is possible without a matching-ADMIN PIN check occurring first

### Requirement: PIN Gates ADJUST Inventory Movements

The system MUST require a PIN matching a user with the `ADMIN` role before an `ADJUST` movement is recorded against any product's stock. `IN` and `OUT` movements are NOT gated by this requirement.

#### Scenario: A matching ADMIN PIN allows an ADJUST movement
- GIVEN a product has stock of 15
- WHEN the operator enters a PIN matching an existing ADMIN user and requests an ADJUST to 8
- THEN the movement is recorded and stock becomes 8

#### Scenario: A non-matching PIN blocks an ADJUST movement
- GIVEN a product has stock of 15
- WHEN the operator enters a PIN that does not match any ADMIN user's credential and requests an ADJUST to 8
- THEN the movement MUST be rejected
- AND stock remains 15, and no movement row is persisted

#### Scenario: IN and OUT movements are not gated
- GIVEN a product has stock of 15
- WHEN the operator records an `IN` movement of quantity 5 without any PIN prompt
- THEN the movement succeeds without a PIN check
- AND the same is true for an `OUT` movement within available stock

### Requirement: Gate Verification Targets The ADMIN Role, Not The Login Session

The system MUST verify the entered PIN against the unified per-user credential store filtered to users with the `ADMIN` role, and MUST NOT consult or require agreement with the currently logged-in session's identity or role.

#### Scenario: A CASHIER-logged-in session completes a price edit via a called-over ADMIN's PIN
- GIVEN the active login session is authenticated as a `CASHIER`
- WHEN that operator enters a different, existing ADMIN user's correct PIN at the price-edit gate
- THEN the price edit MUST be permitted — the gate does not require the session itself to be an ADMIN

#### Scenario: A logged-in ADMIN's session role alone does not satisfy the gate
- GIVEN the active login session is authenticated as an `ADMIN`
- WHEN that operator attempts a price edit without entering any PIN at the gate prompt
- THEN the price edit MUST be rejected — being logged in as ADMIN does not itself satisfy this gate; a PIN must still be entered and verified

### Requirement: PIN Verification Is A Point-in-Time Check, With No Login-Session Exemption

The system MUST verify the PIN at the moment of the gated action and MUST NOT exempt a logged-in ADMIN's session from re-entering the PIN for subsequent gated actions.

#### Scenario: Two gated actions in a row each require a fresh PIN entry, even for a logged-in ADMIN
- GIVEN the active login session is authenticated as an `ADMIN`, and that ADMIN's own PIN was just entered to complete a price edit
- WHEN the same operator immediately attempts an ADJUST movement afterward
- THEN the PIN MUST be requested again for the ADJUST movement — neither the prior successful check nor the ADMIN login session carries over

### Requirement: The Standalone Singleton PIN Store Is Retired

The system MUST NOT retain the pre-existing standalone EncryptedSharedPreferences singleton PIN as a separate credential parallel to the unified per-user store. `PinHasher`'s hashing routine is reused, but the credential this gate checks against now belongs to per-user rows in the unified store (see `user-identity`).

#### Scenario: Gate verification reads from the unified per-user store, not the old singleton
- GIVEN the unified per-user credential store is in place
- WHEN the price-edit or ADJUST gate verifies a PIN
- THEN it queries the per-user credential store filtered to `ADMIN` role — it does not read the pre-existing singleton PIN value

## Design-Level Open Questions (for sdd-design)

- Exact PIN storage/hashing mechanics for per-user credentials (reusing `PinHasher` as-is vs. adapting it) are a design-level implementation detail, not fixed by this amendment.
- Whether a PIN attempt has a retry limit or lockout/backoff behavior remains unspecified, unchanged from the original spec.
- Recovery of a lost sole-ADMIN PIN (another ADMIN resets it, or a documented reinstall/wipe path) remains a design-level open question, not committed here.
