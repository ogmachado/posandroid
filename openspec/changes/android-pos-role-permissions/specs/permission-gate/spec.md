# Permission Gate Specification (Amended by `android-pos-role-permissions`)

## Amendment Note

This is a **delta/amendment** to `openspec/changes/android-pos-auth/specs/permission-gate/spec.md` (itself an amendment of the `android-pos-mvp` origin). It extends the gate's scope in two ways: (a) from `price` to also cover `costPrice`, and (b) from existing-product edits only to **also** cover product creation — setting an initial `price`/`costPrice` when creating a new product now requires the same any-ADMIN PIN callover. Only the "PIN Gates Product Price Edits" requirement is modified below. Everything else about the gate — any-ADMIN credential (not session-role), point-in-time check with no session carry-over, `ADJUST`-only inventory gating, retirement of the standalone singleton PIN store — is **retained verbatim** and not restated here.

## MODIFIED Requirements

### Requirement: PIN Gates Product Price And Cost-Price, On Both Edit And Creation

The system MUST require a PIN matching a user with the `ADMIN` role before a product's `price` or `costPrice` is set or changed — on editing an existing product AND on creating a new product. Without a matching PIN, the value MUST NOT be applied (edit) or the product MUST NOT be created with that value (creation). If `price` and `costPrice` both change together in one edit, or are both set together at creation, the operator is prompted for the PIN once, not twice.
(Previously: "PIN Gates Product Price Edits" — covered only the `price` field, and only on editing an existing product; `costPrice` and product creation were both ungated.)

#### Scenario: A matching ADMIN PIN allows a price edit
- GIVEN a product currently priced at 100
- WHEN the operator enters a PIN matching an existing ADMIN user and confirms a new price of 120
- THEN the product's price is updated to 120

#### Scenario: A matching ADMIN PIN allows a costPrice edit
- GIVEN a product with a current `costPrice` of 60
- WHEN the operator enters a PIN matching an existing ADMIN user and confirms a new `costPrice` of 70
- THEN the product's `costPrice` is updated to 70

#### Scenario: Changing price and costPrice together prompts for the PIN once
- GIVEN a product edit that changes both `price` and `costPrice`
- WHEN the operator submits the edit
- THEN exactly one PIN prompt is shown, and a matching ADMIN PIN applies both changes

#### Scenario: A non-matching PIN blocks a price or costPrice edit
- GIVEN a product currently priced at 100
- WHEN the operator enters a PIN that does not match any ADMIN user's credential and attempts to confirm a new price or costPrice
- THEN the change MUST be rejected and the prior values remain unchanged

#### Scenario: A CASHIER's own PIN does not satisfy the gate
- GIVEN a product being edited
- WHEN the operator enters a PIN that correctly matches a `CASHIER` user (not an `ADMIN`)
- THEN the price/costPrice change MUST be rejected — the credential must belong to an `ADMIN`-role user

#### Scenario: Creating a new product with a price and/or costPrice requires the same PIN callover
- GIVEN an ADMIN is creating a new product with an initial `price` and `costPrice`
- WHEN the creation form is submitted
- THEN a matching ADMIN PIN MUST be entered before the product is persisted with those values

#### Scenario: A non-matching PIN blocks product creation
- GIVEN an ADMIN is creating a new product with an initial `price` and `costPrice`
- WHEN a PIN is entered that does not match any ADMIN user's credential
- THEN the product MUST NOT be created

#### Scenario: The edit and creation write paths are unreachable without the gate
- GIVEN the product edit and product creation write paths
- WHEN either is invoked
- THEN neither is reachable without a matching-ADMIN PIN check occurring first — no price/costPrice value is ever set without the gate

## Unchanged (carried forward from the base spec, not restated here)

- "PIN Gates ADJUST Inventory Movements" — `IN`/`OUT` remain ungated; unchanged.
- "Gate Verification Targets The ADMIN Role, Not The Login Session"
- "PIN Verification Is A Point-in-Time Check, With No Login-Session Exemption"
- "The Standalone Singleton PIN Store Is Retired"
