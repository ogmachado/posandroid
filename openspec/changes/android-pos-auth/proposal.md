# Proposal: android-pos-auth (local multi-user login, ADMIN/CASHIER roles, first-run onboarding)

## Intent

`android-pos-mvp` and `openspec/project.md` shipped the POS on a deliberate simplification: **"single device, single fixed cashier. No multi-user login, no session switching, no role picker"** — and the multi-tenant `STORE` concept was **removed entirely** ("Multi-tenant store scoping ... Disappears entirely: Single implicit store"). At that time the desktop reference's `ROLE_ADMIN`/`ROLE_CASHIER` split was collapsed into a single stateless manager-PIN primitive whose own spec states verbatim it is **"NOT a user/account/role system ... no login flow and no persisted session-based auth state."**

This change **intentionally reverses that simplification.** A real device can now be operated by more than one named person, and the business needs to distinguish who may touch price/inventory/catalog data (ADMIN) from who may only ring sales and run the drawer (CASHIER). The MVP's "whoever holds the device is the cashier" model has no answer for that. This proposal introduces a device-local, offline, Room-backed multi-user model with named users, per-user PIN credentials, two roles (`ADMIN` / `CASHIER`), a login gate, a forced first-run admin-account + business-profile onboarding, and role-based tab visibility that mirrors the desktop's `roleGuard(['ADMIN'])` on catalog/inventory routes.

This is explicitly a **"we are undoing an earlier documented decision"** moment, not a silent contradiction. It therefore carries two mandatory reconciliations: (1) an amendment to `openspec/changes/android-pos-mvp/specs/permission-gate/spec.md`, whose Purpose section currently declares the opposite of what ships here; and (2) an update to `openspec/project.md`'s "single fixed cashier / single implicit store" baseline bullets. Both are treated as in-scope deliverables below so downstream phases inherit a clean, explicit picture.

Everything remains **offline and on-device** — this change adds identity and roles, not networking, not a backend, not multi-branch tenancy.

## Scope

### In Scope

- **Real multi-user model.** A Room-backed users table: multiple named users can coexist on one device, each with a `role` (`ADMIN` | `CASHIER`) and each with their own PIN credential (hashed at rest). Not a single-profile boolean flag.
- **First-run admin auto-seed (editable).** First launch, after the license gate, seeds a default ADMIN account (`admin` / `admin123`), mirroring the desktop reference. The seeded credential is not a permanent known credential — it must be changeable afterward via an ADMIN credential-change flow.
- **One-time business-profile capture, folded into the same first-run flow.** A single business profile (name, address, phone) captured once during the same onboarding as admin-account creation. This is a **single-row profile, not a re-creatable multi-store entity** — there is exactly one "store" on a single-device app. No "create store" screen reachable later.
- **Login gate.** After a valid license, before the POS shell, the operator authenticates (select/identify user + PIN). Session identity is held in memory (mirroring how license status is re-evaluated per-resume), not persisted as a "logged in forever" flag.
- **PIN-repository reconciliation.** The existing `permission/PinRepository` credential is **extended, not duplicated**. The ADMIN login PIN and the existing price-edit / inventory-`ADJUST` PIN gate are the **same credential concept**, living in one credential store keyed by user, rather than a standalone singleton PIN alongside a parallel new one.
- **Formal amendment to `permission-gate` spec** (delta spec) reversing its "NOT a user/account/role system" Purpose statement and re-expressing the gated-action requirements in terms of an authenticated ADMIN-capable credential.
- **Update to `openspec/project.md`** Core Product Shape: retire the "single fixed cashier, no login, no role picker" and "single implicit store" baseline bullets, recording this change as their explicit successor.
- **Tab-level role gating.** `CASHIER` sees only Venta / Caja; `ADMIN` sees all four tabs (Venta / Productos / Inventario / Caja). Attaches to the hardcoded `posTabs` list in `nav/PosNavHost.kt`, filtered by the session role — mirroring the desktop `roleGuard(['ADMIN'])` on catalog/inventory routes.
- **ADMIN-only user management.** Only an ADMIN can create/manage CASHIER (and additional ADMIN) accounts, matching the desktop's "only ADMIN manages users" convention.
- **Room migration `MIGRATION_2_3`** (schema version 3), additive: user table + single-row business-profile table, validated by `MigrationTestHelper.runMigrationsAndValidate` per the existing `LicenseStateDaoTest` precedent.

### Out of Scope

- **No networking, no backend connection, no sync.** Identity is purely device-local (consistent with `project.md`'s offline-v1 stance). No remote user directory, no server auth, no account recovery via network.
- **No multi-store / multi-branch tenancy.** Exactly one business profile per device. No `storeId` foreign keys on domain entities, no store CRUD, no cross-store scoping. This change reintroduces a **business profile**, not the multi-tenant `STORE` table the MVP removed.
- **No cashier self-registration.** A CASHIER cannot create their own or anyone else's account. Only an ADMIN creates accounts, matching the desktop convention. (No device-local reason to diverge was found; if design surfaces one, it is a design-level question, not a scope change here.)
- **No password/username-with-password auth** — PIN-based credential only, consistent with the existing primitive and POS ergonomics (no physical keyboard assumed).
- **No PIN-reset-via-email / self-service recovery flow.** Recovery of a lost ADMIN PIN (e.g. another ADMIN resets it, or a documented reinstall/wipe path) is a design-level open question, not a committed capability here.
- **No per-user audit trail / "who did what" history** on orders, movements, or cash sessions in this slice. The users table makes it *possible* later; wiring `userId` onto domain records is deliberately deferred.
- **No change to POS domain shape** — Product / Inventory / Order / CashSession / Currency entities are untouched (no new `userId`/`storeId` columns added to them in this slice).
- **No lockout/backoff on failed PINs** unless design deems it trivial — the existing `permission-gate` spec explicitly left retry/lockout unspecified; this change does not commit to it.

## Capabilities

### New Capabilities

- `user-identity`: Room-backed users table (named user + role + hashed PIN credential), `UserDao`, and an `AuthRepository` owning credential creation/verification and in-memory session state.
- `first-run-onboarding`: blocking first-launch flow that forces creation of the initial ADMIN account and one-time capture of the business profile before any POS usage; no seeded/default credential.
- `login-gate`: post-license, pre-POS authentication gate slotting **inside** `AppRoot()` before `PosNavHost()`, following the established `EnforcementGate` boolean-composable-swap idiom (not a new nav route).
- `role-based-navigation`: `posTabs` filtered by session role — CASHIER → Venta/Caja, ADMIN → all tabs.
- `user-management`: ADMIN-only creation/management of additional users (CASHIER and ADMIN).
- `business-profile`: single-row store profile (name/address/phone) capture and read.

### Modified Capabilities

- `permission-gate` (`android-pos-mvp/specs/permission-gate/spec.md`): **amended.** Its "NOT a user/account/role system ... no login flow" Purpose is reversed. The price-edit and inventory-`ADJUST` gates are re-expressed against an ADMIN-capable authenticated credential rather than a standalone singleton PIN. The credential store is unified with `user-identity` rather than left parallel.

## Decisions (firm — sdd-design details HOW, not WHICH)

| # | Decision | Choice | Confirm/Override | Rationale |
|---|----------|--------|------------------|-----------|
| 1 | Identity model | **Real multi-user Room table** (named user + role + per-user hashed PIN), not a single-profile role flag | Confirm | User-fixed. Multiple distinct operators can exist on one device; audit-ready for future slices. |
| 2 | First-run bootstrap | **Auto-seed default ADMIN account (`admin`/`admin123`) on first launch, editable afterward** | Confirm (reversed from prior session) | User-fixed (updated 2026-07-13): mirrors desktop's auto-seed convention. The seeded credential must be changeable via an ADMIN credential-change flow — not a permanent known credential. |
| 3 | Credential store | **Extend the existing `PinRepository` credential concept into one per-user store**, not a parallel second store | Confirm | User-fixed. ADMIN login PIN and the existing price-edit/ADJUST gate are one credential concept. Requires the `permission-gate` spec amendment (Decision 6). |
| 4 | Store concept | **Single-row business profile** (name/address/phone) captured once in first-run onboarding | Confirmed | User-fixed (updated 2026-07-13): explicitly confirmed single store, no multi-branch/`storeId` entity. |
| 5 | Tab role gating | **In scope this slice**: CASHIER → Venta/Caja; ADMIN → all four tabs, filtering `posTabs` in `PosNavHost` | Confirm | User-fixed. Mirrors desktop `roleGuard(['ADMIN'])` on catalog/inventory. |
| 6 | Spec/doc reconciliation | **Amend `permission-gate` spec + update `project.md` baseline** as in-scope deliverables | Confirm | This change reverses two explicitly documented decisions; they must be visibly amended, not contradicted silently. |
| 7 | Account creation authority | **ADMIN-only** — no cashier self-registration | Confirm | Matches desktop's "only ADMIN manages users." No device-local reason to diverge found. |
| 8 | Gate ordering | Auth gate sits **after** a successful license check, **inside** `AppRoot()`, before `PosNavHost()` | Confirm | Follows the `EnforcementGate` precedent; an unlicensed-but-authenticated state has no product meaning. |

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/.../permission/` (users/auth) | New + Modified | New `UserDao`, `UserEntity`, `AuthRepository`, session state; `PinRepository`/`PinHasher` reconciled into the unified per-user credential store rather than duplicated |
| `app/.../MainActivity.kt` (`AppRoot`) | Modified | Auth/onboarding gate slots inside `AppRoot()`, before `PosNavHost()`, via the boolean-composable-swap idiom (no new nav graph) |
| `app/.../nav/PosNavHost.kt` | Modified | `posTabs` filtered by session role (CASHIER → Venta/Caja) |
| `app/.../ui/...` (new screens) | New | Login screen, first-run onboarding (admin account + business profile), ADMIN user-management screen — mirroring the `ActivationScreen`/`ActivationViewModel` pattern (excluded from strict TDD per `config.yaml`) |
| `app/.../core/di/AppContainer.kt` | Modified | New `userDao`/`authRepository`/business-profile slots (lazy pattern) in both `create()` and `createInMemory()`; migration registered on `create()` only |
| `app/.../core/db/PosDatabase.kt`, `Migrations.kt` | Modified | `version = 3`; `MIGRATION_2_3` additive (user table + single-row business-profile table) |
| `openspec/changes/android-pos-mvp/specs/permission-gate/spec.md` | Modified (amendment) | Reverse "NOT a user/account/role system"; re-express gates against ADMIN-capable credential |
| `openspec/project.md` | Modified | Retire "single fixed cashier, no login" + "single implicit store" baseline bullets |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| **PinRepository unification friction.** The existing primitive is a *single* secret in EncryptedSharedPreferences; the new model is *per-user* PINs. "Same credential in one place" must resolve: is the price-edit/ADJUST gate satisfied by *any* ADMIN's PIN, or specifically the logged-in ADMIN's? sdd-design must pick and document; leaning "verify against ADMIN-role users," retiring the standalone singleton. | High | Named as the primary design question below; delta spec must state it explicitly. |
| **Session vs. point-in-time-check conflict.** The current `permission-gate` spec requires re-prompting the PIN on *every* gated action (no session carry-over). A login session for an ADMIN reverses that. Does a logged-in ADMIN still re-enter the PIN per price edit, or does role suffice? | High | Explicit in the delta spec (Decision 6). Do not silently keep both behaviors. |
| **Reversing documented baseline.** `project.md` and the `permission-gate` spec currently assert the opposite of this change. Left unreconciled, the repo self-contradicts. | High | Both amendments are in-scope deliverables (Decision 6), not optional cleanup. |
| **Store-concept scope creep.** "Store" has zero data-model anchor today (no `storeId` across 13 entities). Risk of ballooning into multi-tenant scoping. | Med | Decision 4 pins it to a single-row profile; multi-store is an explicit non-goal. Flag if design disagrees. |
| **ADMIN lockout / lost PIN.** No auto-seed + no network recovery means a forgotten sole-ADMIN PIN can brick the device's admin access. | Med | Recovery path is a named design open question (out of scope to *commit* here); at minimum document the reinstall/wipe consequence. |
| **400-line review-budget overrun.** Users table + login + onboarding + tab gating + two spec/doc amendments is large. | High | Flag chained/stacked PRs at `sdd-tasks` (natural slices: migration+users → login/onboarding → tab gating → spec/doc amendments). |
| **Strict-TDD scope.** `UserDao`/`AuthRepository`/`core/db` migration fall under `config.yaml` strict-TDD globs; screens/viewmodels do not. | Low | Downstream phases follow strict TDD test-first for repo/dao/migration only. |
| **Known default credential (`admin`/`admin123`) ships on every install.** Anyone with device access before the owner changes it can reach ADMIN functions. | Med | Credential-change flow is a committed deliverable, not optional; design should consider prompting/nagging until the default is changed (not mandatory-enforced here). |

## Rollback Plan

The users/auth subsystem is additive: a new `permission`-package surface, one additive `MIGRATION_2_3`, and a gate hook inside `AppRoot()`. Rollback = revert the change commits and the additive migration; the POS domain (Product/Inventory/Order/CashSession/Currency) is untouched, so the app returns to the single-fixed-cashier MVP behavior. The `permission-gate` spec amendment and `project.md` update revert with the same commits. Because no domain entity gains a `userId`/`storeId` column in this slice, there is no destructive data coupling to unwind.

## Dependencies

- `android-pos-licensing` merged — the auth gate sits *after* the license `EnforcementGate` and reuses its boolean-swap idiom (Decision 8).
- `android-pos-mvp` merged — provides the `PinRepository`/`PinGate`/`PinHasher` primitive being reconciled, the `PosNavHost` shell, the DI/migration patterns, and the `permission-gate` spec being amended.

## Notes for sdd-design

- **Credential unification mechanics (primary):** decide whether the price-edit/ADJUST gate verifies against *any* ADMIN user or the *currently-logged-in* ADMIN, and how the existing EncryptedSharedPreferences singleton migrates to per-user hashed PINs (or whether per-user hashes live in Room while `PinHasher` is reused). Document the retirement of the standalone singleton PIN.
- **Session-vs-per-action-PIN reconciliation:** define, in the delta spec, whether a logged-in ADMIN is exempt from per-action PIN re-prompts (role suffices) or still re-prompted. This directly contradicts the current spec's "point-in-time check, no session" requirement and must be stated, not assumed.
- **Migration shape:** whether the users table and business-profile table land in one `MIGRATION_2_3` or two migrations; keep additive and `MigrationTestHelper`-validated per `LicenseStateDaoTest`.
- **ADMIN recovery:** at minimum document the consequence of a lost sole-ADMIN PIN (reinstall/wipe), even though a recovery flow is out of scope.
- **First-run seeding:** where the default ADMIN (`admin`/`admin123`) is seeded (e.g. `MIGRATION_2_3`'s `onPostMigrate`/callback vs. a repository-level check on first DB access) and how the credential-change flow is surfaced (mandatory nag vs. optional settings entry).

## Success Criteria

- [ ] A fresh install (post-license) auto-seeds a default ADMIN account (`admin`/`admin123`) and presents one-time business-profile (name/address/phone) capture; the seeded ADMIN credential can be changed afterward via a credential-change flow.
- [ ] After onboarding, launching the app presents a login gate; a correct user PIN authenticates and reveals the POS shell.
- [ ] A logged-in CASHIER sees only Venta and Caja tabs; a logged-in ADMIN sees all four (Venta / Productos / Inventario / Caja).
- [ ] Only an ADMIN can create additional CASHIER/ADMIN accounts; a CASHIER has no account-creation path.
- [ ] The price-edit and inventory-`ADJUST` gates are satisfied by the unified ADMIN-capable credential (no separate standalone PIN store remains).
- [ ] `MIGRATION_2_3` creates the users + business-profile tables and passes `runMigrationsAndValidate` against Room's generated schema.
- [ ] `permission-gate/spec.md` is amended (its "NOT a user/account/role system" Purpose reversed) and `openspec/project.md`'s "single fixed cashier / single implicit store" baseline bullets are updated to record this change as their successor.
- [ ] No POS domain entity (Product/Inventory/Order/CashSession/Currency) gains a `userId`/`storeId` column in this slice; multi-store and networking remain absent.
