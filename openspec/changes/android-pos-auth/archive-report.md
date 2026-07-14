# Archive Report: android-pos-auth

**Date**: 2026-07-14  
**Status**: ARCHIVED AND CLOSED  
**Artifact Store**: hybrid (filesystem + Engram)

## Executive Summary

The `android-pos-auth` SDD change is complete and archived. All 38 tasks across 4 work units (Phases 0–4) are finished. All 8 Success Criteria from the proposal are satisfied. The two mandatory spec/doc amendments have been applied. The implementation is feature-complete with no blocking issues.

## Scope Confirmation

| Item | Status | Details |
|------|--------|---------|
| Proposal (8 Success Criteria) | COMPLETE | All criteria confirmed satisfied in verify-report |
| Design (locked decisions A–J) | COMPLETE | No open design questions remain |
| Tasks (38 total across 4 work units) | COMPLETE | All marked `[x]` in `tasks.md`; verified by independent gatekeeper audit |
| Specs (7 delta specs) | COMPLETE | `user-identity`, `first-run-onboarding`, `login-gate`, `role-based-navigation`, `user-management`, `business-profile`, `permission-gate` (amendment) — all in `openspec/changes/android-pos-auth/specs/` |
| Doc Amendments (2) | COMPLETE | `openspec/changes/android-pos-mvp/specs/permission-gate/spec.md` reversed + pointed to `android-pos-auth`; `openspec/project.md` baseline retired |
| Build & Tests | PASS | Full suite green (252 tests) across 2 fresh runs with daemon reset |

## Success Criteria Verification

All 8 Success Criteria from `proposal.md` § Success Criteria confirmed satisfied:

1. **[ ✓ ] Fresh install auto-seeds default ADMIN + one-time business-profile capture; seeded credential changeable afterward.**
   - `AuthRepository.ensureDefaultAdminSeeded()` (idempotent, Phase 1)
   - `OnboardingScreen` captures business name/address/phone (Phase 2)
   - `UserManagementScreen.changeOwnPin()` allows ADMIN credential change (Phase 4)
   - Test: `UserManagementScreenTest.theDefaultAdmin_changesTheirSeededPin`

2. **[ ✓ ] After onboarding, login gate presents; correct user PIN authenticates and reveals POS shell.**
   - `AuthGate` 3-way composable (Onboarding → Login → Authenticated) in `AppRoot()` (Phase 2)
   - `LoginScreen` user-picker + PIN-pad (Phase 2)
   - `AuthRepository.login(username, pin)` per-user verification (Phase 1)

3. **[ ✓ ] CASHIER sees only Venta/Caja tabs; ADMIN sees all four (Venta/Productos/Inventario/Caja).**
   - `PosNavHost(role: UserRole)` parameter (Phase 3)
   - `visibleTabsFor(role)` filtering applied to `posTabs` (Phase 3)
   - Test: `VisibleTabsForTest` confirms CASHIER gets 2 tabs, ADMIN gets 4

4. **[ ✓ ] Only ADMIN can create additional accounts; CASHIER has no account-creation path.**
   - `AuthRepository.createUser()` — app-level enforcement at repository
   - `UserManagementScreen` only reachable via ADMIN header action in `AppRoot` (Phase 4)
   - Test: `EnforcementGateTest.cashierSession_hasNoUserManagementHeaderAction` + `UserManagementScreenTest`

5. **[ ✓ ] Price-edit/ADJUST gates satisfied by unified ADMIN-capable credential; no standalone PIN store remains.**
   - `PinGate` seam retargeted to `AuthRepository.verifyAdminPin()` (Phase 3)
   - `verifyAdminPin()` checks against any `ADMIN`-role row, point-in-time (Phase 1, Design Decision G)
   - `PinRepository.kt` deleted (Phase 3)
   - Test: `PinGateDialogTest` retargeted to suspend verifier + all call sites in product/inventory screens updated

6. **[ ✓ ] `MIGRATION_2_3` creates users + business-profile tables and passes `runMigrationsAndValidate`.**
   - `MIGRATION_2_3` creates `app_user` and `business_profile` tables (Phase 1)
   - Matches exported `3.json` schema
   - Test: `Migration2To3Test.migration2To3_appliesCleanly_toAV2SchemaFixture` green both runs

7. **[ ✓ ] `permission-gate/spec.md` amended (Purpose reversed) + `openspec/project.md` baseline retired.**
   - `openspec/changes/android-pos-mvp/specs/permission-gate/spec.md`: Purpose blockquote points to `android-pos-auth` as superseding source; original text retained struck-through for history (Commit `724f5b8`)
   - `openspec/project.md` Core Product Shape: "single fixed cashier / single implicit store" baseline retired with `android-pos-auth` recorded as explicit successor (Commit `724f5b8`)

8. **[ ✓ ] No POS domain entity (Product/Inventory/Order/CashSession/Currency) gains `userId`/`storeId`; multi-store/networking remain absent.**
   - Schema `3.json` contains only `app_user` (new), `business_profile` (new), and 10 pre-existing domain tables unchanged
   - No domain entity file touched in any of 4 work units
   - Single-row `business_profile` design enforced via `SINGLE_ROW_ID` pattern

## Known Issues (Out-of-Scope)

**Pre-existing flaky test (deferred by user, not blocking):**
- Test: `com.idos.pos.currency.CurrencyConversionRowTest.conversionRow_recomputesLive_whenAmountChanges`
- Root cause: `CurrencyConversionRows` collects `activeCurrenciesFlow` on background dispatcher independent of `Robolectric`'s `shadowOf(Looper).idle()`
- Status: Explicitly deferred as out-of-scope debt; does not block `android-pos-auth` archive
- Evidence: Gatekeeper report (obs #76) shows test flakiness existed in prior work-unit runs; not introduced by any `android-pos-auth` commit
- Note: Same flakiness class was fixed in `PosNavHostTest` via commit `ec9acbd` (waitUntil on real flow read). A systemic fix across the suite is a separate follow-up.

## Artifacts

### File-based artifacts (openspec/)
- `openspec/changes/android-pos-auth/proposal.md` — full proposal with 8 Success Criteria
- `openspec/changes/android-pos-auth/design.md` — locked design with 10 architecture decisions A–J
- `openspec/changes/android-pos-auth/tasks.md` — all 38 tasks marked `[x]`
- `openspec/changes/android-pos-auth/specs/*/spec.md` (7 files) — delta specs for all capabilities
- `openspec/changes/android-pos-auth/archive-report.md` — this file

### Engram observations (persistent memory)
- #76 `sdd/android-pos-auth/verify-report` — fresh-context gatekeeper audit (all 8 criteria confirmed, all 38 tasks verified)
- #75 `sdd/android-pos-auth/apply-progress` — full implementation record across all 4 work units
- #74 `sdd/android-pos-auth/tasks` — synced tasks.md (all 38 tasks `[x]`)
- #77 `sdd/android-pos-auth/archive-report` — this archive report (saved on closure)

## Implementation Summary

### What shipped
- **Multi-user identity model** — `UserEntity` + `UserDao` in Room, `AuthRepository` owning session state
- **Credential hashing** — per-user salted PBKDF2 hashes (reusing `PinHasher`)
- **First-run onboarding** — auto-seeds default ADMIN + one-time business-profile capture
- **Login gate** — post-license, pre-POS; 3-way `AuthGate` composable swapping Onboarding/Login/Authenticated states
- **Role-based tab visibility** — CASHIER restricted to Venta/Caja; ADMIN sees all 4 tabs
- **User management** — ADMIN-only screen for creating CASHIER/ADMIN accounts and changing own PIN
- **Permission-gate retargeting** — price-edit/ADJUST gates now verify against unified ADMIN-capable credential (not singleton PIN)
- **Spec/doc amendments** — `permission-gate/spec.md` amended + `project.md` baseline retired

### Execution by work unit
| Unit | Goal | Commits | Status |
|------|------|---------|--------|
| 1 (PR 1) | Schema + strict-TDD core (UserDao/AuthRepository/Migrations) | (merged) | Complete |
| 2 (PR 2) | AuthGate + onboarding + login + session wiring | (merged) | Complete |
| 3 (PR 3) | Tab role gating + permission-gate seam retarget + PinRepository deletion | ec9acbd, (merged rest) | Complete |
| 4 (PR 4) | User management + credential change + spec/doc amendments | 724f5b8, 180aaea, ec9acbd | Complete |

### Testing results
- Full unit-test suite: **252 tests, 0 failures** (2 fresh runs, daemon reset between runs)
- Strict-TDD coverage (repo/dao/migration): Robolectric + in-mem Room, all passing
- Alongside tests (screens/viewmodels): written with impl, all passing
- No regressions introduced in pre-existing tests (permission-gate, nav, all domain layers)

### Deviations from original task text
- **Room schema export location**: `app/src/main/assets/com.idos.pos.core.db.PosDatabase/3.json` (per actual `room.schemaLocation` in `build.gradle.kts`), not `app/schemas/` (which does not exist in this project)
- **PosNavHostTest regression fix**: Pre-existing flaky teardown race fixed proactively by gatekeeper (commit ec9acbd) before final verification to ensure test suite stability
- **AuthGate.Authenticated branch deviation in Phase 2**: Calling no-arg `PosNavHost()` initially; resolved in Phase 3 (task 3.8) to pass `role` parameter once available

## Rollback Plan

Revert all 4 work-unit commits + the additive `MIGRATION_2_3`. The app returns to the single-fixed-cashier MVP behavior:
- No `app_user` or `business_profile` tables (schema drops to v2)
- `PinRepository` class restored from git history
- `AuthGate` removed from `AppRoot()`; direct `PosNavHost()` rendered again
- `posTabs` filtering removed; all users see all 4 tabs
- `permission-gate` and `project.md` amendments revert with the same commits

The POS domain (Product/Inventory/Order/CashSession/Currency) is untouched, so no destructive data coupling exists to unwind.

## Closure

This change is **ARCHIVED**. All proposal requirements are satisfied. All spec/doc amendments are applied. The change folder remains in `openspec/changes/android-pos-auth/` as the historical record (following the established convention where prior completed changes like `android-pos-mvp` and `android-pos-licensing` remain in place rather than moving to a separate archive folder).

No further work on `android-pos-auth` is required. Future changes that depend on multi-user identity or role-based gating reference this change's specs and design.

---

**Observation IDs for traceability:**
- `sdd/android-pos-auth/proposal` — recorded during sdd-propose phase
- `sdd/android-pos-auth/spec` — recorded during sdd-spec phase
- `sdd/android-pos-auth/design` — recorded during sdd-design phase
- `sdd/android-pos-auth/tasks` — #74 (synced from filesystem)
- `sdd/android-pos-auth/apply-progress` — #75 (4 work units)
- `sdd/android-pos-auth/verify-report` — #76 (gatekeeper audit)
- `sdd/android-pos-auth/archive-report` — #77 (this report)
