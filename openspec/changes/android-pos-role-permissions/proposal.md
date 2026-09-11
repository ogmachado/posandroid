# Proposal: android-pos-role-permissions (Centralized session-role capability model + defense-in-depth enforcement)

## Intent

Role gating today is **ad hoc and UI-only**. Three inline `currentRole == UserRole.ADMIN` checks (`MainActivity.kt:195,222,224`) plus one `when` filter (`PosNavHost.kt:341-344`) are the entire session-role model — there is no shared "may this role do X" primitive, so each new gated feature means pasting another inline check.

Worse, hiding UI is not enforcement, and one gap is **actively shipped**: a CASHIER scanning an unrecognized barcode mid-sale is navigated straight to the product-create form (`PosNavHost.kt:152-153`), where `createProduct` is gated by nothing — arbitrary price and cost, no role check, no PIN. `role-based-navigation`'s own "Tab Visibility Does Not Replace Action-Level Gating" requirement already forbids this in principle; product creation just never got its gate. The same UI-reachability-only pattern leaves `AuthRepository.createUser`/`changePin` and `BusinessProfileRepository.save()` with zero caller-role checks.

This change introduces the missing primitive, closes the reachable bypass, and pushes enforcement down to the action layer.

## Scope

### In Scope

- **`permission/RolePermissions.kt`** — pure extension-function predicates on `UserRole` (`canManageUsers()`, `canAccessBusinessProfile()`, `canCreateProducts()`), unit-testable without Robolectric.
- **Replace all 4 existing inline checks** (`MainActivity.kt:195,222,224`, `PosNavHost.visibleTabsFor`) with predicate calls; behavior unchanged.
- **Thin `RoleGate(requires = ...) { content }` Composable** — UI-layer sugar over the predicates, not the enforcement mechanism.
- **Action-layer enforcement (defense in depth)**: `AuthRepository.createUser`, `AuthRepository.changePin`, and the business-profile write path MUST reject a non-ADMIN caller even when reached in code.
- **Block CASHIER product creation** (decision 1): the barcode-scan path does not route a CASHIER to product-create; it surfaces a clear "ask an ADMIN to add this product" message and the sale continues.
- **`costPrice` PIN parity** (decision 2): `ProductViewModel.submitUpdate` routes through `PinGate.require` when `costPrice` changed, exactly as it already does for `price` (`ProductViewModel.kt:192-194`).
- **PIN gate extended to product creation** (open question 3, resolved): setting an initial `price`/`costPrice` when creating a new product now also requires the PIN callover — closing the "creation was always PIN-free" gap the original `permission-gate` spec left open. Applies to whoever can still reach creation post-scope (ADMIN only, since CASHIER's path is blocked per decision 1).
- **Spec amendments** (authored in the spec phase, amendment-note convention).

### Out of Scope

- **Cash-session close stays ungated** for both roles (decision 3) — normal shift operation, not administrative. Explicit non-goal; do not add gating during design/tasks.
- **Per-user capability flags** (backend `HIDE_SALES_TOTALS` style) — deferred (decision 5), no observed need.
- **`PinGate`/`permission-gate` mechanism untouched.** The any-ADMIN PIN-callover semantics (point-in-time, ignores session role) are additive-compatible with the new session-role model, never collapsed into it.
- **No `Capability` enum / central permission table** — rejected in exploration: over-centralized for 2 roles / ~6 capabilities, and unlike the reference backend's per-module co-located gating.
- **No schema, DAO, entity, or migration change.** No new nav route. Offline, on-device.
- **Non-existent capabilities not designed ahead** (sale void/refund, product delete, category/currency management, reports).

## Capabilities

### New Capabilities

- `role-capability-model`: the centralized session-role predicate primitive, its "derive fresh from session, never persist permission state" contract, and the requirement that role-gated actions are enforced at the action layer (ViewModel/Repository), not only by UI reachability.

### Modified Capabilities

- `role-based-navigation`: amend "Tab Visibility Does Not Replace Action-Level Gating" so it explicitly covers product creation reached via the barcode-scan hand-off, and require gating to route through the shared predicates.
- `user-management`: amend "Only ADMIN Can Create Accounts" — today's scenario asserts only that "no account-creation action is reachable" for a CASHIER; strengthen to require the account-creation and PIN-change write paths themselves to reject a non-ADMIN caller.
- `permission-gate`: extend the ADMIN-PIN gate from `price` to `costPrice`, and from existing-product edits to product **creation** as well (setting an initial `price`/`costPrice` now also requires the callover). Everything else about the gate (any-ADMIN credential, point-in-time, no session carry-over) is retained verbatim.
- `business-profile`: strengthen the ADMIN-only in-app action from UI reachability to write-path caller-role enforcement.

## Approach

Extension-function predicates on the existing `UserRole` enum in a new `permission/RolePermissions.kt`, applied at **both** layers:

1. **Composition sites** — the 4 existing checks call predicates (optionally via `RoleGate`), keeping design.md Decision H's "derive fresh from session at composition time, no stored permission rows" philosophy.
2. **Action entry points** — the three currently-unenforced write paths check the caller's role before writing.

Mirrors the reference backend's actual pattern (small, named, scoped checks) rather than a permission matrix. Two clearly-documented gating mechanisms coexist by design: **session-role predicates** ("is my role allowed?") and **PIN callover** ("call an ADMIN over").

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/.../permission/RolePermissions.kt` | New | `UserRole` predicate extensions |
| `app/.../permission/RoleGate.kt` | New | Composable sugar over predicates |
| `app/.../MainActivity.kt` | Modified | 3 inline checks → predicates/`RoleGate` |
| `app/.../nav/PosNavHost.kt` | Modified | `visibleTabsFor` via predicate; `onUnknownBarcode` blocks CASHIER instead of navigating to create |
| `app/.../permission/AuthRepository.kt` | Modified | `createUser`/`changePin` reject non-ADMIN caller |
| `app/.../business/BusinessProfileRepository.kt` (or its ViewModel) | Modified | Write path rejects non-ADMIN caller |
| `app/.../catalog/ProductViewModel.kt` | Modified | `costPrice` change joins the `PinGate.require` branch (edit path); creation path also gated |
| `app/.../catalog/ProductFormScreen.kt` | Modified (likely) | Cost-price field participates in the PIN flow |
| `openspec/changes/android-pos-auth/specs/{role-based-navigation,user-management,permission-gate}/spec.md` | Amended (spec phase) | Per Capabilities above |
| `openspec/changes/android-pos-auth-login-first/specs/business-profile/spec.md` | Amended (spec phase) | Write-path enforcement |
| Tests | New + Modified | `RolePermissionsTest` (pure JVM); repository rejection tests; `PosNavHostTest` CASHIER-barcode case; `ProductViewModel` costPrice-gate case |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Repository role check needs a session source — read `AuthRepository.currentSession` internally (co-location) vs. take an explicit caller-role param (testability) | High | Firm design decision required in `sdd-design`; must be uniform across all three write paths. `BusinessProfileRepository` depending on `AuthRepository` is a new cross-package edge — evaluate enforcing at the ViewModel instead |
| Blocking CASHIER product-create degrades a real sales workflow (unknown barcode → sale stalls) | Med | Decision 1 is confirmed; message must be actionable and the sale must remain usable (scan another item / manual entry). UX detail for design |
| `costPrice` gate changes an existing tested flow | Med | Extend the existing `priceChanged` branch, do not restructure `submitUpdate`; its documented double-`_events`-emission contract must survive |
| Amending 4 specs across 2 change folders (no canonical `openspec/specs/`) | Med | Follow the established amendment-note convention; the spec phase lists exact targets |
| `changePin` blanket ADMIN-only forecloses future CASHIER self-service PIN change | Low | Confirmed acceptable; no self-service UI exists today |
| PIN-gating product creation adds a callover step ADMIN never had before, even though only ADMIN can reach creation | Low | Confirmed intentional (extra confirmation gesture on setting an initial price, not a role check) — flag if it proves friction-heavy in practice |
| Predicates drift back to inline checks later | Low | Spec requirement that role gating routes through the shared primitive |

## Rollback Plan

Revert this change's commits. No schema, migration, or repository-contract change ships, so no data unwind exists: `RolePermissions.kt`/`RoleGate.kt` are new files, the 4 composition-site edits restore to their current inline form, the repository checks are subtractive-only, and the `costPrice` gate reverts to the `priceChanged`-only branch. Reverting restores the barcode-scan → product-create navigation (the pre-existing bypass) — an accepted consequence of a full revert, not a partial-rollback hazard. Spec amendments revert with the same commits.

## Dependencies

- `android-pos-auth` archived — supplies `UserRole`, `AuthRepository`/`AuthSession`, `PinGate`, `visibleTabsFor`, and 3 of the 4 amended specs.
- `android-pos-auth-login-first` archived — supplies `BusinessProfileScreen`/`Repository` and the ADMIN-only header pattern being centralized.
- `android-pos-mvp` merged — supplies `ProductViewModel`, `ProductFormScreen`, and the `permission-gate` origin spec.

## Open Questions — resolved

All three were put to the user and confirmed; none reopen the original 5 decisions:

1. **CASHIER self-PIN-change**: resolved as blanket ADMIN-only, no exception. No self-service UI exists today; not built in this change.
2. **Inventory write paths** (`IN`/`OUT` movements, set-minimum-stock): resolved as deferred. Same UI-hiding-only gap as this change fixes elsewhere, but documented as known follow-up debt rather than folded into this change's scope.
3. **Creation-time PIN gate**: resolved as **also gated** (reversing the original draft's assumption) — see "PIN gate extended to product creation" under In Scope, and the `permission-gate` capability amendment above.

## Success Criteria

- [ ] All 4 existing session-role checks (`MainActivity.kt:195,222,224`, `PosNavHost.visibleTabsFor`) route through `RolePermissions.kt`; no inline `== UserRole.ADMIN` role gate remains in composition code.
- [ ] `AuthRepository.createUser`, `AuthRepository.changePin`, and the business-profile write path reject a CASHIER caller with a test proving rejection when invoked directly, bypassing the UI.
- [ ] A CASHIER scanning an unknown barcode mid-sale is **not** navigated to product-create; a clear "ask an ADMIN" message appears and the sale remains usable.
- [ ] Changing `costPrice` on an existing product requires the same any-ADMIN PIN callover as `price`; both fields changed together prompt once.
- [ ] Creating a new product requires the same any-ADMIN PIN callover for its initial `price`/`costPrice` as an edit does.
- [ ] `PinGate`/`permission-gate` semantics (any-ADMIN credential, point-in-time, no session carry-over, price + `ADJUST` coverage) verifiably unchanged — existing tests pass unmodified.
- [ ] Cash-session close remains ungated for both roles; no gating added.
- [ ] `RolePermissions` predicates covered by pure-JVM unit tests with no Compose/Robolectric dependency.
- [ ] `role-capability-model` spec added; `role-based-navigation`, `user-management`, `permission-gate`, and `business-profile` amended via the amendment-note convention.
- [ ] No schema, migration, DAO, entity, or nav-route change ships.
