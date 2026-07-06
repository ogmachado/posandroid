# Exploration: android-pos-mvp — greenfield Android POS, ported from idos-pos backend

## Current State

The reference backend (`D:\Proyectos\idos-pos`) is a multi-tenant, multi-user Spring Boot system. Reading the actual service implementations (not just `CLAUDE.md`, which undersells a few things) surfaced business rules beyond what's documented:

- **`OrderServiceImpl.create`**: one `@Transactional` that resolves store context, validates a `RETURN` against the original order's remaining returnable quantity per line (`ExcessiveReturnQuantityException`), assigns a **daily sequential order number per store** (`findMaxOrderNumberByStoreIdAndDate`), resolves payment method (defaults to `CASH`), and for every line, decrements/increments stock via `inventoryMovementService.create(...)`. Stock-affecting lines are re-sorted by `productId` before locking specifically to avoid cross-order deadlocks — a multi-request concern.
- **`InventoryServiceImpl.decrementStock`**: uses `findByStoreIdAndProductIdForUpdate` — a pessimistic `SELECT ... FOR UPDATE` — precisely because concurrent HTTP requests from different terminals/cashiers can race on the same `(storeId, productId)` row.
- **Per-store price/cost override**: `Inventory.price`/`Inventory.costPrice` fall back to `Product.price`/`Product.costPrice` via `getPriceOrDefault`/`getCostPriceOrDefault` — this exists so the same catalog item can be priced differently per store. Not mentioned in `CLAUDE.md`.
- **`CashSessionServiceImpl.open`**: an app-level uniqueness check (`existsByCashierUsernameAndStatus`) backed by a DB unique index as a TOCTOU backstop — explicitly commented as guarding against two concurrent open-session requests from the same cashier.
- **`SecurityConfig`**: `/admin/**` → `ROLE_ADMIN`; role model lives in `APP_USER`/`APP_ROLE`/`APP_USER_ROLE` tables with BCrypt + JWT.
- **`Currency.java`**: `exchangeRate` = units of primary currency per 1 unit of the alternative; `total_secondary = total_primary / exchangeRate`.

## Business Rule Port Matrix

| Reference rule | Fate in android-pos-mvp | Why |
|---|---|---|
| Product catalog (code/barcode uniqueness, category, unit of measure) | **Port as-is** | Still needed; just becomes local unique constraints, no store scoping |
| Per-store price/cost override on `Inventory` | **Disappears** (or becomes optional local override, a design call) | Only one store exists — the override dimension has nothing to vary against |
| Stock as movement ledger (IN/OUT/ADJUST), single-owner service | **Port as-is** | Audit trail + no-double-count discipline is valuable regardless of multi-tenancy |
| Pessimistic `FOR UPDATE` lock + deadlock-avoidance product-lock ordering | **Disappears** | See Concurrency Verdict below — no concurrent writers exist on one device |
| Order create: atomic order+lines+stock decrement+session attach+payment method | **Port as-is** (via Room `@Transaction`) | Atomicity is a correctness requirement, independent of concurrency |
| Daily sequential order number, scoped `findMaxOrderNumberByStoreIdAndDate` | **Simplifies** | Drop `storeId` scoping; keep per-day sequence logic |
| Returns (`OrderType.RETURN`, remaining-quantity validation, negative totals) | **Port as-is, but flag scope** | Genuine business logic, store-agnostic — **not mentioned in the original ask**; explicit scope decision made below |
| Void with reason + compensating inventory movement | **Port as-is**; admin/cashier gate becomes a local permission check | Business value is unchanged by single-operator; only the *who may void* check changes shape |
| Cash session open/close, `expectedBalance` formula | **Simplifies** | "One open session per cashier" collapses to "one open session per device/shift" — no `cashierUsername` dimension needed |
| `CashRegister` catalog (multiple physical terminals per store) | **Disappears entirely** | Exactly one till exists; no catalog needed |
| TOCTOU unique-index backstop on session open | **Disappears** | No second concurrent actor can race the open call |
| Cash movements (manual deposit/withdrawal) | **Port as-is** | Independent of multi-user |
| Payment methods + `affectsCashBalance` flag | **Port as-is** | Cash-vs-transfer distinction still matters for till reconciliation solo |
| Alternative-currency display conversion + exchange-rate convention | **Port as-is** (explicit ask) | Same UX concept, display-only |
| Multi-tenant store scoping (`StoreContextHolder`, `X-Store-Id`, per-store `INVENTORY` joins) | **Disappears entirely** | Single implicit store |
| JWT auth, login, token expiry, `/auth/select-store` | **Disappears entirely** | No login; a PIN-gate is local authorization, not authentication |
| ADMIN/CASHIER role tables + `@PreAuthorize` | **Simplifies drastically** | Becomes a local boolean/PIN-gated config value with a shared permission-check function reused across screens (product edit, reports) — no user management, no account lifecycle, no password hashing |
| License/JWS enforcement, machine fingerprint, anti-tamper heartbeat | **Ports, adapted for offline mobile** | **Corrected after user clarification**: this app IS sold commercially to multiple businesses, each install is a distinct customer. Needs per-device licensing like the reference system, but adapted since there's no server to heartbeat against — see addendum below |
| Reports (daily-products/cashier, ADMIN-only, `storeId`-scoped) | **Simplifies** | Drop `storeId` param; "ADMIN-only" becomes the PIN gate; still a real v2 feature |

## Concurrency Verdict

**The entire class of race conditions the backend solved disappears — not "simplifies," disappears.** Every hard-concurrency mechanism found in the reference code (`SELECT ... FOR UPDATE` on stock, deadlock-avoidance product-ID lock ordering across concurrent order requests, the unique-index TOCTOU backstop on cash-session open) exists specifically to arbitrate between **multiple simultaneous HTTP requests from different terminals/cashiers hitting the same DB rows**. On a single device, the only writer is the app's own process; SQLite/Room already gives you one connection and transaction-level atomicity for free. The one thing that *does* survive is not a concurrency requirement but a **correctness** requirement: order-create + stock-decrement must be one atomic unit so a crash mid-operation can't leave partial state — Room's `@Transaction` covers that trivially.

The only residual concern worth naming for `sdd-design`: a background job (auto-backup, or a future scheduled report) reading the DB while the UI thread writes. That's solved with single-writer discipline (all writes go through Room DAOs on a serialized executor) — a much lighter pattern than the backend's pessimistic locking, not a re-introduction of the same problem.

## Android Architecture Options

### Persistence — Room vs raw SQLite vs ObjectBox

| Option | Pros | Cons | Effort |
|---|---|---|---|
| Room | Jetpack-official, SQL underneath (matches this domain's inherently relational shape: FKs, joins for order lines, aggregate queries like `sumCashSalesBySessionId`), compile-time query verification, Flow/LiveData integration, huge documentation/community | Manual migration scripts as schema evolves; DAO boilerplate | Low |
| Raw SQLite (`SupportSQLiteOpenHelper`) | Max control | Hand-written SQL, no compile-time verification, no Flow integration, more boilerplate than Room with no offsetting benefit | Medium-High |
| ObjectBox | Very fast benchmarks, automatic migrations, less boilerplate | It's an object-graph NoSQL store — fights a genuinely relational domain (orders JOIN lines JOIN products, cash-session aggregates); smaller community; no Jetpack-blessed Compose/Flow-first story | Medium, with model-fit risk |

**Recommendation: Room.** The domain is relational by nature (this is literally a port of a relational backend schema), and catalog + order-history data volume for a small shop is nowhere near where ObjectBox's raw-speed edge would matter.

### UI toolkit — Compose vs Views/Fragments

| Option | Pros | Cons | Effort |
|---|---|---|---|
| Jetpack Compose | Current Google-recommended default; declarative model matches reactive derived-state screens this app needs (live totals, live currency-conversion rows recalculated per keystroke, live stock); long-term Google investment; less boilerplate for this shape of UI | Learning curve if team has zero Compose experience (unknown); some barcode libs still ship View-based scan Activities requiring `AndroidView` interop (minor, well-documented) | Low-Medium |
| Views/Fragments | Faster if team is already fluent; ZXing's drop-in scan Activity is zero-integration-effort | Imperative UI + manual state sync is a worse fit for live-recalculated totals/conversions; Views are in maintenance mode for new Google guidance; XML+Fragment nav boilerplate | Medium |

**Recommendation: Compose.** Greenfield + no legacy constraint removes the switching-cost argument for Views, and this app's UX (live totals, live conversions, live stock) is Compose's textbook use case.

### DI — Hilt vs manual vs Koin

| Option | Pros | Cons | Effort |
|---|---|---|---|
| Hilt | Jetpack-official, compile-time (KSP) safety, integrates with ViewModel/WorkManager | Codegen overhead, module ceremony that may be disproportionate for a ~15-25 class MVP | Medium |
| Koin | Kotlin DSL, no codegen, fast to learn, popular for small/mid apps | Runtime resolution — DI errors surface at runtime, not compile time; community-maintained, no Google backing | Low |
| Manual (constructor injection + factory functions) | Zero library, zero magic, proportionate to app size | Loses tooling/graph visualization; more manual wiring as scope grows past MVP | Low |

**Recommendation: lean manual DI or Koin for the v1 slice size** — Hilt's ceremony pays off once you have many features sharing scoped dependencies, which a ~7-week single-device MVP likely doesn't hit yet. Not locking this in; flagged for `sdd-design`.

### Barcode scanning — MLKit vs ZXing vs CameraX+MLKit

Verified via web search: ZXing is officially in maintenance mode (security patches only, no active development); MLKit is Google-maintained with regular updates.

| Option | Pros | Cons | Effort |
|---|---|---|---|
| MLKit Barcode Scanning (standalone) | Google-maintained, actively updated, simple API, good accuracy | Some APIs depend on Play Services (a small shop's device almost certainly has this; flag only if targeting GMS-less hardware) | Low |
| ZXing (`zxing-android-embedded`) | No Play Services dependency, drop-in scanning Activity | **Maintenance-mode, no active feature development** — real risk betting a core input flow on it for a fresh 2026 app | Low |
| CameraX + MLKit combo (custom `ImageAnalysis` + on-device MLKit decode) | Most flexible (custom scan UI/reticle matching app design), both pieces actively Google-maintained, clean Compose interop via `PreviewView`, avoids the "takes over the whole screen" UX limit of drop-in libraries | More integration work — you own camera lifecycle/permissions/analyzer wiring | Medium |

**Recommendation: CameraX + MLKit combo** for maintenance longevity and UX control, at moderate (not high) cost given Jetpack's CameraX documentation. MLKit Barcode Scanning API alone is a reasonable simpler fallback if the 7-week timeline is tight and a custom scan UI isn't a v1 priority. **ZXing is not recommended** for a fresh 2026 app given its maintenance status.

### Local backup/export — scoped storage reality

Scoped storage is mandatory since API 29/30+. A raw DB file copy only works reliably inside the app's own sandbox (`context.filesDir`) — trivial there, no permission needed. Getting a backup **out** of the sandbox (to a USB stick, shared folder, cloud) requires the **Storage Access Framework** (`ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`) — Google's sanctioned path for non-media files; MediaStore doesn't apply since a DB export isn't music/photo/video. Two viable export shapes for `sdd-design` to pick between: (a) raw DB file copy — fastest, 1:1 restorable, but coupled to exact Room schema version; (b) structured JSON/CSV export — slower to build, more resilient to schema drift, human-inspectable. Recommendation: **SAF-based export**, not raw filesystem paths outside the sandbox — the latter throws `FileNotFoundException` on any modern Android target and is exactly the category of bug the reference backend never had to think about (server-side DB, no scoped storage).

## MVP-First Slice — options for sdd-propose

- **Slice A (minimum usable end-to-end path)**: product catalog (CRUD + barcode lookup) + inventory as a movement ledger (IN/OUT/ADJUST) + a single shift/cash-session concept (open/close, `expectedBalance`) + sale-only order creation with atomic stock decrement + alt-currency display rows. Defers: returns, void, reports, PIN gate, multiple payment methods, backup/export.
- **Slice B (adds robustness)**: void-with-reason, admin PIN-gate for product edit/reports, additional payment methods with `affectsCashBalance`, SAF-based backup/export.
- **Slice C (adds depth)**: returns flow, daily-product/cashier-equivalent reports, minimum-stock alerts.

## Scope decisions resolved during exploration hand-off

The exploration flagged three open questions. Resolved as follows (orchestrator judgment call, not re-litigated with the user given they're low-ambiguity in context — flagged transparently, subject to correction):

1. **Returns**: real, store-agnostic business logic — kept in scope, but slotted to **Slice C**, not blocking the MVP end-to-end path.
2. **Per-store price override**: not wanted — there's only one store, so the override dimension has nothing to vary against. Confirmed disappears.
3. **License/JWS enforcement**: ~~confirmed disappears~~ **CORRECTED — confirmed IN SCOPE.** The user clarified this app is sold commercially to multiple businesses (each installation is a distinct paying customer), not a one-off personal tool. See addendum below.

## Addendum — license enforcement on a offline, no-network Android device

The reference `license/` module (`D:\Proyectos\idos-pos\license`, see also `CLAUDE.md`'s "License — offline RSA + JWS + machine binding" section) already solves the *offline* half of this problem for desktop — it does not require a live network connection to validate a license, only to receive the `.lic` file once. That property ports well to Android:

- **Signature verification**: RSA-SHA256 JWS verification uses standard `java.security`/`javax.crypto` APIs, both available on Android (with Android's own security provider) — no server round-trip needed to verify, same as today.
- **Distribution of the license file to the device**: since the app has zero network dependency, the file must reach the device out-of-band — e.g. the vendor emails/messages the `.lic` file (or an activation code encoding it) and the store owner imports it via a file picker (Storage Access Framework), or types in a short activation code if the payload is kept small enough to encode as text/QR. A QR-code-based activation (vendor generates a QR containing the signed license, customer scans it with the same camera already used for barcode scanning) is a strong candidate given the app already needs CameraX/MLKit for barcode — worth `sdd-design`'s consideration as it reuses infrastructure instead of adding a new one.
- **Machine/device binding**: the reference system's `MachineFingerprintService` computes a stable per-installation OS-level fingerprint. Android's equivalent is murkier — modern Android restricts stable hardware identifiers for privacy (no reliable `IMEI`/serial without special permissions that Play Store policy discourages). The realistic binding primitive is an app-generated, locally-persisted random UUID created on first launch (an "installation ID," not a hardware fingerprint) — weaker than the desktop's approach (survives app reinstall poorly, doesn't survive a factory reset) but consistent with what's actually available without invasive permissions. `sdd-design` must make this call explicitly and document the weaker guarantee it provides (a user could reinstall to get a fresh installation ID and attempt to reuse an old license) — likely mitigated by making licenses single-activation (the vendor's issuing side records which installation ID has consumed each license) rather than purely technical prevention.
- **Anti-tamper / clock-rollback detection**: the reference `TimeAntiTamperService` relies on a periodic heartbeat comparing wall-clock progress — that still works fully offline (it's already local-only, not a network heartbeat) and ports directly: persist a "last-seen" timestamp locally, flag if `now < lastSeen` on each app-foreground event.
- **Vendor-side signing**: the existing `tools/license-cli/` (standalone signing CLI, private key never leaves the vendor's machine) can very plausibly be reused AS-IS for signing Android licenses too — the JWS payload shape may need Android-specific fields (installation ID instead of desktop machine fingerprint) but the signing mechanism itself doesn't change. This means the vendor-side tooling investment already made for the desktop product is NOT wasted — it extends rather than duplicates.
- **Effort impact**: this is a genuine scope addition, not a wash — estimate roughly +1.5 to +3 calendar weeks on top of the previously discussed ~7-week MVP estimate, covering: on-device JWS verification, activation UI (file import or QR scan), installation-ID generation/display, license-status screen (valid/grace/expired, mirroring the desktop's `LicenseEnforcer` states), and enforcement (blocking app functionality outside a grace period, same UX principle as the desktop's `LicenseEnforcementFilter` but applied to in-app navigation instead of HTTP requests). `sdd-propose` should size this as part of the MVP or explicitly defer it to a fast-following change — given a commercial product without ANY license gate can be freely copied/redistributed from day one, deferring it fully is a real business risk, not just a nice-to-have.

## Risks

- DI and UI-toolkit choices assume no existing team Android experience; if the team already knows Views/Hilt well, effort estimates above should be revisited.
- Backup/export design (raw DB copy vs. structured export) has real tradeoffs on restorability vs. resilience — needs an explicit `sdd-design` decision, not a default.
- If the license-enforcement-drop assumption changes later (e.g. this app gets resold to other stores), that's a much bigger scope addition, not a tweak.

## Ready for Proposal

Yes. The domain-rule mapping, architecture option comparisons, and concurrency verdict give `sdd-propose` enough grounding to scope Slice A concretely.
