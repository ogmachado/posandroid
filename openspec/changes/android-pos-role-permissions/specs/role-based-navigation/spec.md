# Role-Based Navigation Specification (Amended by `android-pos-role-permissions`)

## Amendment Note

This is a **delta/amendment** to `openspec/changes/android-pos-auth/specs/role-based-navigation/spec.md`. It strengthens the existing "Tab Visibility Does Not Replace Action-Level Gating" requirement to explicitly cover product creation reached via the barcode-scan hand-off (`PosNavHost.kt`'s `onUnknownBarcode`, currently routing a CASHIER straight to product-create with zero gating), and requires that this and any similar gating route through the shared `role-capability-model` predicates. All other requirements in the origin spec — CASHIER/ADMIN tab visibility, session-derived tab list — are **retained unchanged** and not restated here.

## MODIFIED Requirements

### Requirement: Tab Visibility Does Not Replace Action-Level Gating

Hiding or showing a tab MUST NOT be treated as a substitute for action-layer enforcement. This applies to the `permission-gate` PIN checks on price/costPrice edits and creation and `ADJUST` inventory movements — those remain independently enforced even when the Productos/Inventario tabs are visible to an ADMIN — and it applies equally to any code path that reaches a gated action without passing through a visible tab at all, including the barcode-scan hand-off in `PosNavHost.kt` (`onUnknownBarcode`), which today routes a CASHIER straight to product creation with no gate. Any such hand-off MUST route its role decision through the shared `role-capability-model` predicates (`RolePermissions.kt`), not through a local or ad hoc check.
(Previously: covered only the Productos/Inventario tab's interaction with `permission-gate`; did not address non-tab hand-off paths such as the barcode-scan product-create route, which shipped ungated.)

#### Scenario: An ADMIN with visible Productos tab still faces the price-edit PIN gate
- GIVEN an ADMIN session with the Productos tab visible
- WHEN that ADMIN attempts to edit a product's price
- THEN the `permission-gate` PIN requirement still applies exactly as specified in that capability — tab visibility alone does not authorize the price change

#### Scenario: A CASHIER scanning an unknown barcode is not routed to product-create
- GIVEN a session authenticated as `CASHIER` is mid-sale
- WHEN that session scans a barcode with no matching product
- THEN the app MUST NOT navigate to the product-create form
- AND an actionable "ask an ADMIN to add this product" message is shown, with the sale remaining usable

#### Scenario: The barcode-scan hand-off decision routes through the shared predicate
- GIVEN the barcode-scan-to-product-create hand-off logic in `PosNavHost.kt`
- WHEN it decides whether to allow navigation to product-create
- THEN it calls `RolePermissions.canCreateProducts()` (or the equivalent shared predicate) rather than a local or inline role comparison

## Unchanged (carried forward from the base spec, not restated here)

- "CASHIER Sees Only Venta And Caja"
- "ADMIN Sees All Four Tabs"
- "Tab Visibility Derives From Session Role At Composition Time"
