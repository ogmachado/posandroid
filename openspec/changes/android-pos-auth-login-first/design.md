# Design: android-pos-auth-login-first (Login-first gate; business-profile as post-login ADMIN action)

## Technical Approach

Reverses `android-pos-auth` Decision E. Seeding moves to a `runBlocking` call in `PosApplication.onCreate()` (the backend `CommandLineRunner` analogue, reusing the `initialLicenseStatus` idiom). `AuthGateState.Onboarding` is deleted; `AuthGateViewModel` drops `BusinessProfileRepository` and `onOnboardingCompleted()`. `OnboardingScreen/ViewModel` become `BusinessProfileScreen/ViewModel`, reached by a second ADMIN-only header button in `AppRoot()` using the verified `showUserManagement` boolean-swap. No schema, DAO, repository-contract, or nav-route change. Implements amended specs `login-gate`, `first-run-onboarding`, `business-profile`.

## Architecture Decisions

### Decision: Seed at `onCreate` via `runBlocking`, before the license read

**Choice**: Add `runBlocking { appContainer.authRepository.ensureDefaultAdminSeeded() }` right after `AppContainer.create(this)`, before `initialLicenseStatus`.
**Alternatives**: fire-and-forget on an app scope; seed inside `AppContainer.create()`.
**Rationale**: `Application.onCreate()` fully returns before `MainActivity.onCreate()` runs `setContent`, so a blocking seed always lands before `AuthGateViewModel.init` subscribes — no race, and the `LoginScreen` user-picker always sees the admin row. Gate correctness never depended on seeding (`session == null -> Login` regardless). The idempotent `count() > 0` guard makes the PBKDF2 cost a one-time fresh-install expense; every later boot returns immediately. Fire-and-forget would leave a briefly-empty picker with no `StateFlow` re-trigger; the DI-factory option puts a business action in wiring.

### Decision: `recompute()` becomes non-suspend; constructor drops one param

**Choice**: `AuthGateState = Loading | Login | Authenticated`. New body:
```kotlin
private fun recompute(session: AuthSession?) {
    _authGateState.value =
        if (session == null) AuthGateState.Login
        else AuthGateState.Authenticated(session.role)
}
```
Secondary ctor → `this(container.authRepository)`; drop the `businessProfileRepository` field and `onOnboardingCompleted()`. `Loading` stays as the initial `MutableStateFlow` value.
**Alternatives**: keep the field unused. **Rationale**: `exists()` was the only suspend source; removing it lets `recompute` stop being suspend. `BusinessProfileRepository` stays in `AppContainer` (used by the new `BusinessProfileViewModel`), so DI is unchanged.

### Decision: Two header buttons, mutually exclusive; rename test tags

**Choice**: Add `showBusinessProfile` alongside `showUserManagement`; two `Button`s in the existing `Row`; each toggle clears the other. New tag `BUSINESS_PROFILE_HEADER_BUTTON_TEST_TAG = "app-root-business-profile-header-button"`. Content slot becomes a `when` over the two booleans, else `AuthGate`. Rename `ONBOARDING_*_TEST_TAG` → `BUSINESS_PROFILE_*_TEST_TAG`.
**Alternatives**: `DropdownMenu` admin menu (rejected — testability, per `UserManagementScreen` KDoc); keep old tag names (rejected — misdescribes the screen).
**Rationale**: reuses the verified `MainActivity.kt:177-196` pattern; reachability enforced by the header, screen self-contains no role check.

### Decision: `BusinessProfileScreen` pre-loads via `get()`

**Choice**: Drop the `ensureDefaultAdminSeeded()` `LaunchedEffect`. Add `LaunchedEffect(Unit) { viewModel.load()?.let { name=it.name; address=it.address; phone=it.phone } }`. Add an `onClose` "Back to POS" button (mirror `UserManagementScreen`); confirm → `save()` then `onClose()`. `BusinessProfileViewModel` keeps only `businessProfileRepository`, exposing `suspend load()` + `suspend save()`.
**Rationale**: now a repeatable edit action, not one-shot capture; empty fields default when `get()` is null (Decision 5 optional-profile contract).

## Data Flow

    PosApplication.onCreate ─runBlocking─→ ensureDefaultAdminSeeded (idempotent)
                                                   │
    AuthRepository.currentSession(StateFlow) ─→ AuthGateViewModel.recompute
        null → Login (LoginScreen)   session → Authenticated (PosNavHost)
    AppRoot header (ADMIN) ─boolean-swap─→ BusinessProfileScreen ⇄ get()/save()

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `PosApplication.kt` | Modify | `runBlocking` seed before `initialLicenseStatus`; update KDoc |
| `permission/AuthGateViewModel.kt` | Modify | Remove `Onboarding`; drop repo dep + `onOnboardingCompleted()`; non-suspend `recompute` |
| `permission/AuthGate.kt` | Modify | Delete `Onboarding` branch; update KDoc |
| `permission/OnboardingScreen.kt` → `BusinessProfileScreen.kt` | Rename+Modify | Drop seed effect; add `get()` pre-load + `onClose`; rename tags |
| `permission/OnboardingViewModel.kt` → `BusinessProfileViewModel.kt` | Rename+Modify | Drop `authRepository`/seed; add `load()` |
| `MainActivity.kt` (`AppRoot`) | Modify | Second ADMIN header button + `showBusinessProfile` swap; remove `Onboarding` from KDoc |

## Interfaces / Contracts

No contract change. `BusinessProfileRepository.get()`/`save()`, `ensureDefaultAdminSeeded()`, entities, DAOs, `MIGRATION_2_3` unchanged. Empty/nullable profile is now a valid steady state.

## Testing Strategy

| File | Change |
|------|--------|
| `AuthGateTest.kt` | DELETE `noBusinessProfile_showsOnboarding_...`; drop `save(...)` preconditions from the other two (keep `ensureDefaultAdminSeeded()`); rename/drop onboarding-tag assertions |
| `OnboardingScreenTest.kt` → `BusinessProfileScreenTest.kt` | Rename; drop admin-seed assertion; VM built without `authRepository`; keep save test + ADD pre-load test; rename tags |
| `EnforcementGateTest.kt` | Trim vestigial `save(...)` in `seedBusinessProfileAndLogin*` (keep `ensureDefaultAdminSeeded()`); fix helper KDoc |
| Audit sweep | Any `createInMemory()` test composing `AuthGate`/`AppRoot`/`EnforcementGate` that reaches `Authenticated` must seed+login explicitly. `LoginScreenTest`, `UserManagementScreenTest`, `AuthRepositoryTest` already seed; `PosNavHostTest`/`EndToEndPosFlowTest` to verify. `sdd-tasks` enumerates. |

No `PosApplicationTest` exists; the seed behavior stays covered indirectly. Adding one is optional (`sdd-tasks` decides).

**Suite-wide cost note** (found during gate review): no test overrides `@Config(application = ...)`, so Robolectric's default manifest-driven `PosApplication` resolution applies to every Robolectric test class in this module, not just the ones audited above. With `forkEvery = 1` (`app/build.gradle.kts:169`, fresh JVM per class), the new `runBlocking { ensureDefaultAdminSeeded() }` call — including its PBKDF2 warm-up cost, already flagged in `AuthGateTest.kt`/`OnboardingScreenTest.kt` comments as needing generous timeouts — now runs once per test class's implicit app bootstrap, against a separate on-disk DB nothing asserts against. Not a correctness risk (isolated DB, no assertion touches it), but a real suite-wide execution-time cost worth being aware of; no action required unless total suite runtime becomes a problem.

## Migration / Rollout

No migration required. Rollback = revert commits; tables and `MIGRATION_2_3` untouched, seeded admin/profile survive.

## Open Questions

- [ ] Confirmed: no consumer hard-requires a populated profile — `grep(businessProfileRepository|BusinessProfileEntity)` across `main/` hits only `business/`, `core/di`, `core/db`, and the two permission ViewModels. No sales/receipt/print reference. Empty profile is safe.
