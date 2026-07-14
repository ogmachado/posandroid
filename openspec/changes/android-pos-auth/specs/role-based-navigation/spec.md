# Role-Based Navigation Specification

## Purpose

The bottom-nav tab list (`posTabs` in `nav/PosNavHost.kt`) is filtered by the authenticated session's role. `CASHIER` sees only Venta and Caja; `ADMIN` sees all four tabs (Venta / Productos / Inventario / Caja) — mirroring the desktop reference's `roleGuard(['ADMIN'])` on catalog/inventory routes.

## Requirements

### Requirement: CASHIER Sees Only Venta And Caja

The system MUST show exactly the Venta and Caja tabs — and no others — to a session authenticated with the `CASHIER` role.

#### Scenario: A CASHIER login shows exactly two tabs
- GIVEN a session authenticated as a `CASHIER`
- WHEN the POS shell renders `posTabs`
- THEN only the Venta and Caja tabs are present — Productos and Inventario are absent

### Requirement: ADMIN Sees All Four Tabs

The system MUST show all four tabs — Venta, Productos, Inventario, Caja — to a session authenticated with the `ADMIN` role.

#### Scenario: An ADMIN login shows all four tabs
- GIVEN a session authenticated as an `ADMIN`
- WHEN the POS shell renders `posTabs`
- THEN Venta, Productos, Inventario, and Caja are all present

### Requirement: Tab Visibility Derives From Session Role At Composition Time

The system MUST derive tab visibility directly from the current session's role when `posTabs` is composed, with no separate stored "tab permission" concept.

#### Scenario: Tab list reflects the current session's role, not a cached value
- GIVEN a session's role is known at composition
- WHEN `PosNavHost` composes `posTabs`
- THEN the filtered list matches that role exactly (CASHIER → 2 tabs, ADMIN → 4 tabs) with no stale/cached tab set from a prior session

### Requirement: Tab Visibility Does Not Replace Action-Level Gating

Hiding or showing a tab MUST NOT be treated as a substitute for the `permission-gate` PIN checks on price edits and `ADJUST` inventory movements — those remain independently enforced even when the Productos/Inventario tabs are visible to an ADMIN.

#### Scenario: An ADMIN with visible Productos tab still faces the price-edit PIN gate
- GIVEN an ADMIN session with the Productos tab visible
- WHEN that ADMIN attempts to edit a product's price
- THEN the `permission-gate` PIN requirement still applies exactly as specified in that capability — tab visibility alone does not authorize the price change

## Design-Level Open Questions (for sdd-design)

- Whether tab filtering is implemented as a `when(role)` list transform inside `PosNavHost` or a role-aware factory function is left to design; the requirement is only the resulting visible set per role.
