# Tasks: android-pos-auth-login-first (Login is the first screen; business-profile becomes a post-login ADMIN action)

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~250-350 (6 production files modified/renamed: `PosApplication.kt` +1/-0 line, `AuthGateViewModel.kt` simplified ~-15/+8, `AuthGate.kt` -1 branch, `OnboardingScreen.kt`→`BusinessProfileScreen.kt` renamed + `onClose`/pre-load added ~+20, `OnboardingViewModel.kt`→`BusinessProfileViewModel.kt` renamed + `load()` added ~+10, `MainActivity.kt` second header button ~+15; 4 test files modified: `AuthGateTest.kt` -1 test/-2 preconditions, `OnboardingScreenTest.kt`→`BusinessProfileScreenTest.kt` renamed/split + 1 new pre-load test ~+30, `EnforcementGateTest.kt` trim ~-4 lines; no new production classes, no schema/migration/repository-contract change) |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Delivery strategy | ask-on-risk (default) — single PR appropriate at this estimated size |
| Chain strategy | n/a (single PR) |

**Decision needed before apply: No** — estimated changed lines are well under the ~400-line review budget; this ships as a single PR. This is a gating/UI/startup-wiring reversal of a previously documented decision (`android-pos-auth` Decision E), not new schema/repository work — every file in scope is `Modify`/`Rename`, nothing new is created except the renamed screen/viewmodel pair keeping their existing shape.

Rationale for "No chaining": no Room schema/migration/DAO/repository-contract change (Decision in proposal.md "Out of Scope"); the 6 production files are a mechanical seed-relocation + gate-simplification + one rename + one new header button, all logically coupled (splitting them would leave intermediate commits that don't compile — e.g. renaming the screen without updating `AuthGate.kt`'s import breaks the build); the 4 test files are equally coupled 1:1 with the production files they cover. A single reviewer can hold "gate ordering + one rename + one header button" in their head at once; this does not match the auth/security/payments hot-path trigger for a mandatory 4R review fan-out on its own, though `permission/` touches warrant at least `review-readability` and `review-risk` per this repo's own trigger rules — call that out at PR time, not here.

---

## Phase 1: Production Code (sequential — each task's edit is a precondition for the next)

- [x] 1.1 `PosApplication.kt` — add `appContainer.authRepository.ensureDefaultAdminSeeded()` inside the existing `runBlocking` immediately after `appContainer = AppContainer.create(this)` and before the `initialLicenseStatus` read, per design.md's exact snippet:
  ```kotlin
  override fun onCreate() {
      super.onCreate()
      appContainer = AppContainer.create(this)
      runBlocking { appContainer.authRepository.ensureDefaultAdminSeeded() }
      initialLicenseStatus = runBlocking { appContainer.licenseRepository.currentStatus() }
  }
  ```
  Update the class KDoc to describe the unconditional startup seed (mirrors the backend's `SecurityBootstrap` `CommandLineRunner`, per proposal Decision 1) instead of relying on `OnboardingScreen`'s `LaunchedEffect`. Satisfies `first-run-onboarding`'s amended "Default ADMIN Is Auto-Seeded Unconditionally At Process Start."

- [x] 1.2 `permission/AuthGateViewModel.kt` — drop the `businessProfileRepository: BusinessProfileRepository` constructor param (and its import), drop `onOnboardingCompleted()`, remove `Onboarding` from `AuthGateState`, and make `recompute()` non-suspend per design.md's exact snippet:
  ```kotlin
  private fun recompute(session: AuthSession?) {
      _authGateState.value =
          if (session == null) AuthGateState.Login
          else AuthGateState.Authenticated(session.role)
  }
  ```
  Update the secondary constructor to `this(container.authRepository)` and the `init` block's `collect` call site accordingly. Update the class/file KDoc (drop the "two sources" / `onOnboardingCompleted` references). Satisfies `login-gate`'s amended "Gate Sits Inside AppRoot, After License, Before PosNavHost" (no `Onboarding` step) and the sealed-interface shape `Loading | Login | Authenticated`.

- [x] 1.3 `permission/AuthGate.kt` — delete the `AuthGateState.Onboarding -> OnboardingScreen(...)` branch from the `when`; update the KDoc (drop the "3-way onboarding/login/authenticated" framing, drop the `onOnboardingCompleted` cross-reference) to describe the 2-way `Login | Authenticated` swap. Depends on 1.2 (the `Onboarding` state no longer exists on `AuthGateState`, so this branch would not compile otherwise).

- [x] 1.4 Rename `permission/OnboardingScreen.kt` → `permission/BusinessProfileScreen.kt` (composable `OnboardingScreen` → `BusinessProfileScreen`; test tags `ONBOARDING_*_TEST_TAG` → `BUSINESS_PROFILE_*_TEST_TAG` per design.md Decision 3) and `permission/OnboardingViewModel.kt` → `permission/BusinessProfileViewModel.kt` (class `OnboardingViewModel` → `BusinessProfileViewModel`), together as one task since they are one screen+viewmodel pair:
  - `BusinessProfileViewModel`: drop the `authRepository` field, drop `ensureDefaultAdminSeeded()`; keep `businessProfileRepository` only; add `suspend fun load(): BusinessProfileEntity?` delegating to `businessProfileRepository.get()`; keep `suspend fun save(...)` (renamed from `saveBusinessProfile` if design.md's naming implies it — otherwise keep the existing name, no functional need to rename it).
  - `BusinessProfileScreen`: drop the `LaunchedEffect(Unit) { viewModel.ensureDefaultAdminSeeded() }`; add `LaunchedEffect(Unit) { viewModel.load()?.let { name = it.name; address = it.address; phone = it.phone } }` to pre-load existing values; add an `onClose: () -> Unit = {}` param and a "Back to POS" button mirroring `UserManagementScreen`'s pattern; confirm button calls `viewModel.save(name, address, phone)` then `onClose()` (replaces the old `onComplete` callback — `AuthGate` no longer calls this screen at all, so `onComplete`/`AuthGate` wiring is fully removed, not just renamed).
  - Update the file/class KDoc: this is now a repeatable, optional, post-login ADMIN action (`business-profile`'s amended "Optional And Editable At Any Time"), not first-run onboarding.

- [x] 1.5 `MainActivity.kt` (`AppRoot()`) — add a second ADMIN-only header button next to "Manage users" per design.md Decision (Two header buttons, mutually exclusive):
  - Add `var showBusinessProfile by remember { mutableStateOf(false) }` alongside `showUserManagement`.
  - Add a second `Button` in the existing header `Row`, tagged `BUSINESS_PROFILE_HEADER_BUTTON_TEST_TAG = "app-root-business-profile-header-button"`, toggling `showBusinessProfile` and clearing `showUserManagement` (and vice versa on the existing button) so the two are mutually exclusive.
  - Change the content-slot `if/else` into a `when` over the two booleans: `showUserManagement -> UserManagementScreen(...)`, `showBusinessProfile -> BusinessProfileScreen(onClose = { showBusinessProfile = false })`, `else -> AuthGate(...)`.
  - Update the class KDoc: drop the "Onboarding → Login → PosNavHost" gate-stack description (now `Login → PosNavHost`), and document the second header action alongside the existing "ADMIN-only user-management header action" paragraph.
  - Depends on 1.3 (AuthGate's simplified 2-way swap) and 1.4 (the renamed `BusinessProfileScreen`/`onClose` param).

- [x] 1.6 Repo-wide grep for remaining `Onboarding` references outside the files already touched (e.g. stray imports, comments in other files referencing `AuthGateState.Onboarding`, `OnboardingScreen`, `OnboardingViewModel`) and fix or annotate each — this is the "naming churn" risk from proposal.md's Risks table (Decision 2). Do not leave a dangling reference to a deleted state/renamed class anywhere in `app/src/main` or `app/src/test`.

## Phase 2: Test Remediation (one task per file, per design.md's Testing Strategy table)

- [x] 2.1 `permission/AuthGateTest.kt`:
  - DELETE `noBusinessProfile_showsOnboarding_posShellUnreachable` entirely (no `Onboarding` state remains to exercise; its `ONBOARDING_NAME_FIELD_TEST_TAG`/`BOTTOM_NAV_TEST_TAG` assertions have no reachable code path anymore).
  - In `businessProfileExists_noSession_showsLogin_notOnboardingOrPosShell` (consider renaming to `noSession_showsLogin_notPosShell` since "businessProfileExists"/"notOnboarding" no longer describe a precondition that matters): drop the `container.businessProfileRepository.save(...)` precondition (business-profile existence no longer gates anything) and the `ONBOARDING_NAME_FIELD_TEST_TAG` assertDoesNotExist line (nothing renders that tag on this path anymore); keep `ensureDefaultAdminSeeded()` and the user-picker/`BOTTOM_NAV_TEST_TAG` assertions.
  - In `authenticatedSession_showsPosShell`: drop the `container.businessProfileRepository.save(...)` precondition; keep `ensureDefaultAdminSeeded()` + `login(...)` + the `BOTTOM_NAV_TEST_TAG` assertion.
  - Update the class KDoc (drop "onboarding/login/authenticated derivation" framing → "login/authenticated derivation").
  - Depends on 1.2/1.3 (compiles only once `Onboarding` is gone and `OnboardingScreen`'s test tags are no longer imported here).

- [x] 2.2 Rename `permission/OnboardingScreenTest.kt` → `permission/BusinessProfileScreenTest.kt`:
  - Rename the class `OnboardingScreenTest` → `BusinessProfileScreenTest`.
  - Build the `viewModel` as `BusinessProfileViewModel(businessProfileRepository)` only — drop the `authRepository`/`AuthRepository(...)` setup entirely from this file (it is no longer a `BusinessProfileViewModel` constructor dependency per task 1.4).
  - Split `confirmingOnboarding_seedsDefaultAdmin_persistsProfileOnce_andNotifiesCompleteExactlyOnce`: drop the admin-seeding assertions (`db.userDao().count() > 0` wait, `admin` lookup/role assertions) since this screen no longer seeds anything; keep the save-profile assertions (rename to something like `savingProfile_persistsNameAddressPhone`), replacing the `onComplete` callback assertions with an `onClose` callback assertion consistent with task 1.4's new param.
  - ADD a new pre-load test (e.g. `existingProfile_preLoadsIntoFields`): GIVEN a business profile already saved via the repository directly, WHEN `BusinessProfileScreen` composes, THEN the name/address/phone fields show the existing saved values (per design.md's pre-load `LaunchedEffect` and the amended `business-profile` spec's "opens ... pre-loaded with any existing saved values" scenario).
  - Rename all `ONBOARDING_*_TEST_TAG` references to `BUSINESS_PROFILE_*_TEST_TAG`.
  - Update the class KDoc cross-reference to `AuthGateTest` (the "not re-prompted after first capture" comment no longer applies — this screen is reachable at any time, not gated).
  - Depends on 1.4 (renamed production classes/tags this test imports).

- [x] 2.3 `EnforcementGateTest.kt` — trim the now-vestigial `container.businessProfileRepository.save(...)` line from both `seedBusinessProfileAndLogin(container)` and `seedBusinessProfileAndLoginAsCashier(container)` helpers (business-profile existence no longer affects gate reachability); keep `ensureDefaultAdminSeeded()` and `login(...)`/`createUser(...)` in both. Update each helper's KDoc — the "no such background coroutine racing `container.database.close()`" rationale (previously about `OnboardingScreen`'s `LaunchedEffect`) no longer applies verbatim; note instead that seeding+login still needs to happen explicitly because `AppContainer.createInMemory()` bypasses `PosApplication.onCreate()`'s new unconditional seed call (task 1.1). Consider renaming the two helpers (`seedBusinessProfileAndLogin*` → `seedAdminAndLogin*` / `seedCashierAndLogin*`) since "business profile" is no longer part of what they set up — optional, mechanical, do only if it doesn't inflate the diff unnecessarily.

- [x] 2.4 No-op confirmation task (explicitly recorded so it is not rediscovered as an open question): `PosNavHostTest.kt` and `EndToEndPosFlowTest.kt` need **NO changes**. Design confirmed neither test composes `AuthGate`, `AppRoot`, or `EnforcementGate` — `PosNavHostTest` drives `PosNavHost` directly (already passing `role = UserRole.ADMIN`), and `EndToEndPosFlowTest` (per its existing setup) does not go through the gate stack either. Nothing in this change's seed-relocation, gate-simplification, or rename touches either file's compile surface or preconditions. Verify this holds during 3.1's full-suite run; if either file unexpectedly fails, treat that as a signal the "no changes needed" assumption in design.md was wrong and re-open this task — do not silently patch around it.

## Phase 3: Verification

- [x] 3.1 Run the scoped strict-TDD test command `./gradlew testDebugUnitTest` (per `openspec/config.yaml` `apply.test_command`) and confirm:
  - All of `AuthGateTest`, `BusinessProfileScreenTest`, `EnforcementGateTest`, `PosNavHostTest`, `EndToEndPosFlowTest`, and the full suite pass with no regressions.
  - Walk proposal.md's Success Criteria checklist item by item and confirm each is now true in code:
    - [x] Fresh install (post-license) seeds default ADMIN at process start and presents the login screen first — no pre-login onboarding/business-profile wizard.
    - [x] `AuthGateState` has exactly three states (`Loading`, `Login`, `Authenticated`); `Onboarding` is gone; `AuthGateViewModel` no longer depends on `BusinessProfileRepository`.
    - [x] A logged-in ADMIN can open a business-profile setup/edit screen from a header action next to "Manage users"; it pre-loads existing values and saves via `BusinessProfileRepository`.
    - [x] An ADMIN can use the full POS (including products/inventory) without ever setting a business profile — nothing blocks or nags on an empty profile.
    - [x] `ensureDefaultAdminSeeded()` is called unconditionally in `PosApplication.onCreate()` and remains idempotent across restarts; tests using `AppContainer.createInMemory()` seed explicitly.
    - [x] `first-run-onboarding`, `login-gate`, and `business-profile` specs are amended/reversed via the amendment-note convention (already done in the spec phase — confirm no further code drift contradicts them).
    - [x] No schema, migration, repository-contract, or products/inventory change shipped in this slice.
  - If `./gradlew assembleDebug` is convenient to run alongside (per `openspec/config.yaml` `verify.build_command`), run it too to confirm no lingering `Onboarding`/`OnboardingScreen`/`OnboardingViewModel` reference broke the release compile path.
