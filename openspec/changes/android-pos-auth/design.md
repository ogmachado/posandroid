# Design: android-pos-auth (local multi-user login, ADMIN/CASHIER roles, first-run onboarding)

## Technical Approach

Introduce a device-local identity model as a Room-backed feature that reuses every established pattern in this codebase: per-feature package (`Entity/Dao → Repository → ViewModel/Screen`), manual DI in `AppContainer`, one additive Room migration, and the `EnforcementGate` boolean-composable-swap idiom for the gate. No new Gradle deps, no networking, no nav graph.

Two new tables land in one additive `MIGRATION_2_3` (schema `version = 3`): `app_user` (multi-user credentials) and `business_profile` (single-row store profile). Credentials are hashed with the **existing, unchanged `PinHasher`** and stored per-user in Room; the standalone EncryptedSharedPreferences singleton PIN (`PinRepository`) is retired. Authentication produces an **in-memory** session held by `AuthRepository` (no persisted "logged in" flag), mirroring how license status is re-evaluated per resume. The gate stack becomes `License → Onboarding → Login → PosNavHost`, expressed as nested boolean swaps inside `AppRoot()`.

The `permission-gate` price-edit / `ADJUST` check keeps its exact point-in-time, no-session-carry-over behavior — only its credential source changes: it now verifies the entered PIN against **any `ADMIN`-role row** in the unified store (spec `permission-gate` firmly resolved this), never against the login session's identity. All firm decisions in `proposal.md` and the 7 spec files are locked; this document is HOW only.

## Architecture Decisions

| # | Decision | Choice | Rejected | Rationale |
|---|----------|--------|----------|-----------|
| A | Migration shape | **One `MIGRATION_2_3`** creating both `app_user` and `business_profile` (additive) | Two migrations (2→3, 3→4) | Both tables are additive, both belong to the same onboarding feature, and both ship in one schema-version bump. One migration = one `runMigrationsAndValidate` assertion against the exported `3.json`, matching the `MIGRATION_1_2` precedent. Splitting adds a version with no independent value. |
| B | Default-ADMIN seeding | **Repository-level idempotent `AuthRepository.ensureDefaultAdminSeeded()`** (`if userDao.count() == 0 → insert admin/admin123`), invoked when the onboarding branch first composes | Room `onCreate` seeder callback; `INSERT` inside `MIGRATION_2_3.migrate()` | `onCreate` fires **only on fresh DB files** and `migrate()` fires **only on v2→v3 upgrades** — neither covers both install paths. A repository idempotent check covers fresh installs AND upgrading MVP devices uniformly, is fully JVM/Robolectric-testable (users live in Room, hashing is pure `PinHasher` — no Keystore), and keeps `MIGRATION_2_3` structural-only so `runMigrationsAndValidate` stays a clean schema check (matching `MIGRATION_1_2`). |
| C | Per-user credential storage | **`pinSalt`/`pinHash` as `BLOB` columns in `app_user`, `PinHasher` reused as-is** | Per-user secrets in EncryptedSharedPreferences; adapting/replacing `PinHasher` | A multi-user model cannot live in a single-secret EncryptedSharedPreferences store. PINs are PBKDF2-salted (120k iterations) hashes, never plaintext — so plain-Room-at-rest exposes only a hash, satisfying `user-identity`'s "hashed at rest" MUST. Reusing `PinHasher` unchanged preserves its existing JVM unit coverage and means the whole credential path becomes Robolectric-testable (the old `PinRepository` was Keystore-bound / instrumented-only). |
| D | Singleton PIN retirement | **Discard-and-delete**: de-wire `PinRepository` from `AppContainer` + `rememberPinGate`, best-effort `context.deleteSharedPreferences("idos-pos-pin-prefs")` on first seed, do **not** import the old value | Import old singleton value into the seeded admin; leave the class inert-and-ignored | The fresh auto-seed is a **known** default (`admin/admin123`); the old singleton value is unknown and un-mappable to a username, so importing it is impossible and pointless. Deleting the orphaned prefs file removes any stale secret and makes `user-identity`'s "no parallel store consulted" unambiguous. `PinHasher` (pure) is kept and reused; `PinRepository` (the storage class) is deleted. |
| E | Gate layering | **A 3-way `when` `AuthGate` composable inside `AppRoot()`**, replacing the direct `PosNavHost()` call, ordered Onboarding → Login → PosNavHost | New nav-graph route; three independent top-level gates in `MainActivity` | Extends the exact `EnforcementGate` idiom (a testable composable driven by state, no nav graph). Onboarding and Login stay **distinct branches** (distinct completion conditions: profile-exists vs. session-not-null) inside one `when`, matching the spec's fixed License→Onboarding→Login order. The license gate stays exactly where it is (`EnforcementGate` above `AppRoot`). |
| F | Session state location | **In-memory `StateFlow<AuthSession?>` owned by `AuthRepository`** (app-lifetime via `AppContainer`), reset only by process death | Persisted "logged in" flag (DataStore/prefs/Room) | `user-identity` MUST hold session in memory only and MUST NOT persist a "logged in forever" flag — a process restart must re-reach the login gate. An app-lifetime `AuthRepository` holding a `StateFlow` gives Compose an observable session while dying naturally with the process, mirroring the per-resume license re-eval. |
| G | permission-gate verify seam | **`PinGate`'s verifier becomes `suspend`; `AuthRepository.verifyAdminPin(pin)` queries all `ADMIN` rows point-in-time** and checks each with `PinHasher.verify` | Synchronous main-thread DAO read; cached in-memory ADMIN-credential snapshot | Production forbids main-thread DB queries, so a sync verifier is impossible; a cached snapshot risks staleness versus the spec's point-in-time MUST. Making the verifier `suspend` (launched in a remembered `CoroutineScope`) keeps every check a **fresh** DB read against **all** current `ADMIN` rows — satisfying any-ADMIN, point-in-time, no-session-carry-over in one mechanism. `PinGateDialog` is untouched (it still calls `gate.submit(pin)`). |
| H | Tab role filtering | **`visibleTabsFor(role: UserRole)` transform in `PosNavHost`; `role` passed as a `PosNavHost(role)` parameter** | Separate nav graph per role; a stored "tab-permission" concept | Keeps the single flat `NavHost` and its existing test seam intact. All route `composable {}` declarations remain defined; only the rendered `posTabs` list is filtered, so hiding a tab never removes a route (matches role-nav spec: visibility ≠ authorization). Role arrives as a param at composition time → derives fresh from the session, no cached tab set. |
| I | Package layout | **Identity/auth in the existing `permission` package; business profile in a new `business` package** | A separate new `auth` package split off from `permission` | The `licensing` package is the precedent: one feature package holds entity+dao+repo+viewmodel+screen together. `permission` is already the "who-can-do-what" package hosting the gate being reconciled, so identity co-locates there (proposal Affected Areas names `permission/` for users/auth). The single-row store profile is a distinct domain concern → its own `business` package (cross-package use is normal here, e.g. `catalog` depends on `inventory`). |
| J | User-mgmt / credential-change entry point | **ADMIN-only boolean-swap overlay reached from a slim ADMIN-only header action in `AppRoot`**, not a 5th tab | A 5th "Usuarios" tab; threading `NavController` into the shell header | role-based-navigation firmly fixes exactly 4 ADMIN tabs — a 5th tab would violate it. A boolean-swap overlay (`showUserManagement` state → `UserManagementScreen`) reuses the idiom this change already adopts and needs no `NavController` plumbing. The credential-change flow lives **inside** that screen (change-own-PIN), satisfying `first-run-onboarding`'s "credential must be changeable" as availability, not enforced nag. |

## Identity model & credential mechanics

`UserEntity` (`app_user`):

| Column | Type | Notes |
|--------|------|-------|
| `id` | `INTEGER` PK autogenerate | surrogate key |
| `username` | `TEXT NOT NULL` | unique index `index_app_user_username` |
| `role` | `TEXT NOT NULL` | stored as `"ADMIN"`/`"CASHIER"` string; `UserRole` enum at the domain boundary (no `Converters.kt` change, mirrors how `license_state` stores raw strings) |
| `pinSalt` | `BLOB NOT NULL` | `PinHasher.generateSalt()` output |
| `pinHash` | `BLOB NOT NULL` | `PinHasher.hash(pin, salt)` output |

`AuthRepository` responsibilities (single class, fully Robolectric-testable):

- `ensureDefaultAdminSeeded()` — idempotent seed (Decision B); best-effort deletes the old PIN prefs file (Decision D) on the seeding run.
- `login(username, pin): Boolean` — per-user verify (`user-identity`); on success sets the in-memory session to that user + role.
- `verifyAdminPin(pin): Boolean` — point-in-time gate check against **all** `ADMIN` rows (Decision G / `permission-gate`); never touches the session.
- `createUser(username, pin, role): Result<Unit>` — ADMIN-authored creation; rejects duplicate username (`DomainError.DuplicateUsername`) and blank PIN (`DomainError.BlankPin`).
- `changePin(userId, newPin)` — credential-change flow.
- `currentSession: StateFlow<AuthSession?>` — in-memory session (Decision F). `AuthSession(userId, username, role)`.

Credential PINs never appear in plaintext at rest; `verifyAdminPin` and `login` both go through `PinHasher.verify(pin, salt, hash)`.

### permission-gate wiring change (behavior retained, source changed)

`PinGate`'s injected verifier changes from `(String) -> Boolean` to `suspend (String) -> Boolean`; `submit(pin)` launches it in a `CoroutineScope` captured by `rememberPinGate`. The 3 call sites in `PosNavHost` change from `rememberPinGate(LocalAppContainer.current.pinRepository)` to `rememberPinGate(LocalAppContainer.current.authRepository)`, wiring `authRepository::verifyAdminPin`. `PinGateDialog` is unchanged. Point-in-time, any-ADMIN, no-carry-over are all preserved (Decision G).

## Gate stack & session flow

```
MainActivity.setContent
  └─ EnforcementGate(licenseStatus)            [existing — unchanged]
       ├─ !licensed → ActivationScreen()
       └─ licensed  → AppRoot(isInGracePeriod)
                        └─ AuthGate()           [NEW — replaces direct PosNavHost() call]
                             when (authGateState):
                               Onboarding  → OnboardingScreen(onComplete = refresh)
                                              └─ LaunchedEffect: authRepository.ensureDefaultAdminSeeded()
                               Login       → LoginScreen(onAuthenticated = …)   // user picker + PIN pad
                               Authenticated(role) → PosNavHost(role)            // tabs filtered by role
```

`AuthGateViewModel` (excluded from strict TDD) derives `AuthGateState` from two sources:
- **Onboarding** while `businessProfileRepository.exists() == false` (blocks login until profile captured — `first-run-onboarding` / `business-profile`).
- **Login** while `businessProfile` exists but `authRepository.currentSession == null`.
- **Authenticated(role)** once `currentSession != null`.

Because `business_profile` is only written at the end of onboarding (after the admin seed), a device can never reach Login/POS without a seeded admin — the ordering is structurally guaranteed, not merely sequenced.

## Data flow

```
startup (post-license) ─▶ AuthGate composes
   businessProfile? no  ─▶ Onboarding ─▶ ensureDefaultAdminSeeded() (idempotent)
                                       ─▶ capture name/address/phone ─▶ businessProfileRepository.save()
                                       ─▶ state → Login
   businessProfile? yes ─▶ Login ─▶ pick user + PIN ─▶ authRepository.login() ─▶ sets in-memory session
                                                                              ─▶ state → Authenticated(role)
   Authenticated(role) ─▶ PosNavHost(role) ─▶ visibleTabsFor(role)  (CASHIER: Venta/Caja · ADMIN: all 4)

price edit / ADJUST  ─▶ PinGate.require { action } ─▶ PinGateDialog(PIN)
                     ─▶ submit(pin) ─▶ authRepository.verifyAdminPin(pin)  // any ADMIN row, fresh read
                                     ─▶ true → action()  ·  false → DomainError.PinIncorrect

ADMIN header action ─▶ showUserManagement=true ─▶ UserManagementScreen
                        (list users · createUser · change-own-PIN)
```

## Room schema (v3, additive)

| Table | Key | Cols |
|-------|-----|------|
| `app_user` | `id` autogen PK; unique index on `username` | `username` TEXT, `role` TEXT, `pinSalt` BLOB, `pinHash` BLOB |
| `business_profile` | `id` (PK=1, single row — `SINGLE_ROW_ID` pattern from `LicenseStateEntity`) | `name` TEXT, `address` TEXT, `phone` TEXT |

`MIGRATION_2_3` (structural only — no seed rows, per Decision B), must match Room's generated `3.json` exactly:

```sql
CREATE TABLE IF NOT EXISTS `app_user` (
    `id` INTEGER NOT NULL,
    `username` TEXT NOT NULL,
    `role` TEXT NOT NULL,
    `pinSalt` BLOB NOT NULL,
    `pinHash` BLOB NOT NULL,
    PRIMARY KEY(`id`)
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_app_user_username` ON `app_user` (`username`);
CREATE TABLE IF NOT EXISTS `business_profile` (
    `id` INTEGER NOT NULL,
    `name` TEXT NOT NULL,
    `address` TEXT NOT NULL,
    `phone` TEXT NOT NULL,
    PRIMARY KEY(`id`)
);
```

Validated by `MigrationTestHelper.runMigrationsAndValidate(TEST_DB_NAME, 3, true, MIGRATION_2_3)` against the exported `app/schemas/com.idos.pos.core.db.PosDatabase/3.json`, exactly as `LicenseStateDaoTest.migration1to2_appliesCleanly_toAV1SchemaFixture` does for v2. No existing table is touched; no domain entity gains `userId`/`storeId` (`business-profile` MUST).

## Interfaces

```kotlin
enum class UserRole { ADMIN, CASHIER }

@Entity(tableName = "app_user", indices = [Index(value = ["username"], unique = true)])
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val role: String,          // UserRole.name; mapped at the repo boundary
    val pinSalt: ByteArray,
    val pinHash: ByteArray,
)

@Dao interface UserDao {
    @Query("SELECT COUNT(*) FROM app_user") suspend fun count(): Int
    @Query("SELECT * FROM app_user WHERE role = :role") suspend fun findByRole(role: String): List<UserEntity>
    @Query("SELECT * FROM app_user WHERE username = :username") suspend fun findByUsername(username: String): UserEntity?
    @Query("SELECT * FROM app_user") suspend fun findAll(): List<UserEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(user: UserEntity): Long
    @Query("UPDATE app_user SET pinSalt = :salt, pinHash = :hash WHERE id = :id") suspend fun updatePin(id: Long, salt: ByteArray, hash: ByteArray)
}

@Entity(tableName = "business_profile")
data class BusinessProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val name: String, val address: String, val phone: String,
) { companion object { const val SINGLE_ROW_ID = 1 } }

@Dao interface BusinessProfileDao {
    @Query("SELECT * FROM business_profile WHERE id = :id") suspend fun find(id: Int = BusinessProfileEntity.SINGLE_ROW_ID): BusinessProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: BusinessProfileEntity)  // single-row REPLACE, cannot create a 2nd row
}

data class AuthSession(val userId: Long, val username: String, val role: UserRole)

class AuthRepository(private val userDao: UserDao, private val context: Context) {
    val currentSession: StateFlow<AuthSession?>            // in-memory only (Decision F)
    suspend fun ensureDefaultAdminSeeded()                 // idempotent (Decision B) + prefs cleanup (Decision D)
    suspend fun login(username: String, pin: String): Boolean
    suspend fun verifyAdminPin(pin: String): Boolean       // any ADMIN, point-in-time (Decision G)
    suspend fun createUser(username: String, pin: String, role: UserRole): Result<Unit>
    suspend fun changePin(userId: Long, newPin: String)
    companion object { const val DEFAULT_ADMIN_USERNAME = "admin"; const val DEFAULT_ADMIN_PIN = "admin123" }
}

class BusinessProfileRepository(private val dao: BusinessProfileDao) {
    suspend fun exists(): Boolean
    suspend fun get(): BusinessProfileEntity?
    suspend fun save(name: String, address: String, phone: String)
}

// PinGate seam change (Decision G):
class PinGate(private val verifyPin: suspend (String) -> Boolean, private val scope: CoroutineScope) { … }
@Composable fun rememberPinGate(authRepository: AuthRepository): PinGate  // scope = rememberCoroutineScope()
```

New `DomainError` variants: `DuplicateUsername(username)`, `BlankPin` (ViewModels `when`-match, per the existing `DomainError` contract).

## File Changes

| Path | Action | Strict TDD? | Description |
|------|--------|-------------|-------------|
| `permission/UserEntity.kt` | Create | No (data class) | `app_user` row |
| `permission/UserRole.kt` | Create | No | `ADMIN`/`CASHIER` enum |
| `permission/UserDao.kt` | Create | **Yes** (`**/*Dao.kt`) | user queries |
| `permission/AuthSession.kt` | Create | No | in-memory session value |
| `permission/AuthRepository.kt` | Create | **Yes** (`**/*Repository.kt`) | seed/login/verifyAdminPin/createUser/changePin + session |
| `permission/PinGate.kt` | Modify | No (UI state holder) | verifier → `suspend`, scope-launched submit |
| `permission/PinRepository.kt` | **Delete** | — | singleton retired (Decision D) |
| `permission/PinHasher.kt` | Unchanged | No (not in `strict_tdd_scope`; unchanged utility, already covered by its existing JVM test) | reused as-is |
| `permission/PinGateDialog.kt` | Unchanged | No | still calls `gate.submit(pin)` |
| `permission/AuthGate.kt` + `AuthGateViewModel.kt` | Create | No (`*ViewModel.kt` excluded; gate = UI) | 3-way onboarding/login/authenticated swap |
| `permission/LoginScreen.kt` + `LoginViewModel.kt` | Create | No (`*Screen`/`*ViewModel`) | user picker + PIN pad |
| `permission/OnboardingScreen.kt` + `OnboardingViewModel.kt` | Create | No | seeds admin + captures profile |
| `permission/UserManagementScreen.kt` + `UserManagementViewModel.kt` | Create | No | list + create user + change-own-PIN |
| `business/BusinessProfileEntity.kt` | Create | No | single-row profile |
| `business/BusinessProfileDao.kt` | Create | **Yes** | profile find/upsert |
| `business/BusinessProfileRepository.kt` | Create | **Yes** | exists/get/save |
| `core/db/PosDatabase.kt` | Modify | **Yes** (`core/db/**`) | `version = 3`; register 2 entities + 2 DAOs |
| `core/db/Migrations.kt` | Modify | **Yes** | `MIGRATION_2_3` (structural) |
| `core/domain/DomainError.kt` | Modify | Yes (`core/domain/**`) | add `DuplicateUsername`, `BlankPin` |
| `core/di/AppContainer.kt` | Modify | No (`core/di/**` excluded) | `userDao`/`businessProfileDao`/`authRepository`/`businessProfileRepository`; register `MIGRATION_2_3` on `create()`; drop `pinRepository` |
| `MainActivity.kt` | Modify | No (UI, alongside) | `AppRoot()` renders `AuthGate()` instead of `PosNavHost()` |
| `nav/PosNavHost.kt` | Modify | No (alongside) | `PosNavHost(role)`, `visibleTabsFor(role)`, `rememberPinGate(authRepository)` |
| `app/schemas/.../3.json` | Generate | — | Room export (build artifact, checked in like `1.json`/`2.json`) |
| `openspec/changes/android-pos-mvp/specs/permission-gate/spec.md` | Modify (amend) | — | reverse "NOT a user/account/role system" (delta spec drives this) |
| `openspec/project.md` | Modify | — | retire "single fixed cashier / single implicit store" baseline bullets |

## Testing Strategy (mapped to `strict_tdd_scope`)

Strict RED/GREEN first (config `include`: `**/*Repository.kt`, `**/*Dao.kt`, `core/db/**`, `core/domain/**`):

| Layer | Class | Mode |
|-------|-------|------|
| RED/GREEN (Robolectric + in-mem Room) | `UserDao`, `BusinessProfileDao` (CRUD, uniqueness, single-row REPLACE), `MIGRATION_2_3` (`runMigrationsAndValidate` vs `3.json`) | test-first |
| RED/GREEN (Robolectric + in-mem Room) | `AuthRepository` (idempotent seed, per-user login, `verifyAdminPin` any-ADMIN/point-in-time, duplicate-username & blank-PIN rejects, in-memory session set/clear), `BusinessProfileRepository` (exists/get/save, no-second-row) | test-first |
| RED/GREEN (JVM) | `DomainError` additions covered incidentally via repo tests | — |
| Alongside (Compose) | `AuthGate`/`AuthGateViewModel`, `LoginScreen`/`LoginViewModel`, `OnboardingScreen`/`OnboardingViewModel`, `UserManagementScreen`/`UserManagementViewModel`, `PosNavHost` tab-filter, `PinGate` suspend seam | with impl |
| Alongside (wiring) | `AppContainer`, `MainActivity` `AuthGate` slot | with impl |

**Key testability win:** unlike the retired `PinRepository` (Keystore-bound, instrumented-only), `AuthRepository` and both DAOs live entirely in Room + pure `PinHasher`, so the whole credential path gets fast JVM/Robolectric coverage. `PinGate`'s suspend seam is exercised with a fake `suspend` verifier (same decoupling `PinGate` already used for `PinRepository::verify`).

## Migration / Rollout

Additive `MIGRATION_2_3`; revert = drop the change commits + the migration, and the app returns to single-fixed-cashier MVP behavior. No POS domain entity gains a column, so there is no destructive data coupling to unwind. The `permission-gate` amendment and `project.md` update revert with the same commits.

**Review-budget note (for `sdd-tasks`):** this is large (2 tables + migration + repos + login + onboarding + user-mgmt + tab gating + 2 doc amendments). Natural slices for chained/stacked PRs: **(1)** schema + `UserDao`/`BusinessProfileDao`/`AuthRepository`/`BusinessProfileRepository` + `MIGRATION_2_3` (strict-TDD core), **(2)** `AuthGate` + onboarding + login + session wiring, **(3)** tab role gating + permission-gate seam retarget + `PinRepository` retirement, **(4)** user-management + credential-change + `permission-gate`/`project.md` doc amendments.

## ADMIN recovery (lost sole-ADMIN PIN)

Out of scope to build a recovery flow (proposal + `first-run-onboarding` non-goal). **Documented consequence:** a forgotten sole-ADMIN PIN with no other ADMIN account cannot be reset in-app. The only recovery is clearing app data / reinstalling, which drops the Room DB and re-triggers first-run onboarding — `ensureDefaultAdminSeeded()` then re-seeds `admin/admin123` on the empty users table (and the business profile is re-captured). This is destructive (all local POS data is lost) and must be understood as the recovery path. Mitigation available to operators: create a second ADMIN account via user-management so no single PIN is a single point of failure.

## Open Questions

Resolved in this document:
- Migration shape → **Decision A** (single `MIGRATION_2_3`).
- Seeding mechanism → **Decision B** (repository-level idempotent).
- `PinHasher` reuse + singleton retirement → **Decisions C, D** (reuse as-is; discard-and-delete).
- Gate layering → **Decision E** (nested `when` `AuthGate` in `AppRoot`).
- Session state location → **Decision F** (in-memory `StateFlow` in `AuthRepository`).
- `posTabs` filtering → **Decision H** (`visibleTabsFor(role)`, `role` param).
- Screen/viewmodel layout & packages → **Decision I** + File Changes.
- User-mgmt / credential-change entry → **Decision J** (ADMIN-only boolean-swap overlay).
- Strict-TDD boundaries → File Changes column + Testing Strategy.

Judgment calls left for human awareness (not blockers):
- **Login UX** is chosen as a **user-picker + PIN pad** (not typed username) for no-keyboard ergonomics — `login-gate` left this open. If a typed username is preferred, it is a screen-level swap with no data-model impact.
- **User-management entry point** (ADMIN-only header overlay, Decision J) is a UI-layout choice the specs did not fix; it deliberately avoids a 5th tab. Worth a glance at apply time to confirm the header affordance placement feels right.
- **No logout / switch-user** affordance (session ends on process death only) — consistent with `login-gate` leaving it uncommitted; add later if wanted.
