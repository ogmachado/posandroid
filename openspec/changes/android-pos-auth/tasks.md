# Tasks: android-pos-auth (local multi-user login, ADMIN/CASHIER roles, first-run onboarding)

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~2000-3200 (2 Room tables + migration + 2 repos + 2 DAOs + `AuthRepository` + 4 new screens/viewmodels (`AuthGate`, `Login`, `Onboarding`, `UserManagement`) + `PinGate` suspend-seam retarget + `PinRepository` deletion + tab-role filtering + 2 spec/doc amendments, each with matching tests) |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 → PR 4 (see Suggested Work Units) |
| Delivery strategy | ask-on-risk (default) |
| Chain strategy | pending |

**Decision needed before apply: Yes** — this forecast flags high risk of exceeding the ~400-line review budget. Per `ask-on-risk`, the orchestrator must stop and ask whether to split into chained/stacked PRs (and which chain strategy) or proceed under `size:exception` before launching `sdd-apply`.

Chained PRs recommended: Yes
400-line budget risk: High
Chain strategy: pending

This is a from-scratch device-local identity subsystem: 2 additive Room tables, a schema migration, credential hashing reuse, an async verifier-signature change to an existing shared class (`PinGate`), 4 new screens, and 2 cross-file spec/doc amendments reversing previously documented decisions. One PR would bury a reviewer in unrelated concerns (schema/migration correctness vs. gate-composition UX vs. a breaking constructor-signature change vs. documentation reconciliation). Chaining is recommended at this size, matching `android-pos-licensing`'s precedent.

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Schema + strict-TDD core: `UserDao`/`BusinessProfileDao`/`AuthRepository`/`BusinessProfileRepository` + `MIGRATION_2_3` (Phase 1) | PR 1 | No UI; base for all. Strict TDD per `config.yaml` (`**/*Repository.kt`, `**/*Dao.kt`, `core/db/**`, `core/domain/**` already cover every file here — no `config.yaml` edit needed) |
| 2 | `AuthGate` + onboarding + login + session wiring (Phase 2) | PR 2 | Depends on PR 1 |
| 3 | Tab role gating + `permission-gate` seam retarget + `PinRepository` retirement (Phase 3) | PR 3 | Depends on PR 1, PR 2. **Must include the `PinGateDialogTest.kt` signature fix in the same commit as the `PinGate.kt` change (task 3.2) — the build does not compile otherwise.** |
| 4 | User-management + credential-change + spec/doc amendments (Phase 4) | PR 4 | Depends on PR 1, PR 2, PR 3 |

## Phase 0: Design Doc Correction (mechanical, precedes PR 1)

- [x] 0.1 Correct `design.md`'s File Changes table: `permission/PinHasher.kt` is currently marked Strict TDD `"Yes (kept)"`. Per `openspec/config.yaml`'s `strict_tdd_scope.include` (`**/*Repository.kt`, `**/*Dao.kt`, `core/db/**`, `core/domain/**`, `licensing/**`), `PinHasher.kt` matches none of these globs — it is a plain utility class, `Unchanged` in this design. Update the row to reflect "No (not in `strict_tdd_scope`; unchanged utility, already covered by its existing JVM test)". Do not treat `PinHasher.kt` as strict-TDD-scoped work in any downstream task — it is not modified at all in this change.

## Phase 1: Schema + Strict-TDD Core (Work Unit 1 / PR 1)

- [x] 1.1 `permission/UserRole.kt` — `ADMIN`/`CASHIER` enum (satisfies `user-identity` "exactly one role")
- [x] 1.2 `permission/UserEntity.kt` — `app_user` table (`id` autogen PK, `username` unique index, `role` TEXT, `pinSalt` BLOB, `pinHash` BLOB)
- [x] 1.3 `business/BusinessProfileEntity.kt` — `business_profile` single-row table (`id = SINGLE_ROW_ID = 1`, `name`/`address`/`phone` TEXT)
- [x] 1.4 RED: `UserDaoTest.kt` (Robolectric + in-mem Room) — insert/find-by-username/find-by-role/find-all/update-pin; unique-username constraint rejects a duplicate insert (`user-identity` "no shared credential")
- [x] 1.5 GREEN: `permission/UserDao.kt` — `count()`, `findByRole(role)`, `findByUsername(username)`, `findAll()`, `insert(user)` (`OnConflictStrategy.ABORT`), `updatePin(id, salt, hash)`
- [x] 1.6 RED: `BusinessProfileDaoTest.kt` (Robolectric + in-mem Room) — `find()` returns null before capture, non-null after `upsert`; a second `upsert` call REPLACEs the single row rather than creating a second one (`business-profile` "no second row")
- [x] 1.7 GREEN: `business/BusinessProfileDao.kt` — `find(id)`, `upsert(entity)` (`OnConflictStrategy.REPLACE`)
- [x] 1.8 RED: `MIGRATION_2_3` test in `LicenseStateDaoTest.kt`'s migration-test class (or a sibling test class following the same pattern) — `MigrationTestHelper.runMigrationsAndValidate(TEST_DB_NAME, 3, true, MIGRATION_2_3)` against a v2 fixture, asserting it applies cleanly and matches the exported `3.json` schema
- [x] 1.9 GREEN: `core/db/Migrations.kt` — add `MIGRATION_2_3` (structural only, no seed rows per Decision B): `CREATE TABLE app_user (...)`, `CREATE UNIQUE INDEX index_app_user_username`, `CREATE TABLE business_profile (...)` exactly as specified in `design.md`'s Room schema section
- [x] 1.10 `core/db/PosDatabase.kt` — bump `version = 3`; register `UserEntity`/`BusinessProfileEntity` in `@Database(entities = [...])` and add `abstract fun userDao(): UserDao`, `abstract fun businessProfileDao(): BusinessProfileDao`
- [x] 1.11 Generate and commit `app/src/main/assets/com.idos.pos.core.db.PosDatabase/3.json` (Room export, checked in like `1.json`/`2.json` — actual `room.schemaLocation` per `app/build.gradle.kts` task 2.6 is `src/main/assets`, not the `app/schemas` path this task text names; see Deviations)
- [x] 1.12 `core/domain/DomainError.kt` — add `DuplicateUsername(username: String)` and `BlankPin` variants (covered incidentally by Phase 1 repository tests, per `design.md` Testing Strategy — no standalone test required)
- [x] 1.13 RED: `AuthRepositoryTest.kt` (Robolectric + in-mem Room, fake/real `PinHasher`) — `ensureDefaultAdminSeeded()` seeds `admin`/`admin123` exactly once and is idempotent on repeat calls; `login(username, pin)` succeeds only for the matching user's own hash and fails for another user's PIN; `verifyAdminPin(pin)` succeeds for any `ADMIN`-role row and fails for a `CASHIER`-role row with the same PIN; `createUser` rejects a duplicate username (`DomainError.DuplicateUsername`) and a blank PIN (`DomainError.BlankPin`); `currentSession` StateFlow is null before login, set after, and survives no persisted storage
- [x] 1.14 GREEN: `permission/AuthSession.kt` (`AuthSession(userId, username, role)`) + `permission/AuthRepository.kt` — `ensureDefaultAdminSeeded()` (idempotent, best-effort `context.deleteSharedPreferences("idos-pos-pin-prefs")` per Decision D), `login()`, `verifyAdminPin()`, `createUser()`, `changePin()`, `currentSession: StateFlow<AuthSession?>`, `DEFAULT_ADMIN_USERNAME`/`DEFAULT_ADMIN_PIN` companion constants
- [x] 1.15 RED: `BusinessProfileRepositoryTest.kt` — `exists()` false before capture / true after; `get()` returns captured values; `save()` called twice never produces a second row
- [x] 1.16 GREEN: `business/BusinessProfileRepository.kt` — `exists()`, `get()`, `save(name, address, phone)`
- [x] 1.17 Wire `userDao`/`businessProfileDao`/`authRepository`/`businessProfileRepository` into `core/di/AppContainer.kt` (lazy pattern, in both `create()` and `createInMemory()`); register `MIGRATION_2_3` on the production `Room.databaseBuilder` call in `create()` only. Do **not** remove `pinRepository` yet — Phase 3 retires it once its call sites are retargeted.

## Phase 2: AuthGate + Onboarding + Login + Session Wiring (Work Unit 2 / PR 2)

- [x] 2.1 `permission/AuthGateViewModel.kt` (standard mode, `*ViewModel.kt` excluded from strict TDD) — derives `AuthGateState` (`Onboarding` / `Login` / `Authenticated(role)`) from `businessProfileRepository.exists()` and `authRepository.currentSession`
- [x] 2.2 `permission/AuthGate.kt` (standard mode) — 3-way `when(authGateState)` composable: `Onboarding → OnboardingScreen`, `Login → LoginScreen`, `Authenticated(role) → PosNavHost(role)` (this fully replaces the direct `PosNavHost()` call inside `AppRoot()`) — **deviation**: calls the existing no-arg `PosNavHost()` for now since the `role`-aware overload is Phase 3 task 3.4 (out of scope for this batch); see `AuthGate.kt`'s class doc
- [x] 2.3 `permission/OnboardingScreen.kt` + `OnboardingViewModel.kt` (standard mode) — `LaunchedEffect` calls `authRepository.ensureDefaultAdminSeeded()`; captures business name/address/phone; on confirm calls `businessProfileRepository.save(...)` then advances state (`first-run-onboarding`, `business-profile`)
- [x] 2.4 `permission/LoginScreen.kt` + `LoginViewModel.kt` (standard mode) — user picker + PIN pad UX (per `design.md`'s chosen UX); calls `authRepository.login(username, pin)`; on success the session becomes authenticated (`login-gate`)
- [x] 2.5 `MainActivity.kt` — `AppRoot()` now renders `AuthGate()` in place of the direct `PosNavHost()` call (Decision E gate stack: `License → Onboarding → Login → PosNavHost`)
- [x] 2.6 Compose UI tests (alongside, standard mode): `AuthGateTest` — no business profile → Onboarding shown, POS shell unreachable; profile exists + no session → Login shown; session set → `PosNavHost` shown. `OnboardingScreenTest` — completing onboarding seeds the default admin and persists the profile exactly once (not-re-prompted-on-relaunch covered at the `AuthGate` level in the same test class). `LoginScreenTest` — correct user+PIN authenticates and reveals the role; incorrect PIN rejects and keeps the login screen shown (`login-gate` scenarios). Also fixed a pre-existing `EnforcementGateTest` regression this wiring exposed (see Deviations).

## Phase 3: Tab Role Gating + permission-gate Seam Retarget + PinRepository Retirement (Work Unit 3 / PR 3)

- [ ] 3.1 `permission/PinGate.kt` — change the injected verifier from `(String) -> Boolean` to `suspend (String) -> Boolean`; add a `scope: CoroutineScope` constructor param; `submit(pin)` launches the suspend check in `scope` (Decision G)
- [ ] 3.2 **Same PR/commit as 3.1 — mandatory, build-breaking otherwise**: update `app/src/test/java/com/idos/pos/permission/PinGateDialogTest.kt` for the new suspend constructor signature. The current file constructs `PinGate(verifyPin = { it == correctPin })` using the old single-arg synchronous constructor and will fail to compile against the new signature. Rewrite using `kotlinx-coroutines-test` (`TestScope`/`runTest`), providing a suspend lambda `verifyPin = { it == correctPin }` plus the required `scope` argument, and await/advance the async check before each assertion (`gate.isVisible`, `actionRan`). Preserve all three existing scenarios (correct PIN admits, incorrect PIN blocks + shows error, two gated actions in a row each require a fresh PIN).
- [ ] 3.3 `rememberPinGate(authRepository: AuthRepository): PinGate` — `scope = rememberCoroutineScope()`, wires `authRepository::verifyAdminPin` as the suspend verifier (replacing the old `rememberPinGate(pinRepository: PinRepository)` overload)
- [ ] 3.4 `nav/PosNavHost.kt` — add `role: UserRole` parameter to `PosNavHost(role)`; add `visibleTabsFor(role)` transform filtering `posTabs` (CASHIER → Venta/Caja; ADMIN → all four); update the 3 existing `rememberPinGate(LocalAppContainer.current.pinRepository)` call sites to `rememberPinGate(LocalAppContainer.current.authRepository)`
- [ ] 3.5 Delete `permission/PinRepository.kt` (Decision D — discard-and-delete, no value import); remove `pinRepository` from `core/di/AppContainer.kt`
- [ ] 3.6 Verify `AuthRepository.ensureDefaultAdminSeeded()` (built in task 1.14) performs the best-effort `context.deleteSharedPreferences("idos-pos-pin-prefs")` cleanup on the seeding run — do not leave the orphaned prefs file reachable (`user-identity` "no parallel store consulted")
- [ ] 3.7 Test (alongside, standard mode): `visibleTabsFor(UserRole.CASHIER)` returns exactly `[Venta, Caja]`; `visibleTabsFor(UserRole.ADMIN)` returns all four tabs in the existing order (`role-based-navigation` scenarios)
- [ ] 3.8 Regression pass (alongside): existing `permission-gate` scenarios still hold end-to-end through the new suspend seam — any-`ADMIN` PIN allows price-edit/`ADJUST`, a `CASHIER`'s own PIN does not satisfy the gate, no session carry-over between two gated actions in a row (`permission-gate` amendment scenarios)

## Phase 4: User Management + Credential Change + Spec/Doc Amendments (Work Unit 4 / PR 4)

- [ ] 4.1 `permission/UserManagementScreen.kt` + `UserManagementViewModel.kt` (standard mode) — list existing users (username + role); create-user form (username, PIN, role) calling `authRepository.createUser(...)`; change-own-PIN action calling `authRepository.changePin(...)` (`user-management`, `first-run-onboarding` "credential must be changeable")
- [ ] 4.2 `MainActivity.kt` (`AppRoot`) — ADMIN-only header action (boolean-swap overlay `showUserManagement`, Decision J) that opens `UserManagementScreen`; the action is not rendered/reachable for a `CASHIER` session
- [ ] 4.3 Compose UI tests (alongside, standard mode): `UserManagementScreenTest` — an ADMIN can create a new CASHIER account and a new ADMIN account; a duplicate username is rejected; a blank PIN is rejected; the existing-users list shows every user's identifier and role; a CASHIER session has no reachable path to the screen (`user-management` scenarios)
- [ ] 4.4 Amend `openspec/changes/android-pos-mvp/specs/permission-gate/spec.md` — reverse its Purpose statement ("NOT a user/account/role system ... no login flow and no persisted session-based auth state") to point at this change's amendment (`openspec/changes/android-pos-auth/specs/permission-gate/spec.md`) as the superseding source of truth, consistent with the delta spec already drafted in this change
- [ ] 4.5 Update `openspec/project.md` — retire the "single fixed cashier, no login, no role picker" and "single implicit store" Core Product Shape baseline bullets; record `android-pos-auth` as their explicit successor (per proposal Decision 6)
- [ ] 4.6 End-to-end pass covering proposal Success Criteria: fresh install auto-seeds default ADMIN + prompts one-time business-profile capture; seeded credential changeable afterward; correct PIN authenticates and reveals the POS shell; CASHIER sees 2 tabs / ADMIN sees 4; only ADMIN can create accounts; price-edit/`ADJUST` gates satisfied by the unified credential with no standalone PIN store remaining; `MIGRATION_2_3` passes `runMigrationsAndValidate`; no POS domain entity gained a `userId`/`storeId` column
