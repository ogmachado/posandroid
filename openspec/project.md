# Project Context — idos-pos-android

**Detected**: 2026-07-06
**Status**: Greenfield — no build file, no source tree yet. This document is the SDD bootstrap context, not a reflection of existing code.

## What this project is

A standalone Android (Kotlin) point-of-sale app. It is a **separate application**, not a module of the existing `idos-pos` Spring Boot backend and not a build dependent of it in any way (no shared Gradle/Maven artifact, no network call assumed by default). The Spring Boot backend at `D:\Proyectos\idos-pos` is a **reference only** — its domain rules and REST contracts describe proven business behavior that this Android app should replicate locally, adapted to a single-device, offline context.

## Core product shape (v1)

- **Retired (superseded 2026-07-13 by `android-pos-auth`): single fixed cashier, no login, no role picker; single implicit store.** The original v1 baseline read: *"Single device, single fixed cashier. No multi-user login, no session switching, no role picker... there is no admin/cashier account juggling like the backend's `ROLE_ADMIN`/`ROLE_CASHIER` split,"* alongside an implicit assumption of no store/business-profile concept at all (zero `storeId` usage anywhere in the schema). `android-pos-auth` reverses both: the device now supports a real Room-backed multi-user model (named users, hashed per-user PIN, `ADMIN`/`CASHIER` roles), a login gate, role-based tab visibility, and a one-time single-row business-profile capture. See `openspec/changes/android-pos-auth/specs/` for current behavior — the multi-store/multi-tenant `STORE` concept remains explicitly out of scope; the business profile is single-row only.
- **Offline, no backend.** No server dependency assumed for v1. All data lives in a local database on the device.
- **Local database only.** Room (on top of SQLite) is the leading candidate given it's the standard Android/Kotlin persistence layer, but this is **not yet decided** — `sdd-design` should confirm or replace this choice explicitly rather than treating it as settled.
- **No receipt printing in v1.** Explicitly out of scope. Do not design print-driver abstractions prematurely.
- **Barcode scanning via device camera.** MLKit Barcode Scanning or ZXing are the leading candidates; not yet decided. No dedicated hardware scanner assumed for v1 — the phone/tablet camera is the input device.
- **Alternative-currency display conversion — needed.** Same UX concept as the reference backend's `/currencies` + POS conversion rows: show the order total (and change, where applicable) converted into one or more alternative currencies below the primary-currency amount, **display-only** (no persisted rate on the transaction, no multi-currency accounting). The reference system's convention — exchange rate stored as "units of primary currency per 1 unit of the alternative currency" — is a reasonable starting point for the local data model but should be re-validated during design.

## What the reference backend (`D:\Proyectos\idos-pos`) informs — NOT shares

No code, no build dependency, no shared package. What carries over is **business-logic understanding** of a working POS domain:

| Reference concept | Backend module | What to learn from it |
|---|---|---|
| Products + categories | `products/` | Catalog shape: code uniqueness, barcode lookup, category grouping, unit of measure |
| Per-entity inventory with IN/OUT/ADJUST movements | `inventory/` | Stock is a ledger of movements, not just a mutable counter; a single service/primitive should own all stock mutation to avoid double-counting |
| Sales/order creation with stock decrement | `sales/` | An order line creation must atomically decrement stock (OUT movement) — this pairing needs an equivalent local-transaction guarantee (Room `@Transaction`) |
| Void with reason | `sales/` (`/order/{id}/void`) | Voiding should reverse the inventory effect (a compensating movement) rather than deleting the original record, and should require a reason |
| Cash-session open/close with `expectedBalance` formula | `cash-register/` | `expectedBalance = opening + Σ deposits − Σ withdrawals + Σ cash sales`; sessions bound the reporting window |
| Payment-method / currency catalogs | `sales/` | Payment methods carry an "affects cash balance" flag (e.g. cash vs. transfer) that should gate cash-session math the same way locally |
| Admin/cashier roles for permission gating | `security/` | Reference for what *used to* need a role gate — re-evaluate against the single-fixed-cashier decision above; likely maps to a lighter PIN/settings-lock concept rather than a full role system |

None of the above implies reusing DTOs, entities, REST paths, or Liquibase changelogs from the backend. It is domain knowledge to consult, not code to port.

## Explicitly not yet decided (do not assume in `sdd-explore`/`sdd-propose` without re-confirming)

- Local persistence library (Room candidate, not committed)
- Barcode library (MLKit vs. ZXing, not committed)
- UI toolkit (Jetpack Compose vs. legacy Views — not addressed at all yet, needs a decision)
- Whether any settings/admin-style gating exists despite the single-cashier model
- Whether a future sync-to-backend capability is ever in scope (current assumption: no, purely offline for v1)

## Testing capabilities

**Not yet determined.** There is no `build.gradle`/`build.gradle.kts`, no Gradle wrapper, and no source tree in this repository yet — only `.git` and SDD bootstrap files (`.atl/`, `openspec/`). Test runner, framework, and commands cannot be detected from an empty repo and must not be guessed.

Re-detection is required once `sdd-design` (or an early `sdd-apply` slice) establishes the actual Android project setup. Expected candidates at that point: Gradle as the build/test runner, JUnit 5 (or JUnit 4 + AndroidX Test) for unit tests, Robolectric for JVM-side Android unit tests, and Espresso for instrumented/UI tests — none of this is confirmed today.

See `openspec/config.yaml` → `testing:` for the placeholder this project's SDD tooling reads until re-detection happens.

## Bootstrap Decisions (recorded PR8/Phase 10, task 10.2)

These were decided during `android-pos-mvp`'s `sdd-apply` batches (PR1 onward) and are recorded here for durability now that the Gradle/Android project actually exists:

- **`minSdk = 26`, `compileSdk`/`targetSdk = 34`** (tasks.md 0.2) — `design.md` left this unpinned; 26 was chosen as the floor because it covers CameraX/MLKit's own minimum requirement while keeping broad device reach. Revisit only if a future capability needs a higher floor (e.g. newer Jetpack Security APIs).
- **Package name `com.idos.pos`** (tasks.md 0.1) — matches this repository's single Gradle `:app` module; feature code is organized by package (`catalog`, `inventory`, `sales`, `cashsession`, `currency`, `permission`, `scan`, `core`) per `design.md`'s "Module structure" decision. No relation to the reference backend's `com.idos.*` packages beyond the shared vendor prefix — no code or build dependency exists between the two (see "What this project is" above).
- **Seed exchange-rate placeholder: `540`** (`PosDatabaseSeeder.DEFAULT_USD_EXCHANGE_RATE`, tasks.md 2.6) — units of CUP per 1 USD, matching the reference deployment's convention (`specs/currency-display/spec.md` "Exchange Rate Convention"). **This is a documented placeholder constant, not a permanent business value** — it is a real-world financial rate that changes over time. `PosDatabaseSeeder`'s own KDoc carries the same warning at the source. There is no in-app edit screen for it in Slice A (currency catalog is seed-only, same as the payment-method catalog per `design.md`'s "Payment-method catalog is read-only" decision) — reconfiguring it today means changing the constant and reinstalling, or a direct DB edit. An admin-editable rate is a natural candidate for a later slice, not committed to here.
