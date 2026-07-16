# Exploration: android-pos-auth-login-first — reorder gate to Login-first, business-profile becomes a post-login ADMIN action

Follow-up amendment to the archived `android-pos-auth` change. See `openspec/changes/android-pos-auth/{design.md,archive-report.md}` for that change's full history.

## Current State (re-verified by reading the files)

`AuthGateViewModel.recompute()` (`app/src/main/java/com/idos/pos/permission/AuthGateViewModel.kt:64-69`):
```kotlin
_authGateState.value = when {
    !businessProfileRepository.exists() -> AuthGateState.Onboarding
    session == null -> AuthGateState.Login
    else -> AuthGateState.Authenticated(session.role)
}
```
`AuthGateState` (same file, line 18-23) is a sealed interface `Loading | Onboarding | Login | Authenticated(role)`. `AuthGate.kt` renders `OnboardingScreen(onComplete = viewModel::onOnboardingCompleted)` for the `Onboarding` branch.

`OnboardingScreen.kt` seeds the default ADMIN via a `LaunchedEffect(Unit) { viewModel.ensureDefaultAdminSeeded() }` scoped to the screen's own composition (line 49-51) — i.e. seeding only happens once this screen is reached, which itself only happens while `businessProfileRepository.exists() == false`. `OnboardingViewModel.ensureDefaultAdminSeeded()` just forwards to `AuthRepository.ensureDefaultAdminSeeded()`.

`AuthRepository.ensureDefaultAdminSeeded()` (`permission/AuthRepository.kt:31-48`) is **already fully idempotent** — checks `userDao.count() > 0` before inserting — so it is safe to call unconditionally, from anywhere, on every process start. No change needed to this method itself; only its call site needs to move.

`BusinessProfileRepository.save()` (`business/BusinessProfileRepository.kt`) already upserts via `OnConflictStrategy.REPLACE`, so it already supports being called more than once / edited later — confirmed by `BusinessProfileRepositoryTest.save_calledTwice_neverProducesASecondRow`. **No repository-layer change needed** for either repository — this is purely a gating/UI/wiring change.

`MIGRATION_2_3` (`core/db/Migrations.kt:54+`) only creates the `app_user`/`business_profile` table schema via raw SQL — it does not seed any row. No migration risk from moving the seed call.

`AppRoot()` (`MainActivity.kt:166-199`) already establishes the exact reusable pattern this change needs: an ADMIN-only header `Button` that boolean-swaps the content `Box` between `AuthGate` and `UserManagementScreen`, driven by a local `showUserManagement` state and `currentRole == UserRole.ADMIN` (derived from the same `AuthGateViewModel` instance via `posViewModel` caching). `UserManagementScreen.kt` itself does zero role-checking internally — reachability is enforced entirely by the header action, not the screen.

`PosApplication.onCreate()` (`PosApplication.kt:38-42`) already establishes the "unconditional startup action" precedent this change needs: `initialLicenseStatus = runBlocking { appContainer.licenseRepository.currentStatus() }` runs synchronously right after `AppContainer.create(this)`, before `MainActivity` ever composes — the exact idiom needed for admin-seeding-as-`CommandLineRunner`.

## Affected Areas

- `app/src/main/java/com/idos/pos/permission/AuthGateViewModel.kt` — remove `Onboarding` branch from `recompute()`; drop `businessProfileRepository` dependency and `onOnboardingCompleted()` entirely (no longer needed once gating stops depending on profile existence).
- `app/src/main/java/com/idos/pos/permission/AuthGate.kt` — remove the `AuthGateState.Onboarding -> OnboardingScreen(...)` branch; `AuthGateState` becomes `Loading | Login | Authenticated`.
- `app/src/main/java/com/idos/pos/permission/OnboardingScreen.kt` + `OnboardingViewModel.kt` — repurpose into a `BusinessProfileScreen`/`BusinessProfileViewModel` (or rename in place) that: (a) drops the `ensureDefaultAdminSeeded()` `LaunchedEffect` entirely, (b) keeps the name/address/phone form bound to `BusinessProfileRepository.save()`, (c) should pre-load existing values via `businessProfileRepository.get()` since it's now reachable repeatedly as an edit action, not a one-shot capture-and-freeze screen.
- `app/src/main/java/com/idos/pos/PosApplication.kt` — add the unconditional admin-seed call at startup (the `CommandLineRunner` equivalent), most naturally alongside the existing `initialLicenseStatus` `runBlocking` call.
- `app/src/main/java/com/idos/pos/MainActivity.kt` (`AppRoot()`) — add a second ADMIN-only header action (alongside "Manage users") that boolean-swaps to the new business-profile screen, following the exact same pattern as `showUserManagement`.
- `app/src/test/java/com/idos/pos/permission/AuthGateTest.kt` — `noBusinessProfile_showsOnboarding_posShellUnreachable` no longer has a scenario to test; the other two tests' `container.businessProfileRepository.save(...)` preconditions become irrelevant to gate reachability and should be dropped. **Critical**: these tests build `AppContainer.createInMemory()` directly, bypassing `PosApplication.onCreate()` — if admin-seeding moves to `PosApplication`, tests using `AppContainer` directly will no longer get free seeding and MUST call `container.authRepository.ensureDefaultAdminSeeded()` explicitly in `setUp` (mirroring the pattern already used in 8 other test files).
- `app/src/test/java/com/idos/pos/permission/OnboardingScreenTest.kt` — needs a full split: business-profile-capture assertions move to a new `BusinessProfileScreenTest`; the admin-seeding assertion no longer applies to this screen.
- `app/src/test/java/com/idos/pos/EnforcementGateTest.kt` — `seedBusinessProfileAndLogin`/`seedBusinessProfileAndLoginAsCashier` helpers already call `ensureDefaultAdminSeeded()` explicitly before login (remains necessary); `businessProfileRepository.save(...)` calls become vestigial but harmless.
- `openspec/changes/android-pos-auth/specs/first-run-onboarding/spec.md`, `.../business-profile/spec.md`, `.../login-gate/spec.md` — need delta/amendment specs for this change.

## Files confirmed UNaffected (verified directly)
- `PosNavHostTest.kt` — no match for `Onboarding|businessProfile|ensureDefaultAdminSeeded`; renders `PosNavHost` directly, never through `AuthGate`.
- `LoginScreenTest.kt` — only calls `ensureDefaultAdminSeeded()` directly; tests `LoginScreen` standalone, not through `AuthGate`.
- `UserManagementScreenTest.kt` — same pattern as `LoginScreenTest`; unaffected.
- `BusinessProfileRepository` / `BusinessProfileRepositoryTest` / `AuthRepository`'s seeding logic itself — no code changes needed, only call-site relocation.

## Approaches

1. **Seed at `PosApplication.onCreate()`, remove `Onboarding` gate state, business-profile becomes an ADMIN header action (like `UserManagementScreen`)** — recommended
   - Mirrors the reference backend's `CommandLineRunner` most literally.
   - Reuses the exact `initialLicenseStatus` `runBlocking` idiom already established.
   - `AuthGateViewModel` gets simpler (drops a dependency + a method).
   - Business-profile setup gets full ADMIN-editable semantics for free.
   - Cons: every Robolectric test building `AppContainer.createInMemory()` directly needs an explicit `ensureDefaultAdminSeeded()` call — continuation of an existing pattern, but easy to miss one file.
   - Effort: Medium.

2. **Seed inside `AppContainer.create()`/`createInMemory()` factories themselves**
   - Every container gets admin auto-seeded for free, removing test-setup burden.
   - Diverges slightly from the literal `CommandLineRunner`-at-boot analogy; puts business logic in `core/di/**`, which this codebase's own conventions treat as "wiring, not business logic."
   - Effort: Medium.

3. **Keep `AuthGateState.Onboarding`, retarget its trigger to a post-login nag** — rejected; contradicts the explicit requirement that Login is always first.

## Recommendation

Approach 1 — seed unconditionally in `PosApplication.onCreate()`, remove `AuthGateState.Onboarding` entirely (gate becomes `Loading | Login | Authenticated`), turn business-profile setup into a second ADMIN-only header action in `AppRoot()` next to "Manage users."

Open question for `sdd-propose`: whether to rename `OnboardingScreen`/`OnboardingViewModel` to `BusinessProfileScreen`/`BusinessProfileViewModel` (recommended — the "onboarding" concept is retired) vs. keep names and only change behavior.

## Risks

- **Test seeding assumption break**: any Robolectric test building `AppContainer.createInMemory()` directly and expecting the admin to already exist purely from gate/screen composition must add an explicit `ensureDefaultAdminSeeded()` call — mechanical but easy to miss one file (confusing "empty user list" failure instead of a compile error).
- **Spec reversal needed, not just new specs**: `first-run-onboarding/spec.md` and `login-gate/spec.md`'s gate-ordering requirements are directly contradicted — need explicit amendment/reversal (same pattern already used for `permission-gate/spec.md`), not just a fresh delta layered on top. `business-profile/spec.md`'s "captured as part of first-run onboarding" requirement is also reversed.
- **No existing `PosApplicationTest.kt`** — no test harness at the exact layer where the new seed call would live; `sdd-tasks`/`sdd-apply` should decide whether to add one.
- **Two ADMIN header actions may need a UX decision** — `AppRoot()`'s header currently holds one button; adding a second is a small layout decision. `UserManagementScreen`'s KDoc explicitly avoids `DropdownMenu` for testability, so a dropdown-based "admin menu" is likely off the table too.
- **Naming churn** if `OnboardingScreen`/`OnboardingViewModel` are renamed — every reference (imports, KDoc cross-references in `AuthGate.kt`, `MainActivity.kt`) needs updating.

## Ready for Proposal

Yes. Narrow, well-bounded fix space: no schema/migration change, no repository change, a net simplification of `AuthGateViewModel`, and a screen relocation reusing an already-established pattern. Main proposal-phase decisions: (a) `PosApplication.onCreate()` vs. `AppContainer` factory as seed call site, (b) whether to rename `OnboardingScreen`/`OnboardingViewModel`, (c) exact spec-amendment wording for the three affected specs.
