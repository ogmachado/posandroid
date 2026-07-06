# Project Context — idos-pos-android

**Detected**: 2026-07-06
**Status**: Greenfield — no build file, no source tree yet. This document is the SDD bootstrap context, not a reflection of existing code.

## What this project is

A standalone Android (Kotlin) point-of-sale app. It is a **separate application**, not a module of the existing `idos-pos` Spring Boot backend and not a build dependent of it in any way (no shared Gradle/Maven artifact, no network call assumed by default). The Spring Boot backend at `D:\Proyectos\idos-pos` is a **reference only** — its domain rules and REST contracts describe proven business behavior that this Android app should replicate locally, adapted to a single-device, offline context.

## Core product shape (v1)

- **Single device, single fixed cashier.** No multi-user login, no session switching, no role picker. Whoever operates the device is "the cashier" — there is no admin/cashier account juggling like the backend's `ROLE_ADMIN`/`ROLE_CASHIER` split. (Whether an admin-vs-cashier *permission* concept survives inside the app, e.g. a PIN-gated settings area, is a decision for `sdd-propose`/`sdd-design`, not decided here.)
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
