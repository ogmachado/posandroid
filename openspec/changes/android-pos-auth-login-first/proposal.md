# Proposal: android-pos-auth-login-first (Login is the first screen; business-profile becomes a post-login ADMIN action)

## Intent

The archived `android-pos-auth` change (design.md **Decision E**) deliberately ordered the auth gate `Onboarding -> Login -> Authenticated`: on a fresh install the operator was forced through a blocking first-run flow that seeded the default ADMIN **and** captured the business profile *before* the login screen was ever reachable. Login was gated behind business-profile existence (`AuthGateViewModel.recompute()` returns `Onboarding` whenever `!businessProfileRepository.exists()`).

This change **intentionally reverses Decision E.** Comparing the Android app against the project's own reference backend (`idos-pos`, Spring Boot — reference-only, no code dependency) surfaced a concrete divergence:

- The backend's `SecurityBootstrap.java` seeds a default ADMIN **unconditionally at boot** (a `CommandLineRunner`), independent of whether any store record exists.
- Store creation (`StoreController` at `/admin/stores`) is an **ordinary authenticated ADMIN REST action**, not a pre-login gate.

The Android app should match that shape: the default ADMIN is always seeded at process start, **Login is always the first screen on a fresh install**, and business-profile setup is a normal authenticated-ADMIN action performed *inside* the app — not a pre-login wizard that blocks every other capability behind it.

This is a "we are undoing an earlier documented decision" moment, not a silent contradiction. It therefore carries a mandatory reconciliation: the three specs `android-pos-auth` introduced — `first-run-onboarding`, `login-gate`, and `business-profile` — must be explicitly amended/reversed (using the same delta/amendment-note convention `android-pos-auth` itself used to amend `permission-gate`), because their current text asserts the opposite gate ordering. Those amendments are in-scope deliverables of the spec phase, called out here so downstream phases inherit a clean picture.

Everything remains **offline and on-device.** No schema change, no migration change, no repository-contract change — this is a gating / UI / startup-wiring change plus spec reconciliation.

## Scope

### In Scope

- **Unconditional default-ADMIN seeding at startup.** Move the admin auto-seed out of `OnboardingScreen`'s composition-scoped `LaunchedEffect` and into `PosApplication.onCreate()`, called unconditionally on every process start (the `CommandLineRunner` equivalent). `AuthRepository.ensureDefaultAdminSeeded()` is **already idempotent** (`userDao.count() > 0` guard) so no change to the seeding method itself is needed — only its call site moves.
- **Login-first gate ordering.** Remove `AuthGateState.Onboarding` entirely. The gate becomes exactly `Loading | Login | Authenticated`. `AuthGateViewModel` drops its `businessProfileRepository` dependency and its `onOnboardingCompleted()` method — gate reachability no longer depends on business-profile existence.
- **Business-profile setup as a post-login ADMIN action.** Repurpose `OnboardingScreen`/`OnboardingViewModel` into a `BusinessProfileScreen`/`BusinessProfileViewModel` reachable via a **second ADMIN-only header action in `AppRoot()`**, alongside the existing "Manage users" button, using the identical `showUserManagement` boolean-swap pattern. The screen drops the admin-seed `LaunchedEffect`, keeps the name/address/phone form bound to `BusinessProfileRepository.save()`, and pre-loads existing values via `BusinessProfileRepository.get()` since it is now a repeatable edit action, not a one-shot capture.
- **Rename `Onboarding*` to `BusinessProfile*`.** Rename `OnboardingScreen`/`OnboardingViewModel` (and `OnboardingScreenTest`) to `BusinessProfileScreen`/`BusinessProfileViewModel`/`BusinessProfileScreenTest` — the "onboarding" concept is retired, and keeping the old names would misdescribe the retained behavior.
- **Test-seeding reconciliation.** Every Robolectric test building `AppContainer.createInMemory()` directly (which bypasses `PosApplication.onCreate()`) that relies on the admin already existing MUST add an explicit `container.authRepository.ensureDefaultAdminSeeded()` in setup — a continuation of the pattern already present in ~8 test files. `AuthGateTest`'s `noBusinessProfile_showsOnboarding_posShellUnreachable` scenario is deleted (no `Onboarding` state remains); the other tests' `businessProfileRepository.save(...)` preconditions become irrelevant to gate reachability and are dropped.
- **Spec reconciliation for the three `android-pos-auth` specs.** `first-run-onboarding/spec.md`, `login-gate/spec.md`, and `business-profile/spec.md` are amended/reversed via the delta/amendment-note convention (see `permission-gate/spec.md`'s "Amendment Note" for the pattern). These are authored in the spec phase, not here.

### Out of Scope

- **Products & inventory management — explicitly UNTOUCHED.** Catalog and inventory screens already exist as ordinary post-login screens (from `android-pos-mvp`). This change does not add, move, or modify them. It only stops them (and everything else) from being blocked behind a pre-login business-profile wizard — which the login-first reordering achieves for free.
- **No user-management change.** ADMIN user management already exists (`UserManagementScreen` + its ADMIN-only header action). This change adds a *sibling* header action for business-profile; it does not alter user management.
- **No repository / DAO / entity / migration change.** `AuthRepository.ensureDefaultAdminSeeded()` (idempotent), `BusinessProfileRepository.save()` (upsert via `OnConflictStrategy.REPLACE`) and `get()`, `MIGRATION_2_3` (schema only, no seeding), and the `app_user`/`business_profile` tables are all unchanged. Purely a call-site + gating + UI relocation.
- **No change to the `permission-gate` amendment** from `android-pos-auth` — the price-edit / inventory-`ADJUST` ADMIN-credential gate is unaffected by gate reordering.
- **No networking, no backend connection, no sync.** The reference backend is a shape comparison only; the Android app stays offline and device-local.
- **No new nav route.** The business-profile screen uses the boolean-composable-swap idiom (like `UserManagementScreen`), not a navigation graph entry.

## Relationship To `android-pos-auth`

This proposal is an explicit **reversal/amendment of `android-pos-auth` design Decision E** (gate ordering `Onboarding -> Login -> Authenticated`). It does **not** reverse the rest of `android-pos-auth`: the multi-user model, per-user PIN credentials, role-based tab gating, ADMIN-only user management, and the `permission-gate` amendment all stand. Only the *ordering* and the *pre-login blocking nature* of first-run onboarding are retired, and the business profile is demoted from a first-run capture to a post-login ADMIN action.

## Decisions (firm — sdd-design details HOW, not WHICH)

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| 1 | Seed call site | **`PosApplication.onCreate()`**, unconditional, alongside the existing `initialLicenseStatus = runBlocking { ... }` call | Mirrors the backend's `SecurityBootstrap` `CommandLineRunner` most literally; reuses the exact startup idiom already established at `PosApplication.kt:38-42` (verified); keeps `core/di/**` as wiring, not business logic, per this codebase's own convention; `AuthGateViewModel` gets simpler (drops a dependency + a method). Rejected: seeding inside `AppContainer.create()/createInMemory()` factories — would free tests from an explicit seed call, but puts a business action in the DI layer and diverges from the boot-time analogy. |
| 2 | `Onboarding*` naming | **Rename** `OnboardingScreen`/`OnboardingViewModel`/`OnboardingScreenTest` to `BusinessProfileScreen`/`BusinessProfileViewModel`/`BusinessProfileScreenTest` | The "onboarding" concept is retired; keeping the name would misdescribe a repeatable ADMIN edit action. Accepts naming churn (imports, KDoc cross-references in `AuthGate.kt`/`MainActivity.kt`) as a one-time cost. |
| 3 | Gate states | **`AuthGateState` becomes exactly `Loading \| Login \| Authenticated`** — `Onboarding` removed | Gate reachability no longer depends on business-profile existence; login is always first once the license passes. |
| 4 | Business-profile UI entry point | **Second ADMIN-only header action in `AppRoot()`**, next to "Manage users", using the same `showUserManagement`-style boolean-swap | Reuses the exact verified pattern at `MainActivity.kt:177-196`; no new nav route; screen self-contains no role check (reachability enforced by the header, mirroring `UserManagementScreen`). Two header buttons is a minor layout addition; a `DropdownMenu` "admin menu" is deliberately avoided (the codebase avoids it for testability, per `UserManagementScreen`'s KDoc). |
| 5 | Business-profile requirement | **Optional — no gate, no nag, no banner.** ADMIN may operate the full POS without ever setting a business profile. Profile fields default to empty/nullable in the UI; the screen is reachable and editable at any time via the header action. | Matches the backend, where store creation is an ordinary authenticated ADMIN REST action with no pre-login or nag enforcement. The old hard blocking gate is retired outright; nothing softer (mandatory banner / forced dismissal) replaces it — a dismissible nag would reintroduce the very "profile-before-work" coupling this change removes. |
| 6 | Spec reconciliation | **Amend/reverse `first-run-onboarding`, `login-gate`, and `business-profile` specs** via the delta/amendment-note convention (authored in the spec phase) | These three specs currently assert `Onboarding -> Login` ordering and "profile captured during first-run onboarding"; left unamended the repo self-contradicts. Same pattern `android-pos-auth` used to amend `permission-gate`. |

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/.../PosApplication.kt` | Modified | Add unconditional `appContainer.authRepository.ensureDefaultAdminSeeded()` at startup, alongside the existing `initialLicenseStatus` `runBlocking` call |
| `app/.../permission/AuthGateViewModel.kt` | Modified | Remove `Onboarding` branch from `recompute()`; drop `businessProfileRepository` dependency and `onOnboardingCompleted()` |
| `app/.../permission/AuthGate.kt` | Modified | Remove the `AuthGateState.Onboarding -> OnboardingScreen(...)` branch; `AuthGateState` becomes `Loading \| Login \| Authenticated` |
| `app/.../permission/OnboardingScreen.kt` + `OnboardingViewModel.kt` | Renamed + Modified | Become `BusinessProfileScreen`/`BusinessProfileViewModel`: drop the `ensureDefaultAdminSeeded()` `LaunchedEffect`; keep the name/address/phone form bound to `BusinessProfileRepository.save()`; pre-load existing values via `get()` |
| `app/.../MainActivity.kt` (`AppRoot()`) | Modified | Add a second ADMIN-only header action (next to "Manage users") that boolean-swaps to `BusinessProfileScreen`, following the `showUserManagement` pattern; remove `Onboarding` from KDoc |
| `app/.../permission/AuthGateTest.kt` | Modified | Delete `noBusinessProfile_showsOnboarding_posShellUnreachable`; drop now-irrelevant `businessProfileRepository.save(...)` preconditions; add explicit `ensureDefaultAdminSeeded()` in setup where gate composition previously supplied it |
| `app/.../permission/OnboardingScreenTest.kt` | Renamed + Split | Business-profile-capture assertions move to `BusinessProfileScreenTest`; the admin-seeding assertion no longer applies to this screen |
| `app/.../EnforcementGateTest.kt` | Modified (minor) | `seedBusinessProfileAndLogin*` helpers keep their explicit `ensureDefaultAdminSeeded()`; their `businessProfileRepository.save(...)` calls become vestigial (harmless) and may be trimmed |
| `openspec/changes/android-pos-auth/specs/first-run-onboarding/spec.md` | Amended (spec phase) | Reversed: no blocking first-run flow; seeding is unconditional at startup, not gated |
| `openspec/changes/android-pos-auth/specs/login-gate/spec.md` | Amended (spec phase) | Gate ordering reversed to License -> Login (no Onboarding step) |
| `openspec/changes/android-pos-auth/specs/business-profile/spec.md` | Amended (spec phase) | "Captured as part of first-run onboarding" reversed to "optional, editable, post-login ADMIN action" |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| **Test-seeding assumption break.** Tests building `AppContainer.createInMemory()` directly no longer get free seeding once it moves to `PosApplication.onCreate()`; a missed file fails with a confusing "empty user list" instead of a compile error. | High | Mechanical audit of every `createInMemory()` test that relied on gate/screen composition for seeding; add explicit `ensureDefaultAdminSeeded()` (existing pattern in ~8 files). `sdd-tasks` should enumerate them. |
| **Spec reversal, not just new specs.** `first-run-onboarding` and `login-gate` gate-ordering requirements, and `business-profile`'s "captured during onboarding" requirement, are directly contradicted — they need explicit amendment/reversal, not a fresh delta layered on top. | High | Decision 6 makes all three amendments in-scope for the spec phase, using the `permission-gate` amendment-note convention. |
| **No `PosApplicationTest.kt` exists.** There is no test harness at the exact layer the new seed call lives. | Med | `sdd-tasks`/`sdd-apply` decide whether to add one; the idempotency + login-reachability behavior is already covered indirectly by `AuthGateTest`/`EnforcementGateTest` once they seed explicitly. |
| **Two ADMIN header actions layout.** `AppRoot()`'s header currently holds one button; a second is a small layout decision, and a `DropdownMenu` is off the table for testability. | Low | Decision 4: two plain buttons in the existing header `Row`; each gets its own `testTag`. |
| **Naming churn.** Renaming `Onboarding*` touches imports and KDoc cross-references in `AuthGate.kt` and `MainActivity.kt`. | Low | Mechanical; covered by Decision 2 and the affected-areas list. |
| **Optional-profile downstream assumptions.** Any code that assumed a business profile always exists post-onboarding (e.g. receipt header) could now hit an empty profile. | Med | Decision 5 makes empty/nullable the explicit contract; `sdd-design`/`sdd-spec` should confirm no current consumer hard-requires a populated profile (none surfaced in exploration — receipts/domain untouched). |

## Rollback Plan

Fully additive-in-reverse and low-risk: rollback = revert this change's commits. Because there is **no schema change, no migration change, and no repository-contract change**, reverting restores the `android-pos-auth` `Onboarding -> Login` gate ordering with no data migration or destructive unwind. The `app_user`/`business_profile` tables and `MIGRATION_2_3` are untouched by this change, so a seeded admin and any saved business profile remain valid across the revert. The three spec amendments revert with the same commits, restoring the original `android-pos-auth` spec text.

## Dependencies

- `android-pos-auth` archived — provides the multi-user model, `AuthGateViewModel`/`AuthGate`, `OnboardingScreen`, `UserManagementScreen`, `AuthRepository.ensureDefaultAdminSeeded()`, `BusinessProfileRepository`, and the three specs being amended. This change amends that change's Decision E.
- `android-pos-licensing` merged — the auth gate still sits after the license `EnforcementGate`; the `PosApplication.onCreate()` seeding lands next to the existing `initialLicenseStatus` `runBlocking` read.
- `android-pos-mvp` merged — provides the `AppRoot()` boolean-swap idiom, the DI patterns, and the catalog/inventory screens that stay out of scope.

## Notes for sdd-spec / sdd-design

- **Spec amendments** must use the delta/amendment-note convention (see `permission-gate/spec.md`'s "Amendment Note" + reversed-Purpose pattern): state the original requirement, mark it reversed, and give the new requirement. Do not silently rewrite.
- **`first-run-onboarding`**: reverse from "blocking first-run flow that seeds ADMIN + captures profile before login" to "ADMIN seeded unconditionally at process start; no blocking pre-login flow." Consider whether the capability is retired entirely or restated as a "startup seeding" requirement.
- **`login-gate`**: amend "Gate Ordering Is License → Onboarding → Login" to "License → Login" (Onboarding step removed).
- **`business-profile`**: reverse "captured as part of first-run onboarding, not separately reachable" to "optional, empty by default, editable at any time via an ADMIN-only in-app action." Keep the single-row and readable-after-save requirements.
- **Empty-profile contract**: `sdd-design` should confirm no current consumer hard-requires a populated business profile and specify the nullable/empty-default UI behavior.

## Success Criteria

- [ ] A fresh install (post-license) seeds the default ADMIN at process start and presents the **login screen first** — no pre-login onboarding/business-profile wizard.
- [ ] `AuthGateState` has exactly three states (`Loading`, `Login`, `Authenticated`); `Onboarding` is gone, and `AuthGateViewModel` no longer depends on `BusinessProfileRepository`.
- [ ] A logged-in ADMIN can open a business-profile setup/edit screen from a header action next to "Manage users"; it pre-loads existing values and saves via `BusinessProfileRepository`.
- [ ] An ADMIN can use the full POS (including products/inventory) without ever setting a business profile — nothing blocks or nags on an empty profile.
- [ ] `ensureDefaultAdminSeeded()` is called unconditionally in `PosApplication.onCreate()` and remains idempotent across restarts; tests using `AppContainer.createInMemory()` seed explicitly.
- [ ] `first-run-onboarding`, `login-gate`, and `business-profile` specs are amended/reversed via the amendment-note convention; the repo no longer asserts the retired `Onboarding -> Login` ordering.
- [ ] No schema, migration, repository-contract, or products/inventory change ships in this slice.
