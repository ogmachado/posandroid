# Design: android-pos-mvp (Slice A — offline single-device POS core)

## Technical Approach

Single Gradle `:app` module, Kotlin + Jetpack Compose UI, Room (SQLite) persistence,
manual constructor DI, CameraX + MLKit for barcode scan. All firm decisions from
`proposal.md` are locked; this document specifies HOW. The domain rules are ported
from the reference backend (`D:\Proyectos\idos-pos`) as **rules**, not code — no
Spring, no `@Transactional`, no multi-module reactor, no store scoping, no pessimistic
locking (the entire concurrency-arbitration class disappears on a single-writer device
per the exploration's Concurrency Verdict). The one surviving invariant is
**correctness atomicity** for order-create + stock-decrement, covered by Room `@Transaction`.

Layering per feature: `Entity/DAO (Room)` → `Repository (suspend + Flow)` → `ViewModel`
→ `Composable`. Entities never escape a repository; Compose sees UI state models.
Nothing here obstructs bolting on `android-pos-licensing` later (license touches app
startup + an activation/status screen, not domain shape).

## Architecture Decisions

### Decision: Separate `inventory` table (1:1 product) + keep `inventory_movement` ledger

**Choice**: A dedicated `inventory` row per product holding running `stock` + `minimumStock`,
AND a full `inventory_movement` append-only ledger (IN/OUT/ADJUST).
**Alternatives considered**: (a) fold `stock` into `product`; (b) drop the ledger, keep only a mutable counter.
**Rationale**: The reference split `INVENTORY` from `PRODUCT` for multi-tenancy, which is gone
here — but a separate inventory row still earns its place: it keeps the *mutable operational
state* off the catalog row (a sale must not rewrite the catalog entity), and it preserves
`InventoryService` as the single mutation owner (no double-count). The ledger is kept **as-is**
per the exploration's "port as-is" verdict: its audit-trail value (why did stock change, when,
by whom, tied to which order) is independent of multi-tenancy and is the backbone of the future
reports slice. Running stock is a denormalized projection of the ledger, updated in the same
transaction that appends the movement — never mutated independently.

### Decision: Manual DI via `AppContainer` service-locator + Compose bridge

**Choice**: A single `AppContainer` built in `Application.onCreate`, holding the Room DB and one
instance of each repository. Exposed to Compose through a `CompositionLocal`; ViewModels obtain
repositories from a `ViewModelProvider.Factory` that reads the container.
**Alternatives considered**: Hilt (KSP ceremony disproportionate for ~15–25 classes), Koin
(runtime-resolution errors), top-level global singletons (untestable — can't swap fakes).
**Rationale**: Proportionate zero-dependency wiring; `AppContainer` is trivially replaceable with a
test double in Robolectric/instrumented tests. Revisit at Slice B if the graph grows.

### Decision: Typed failure via Kotlin `Result` + sealed `DomainError`, not thrown Spring-style exceptions

**Choice**: Repository/use-case methods return `Result<T>` (or `kotlin.Result`) wrapping a sealed
`DomainError` (`InsufficientStock`, `DuplicateCode`, `PaymentMethodNotFound`, `UnitMeasureNotFound`,
`NoOpenSession`, `SessionAlreadyOpen`, `PinIncorrect`). ViewModels map errors to UI state; no global
exception handler. (`PinIncorrect` alone covers the PIN failure surface — the gate *requests* the PIN
by showing its dialog, so there is no separate "PIN required" error outcome.)
**Alternatives considered**: mirror the backend's exception-per-rule + `@RestControllerAdvice`.
**Rationale**: There is no HTTP boundary to translate exceptions at. A sealed error type is
exhaustively `when`-matchable at the Compose layer, giving compile-time coverage of every failure
the UI must surface — the idiomatic Kotlin equivalent of the reference's `InsufficientStockException`.

### Decision: `expectedBalance` computed LIVE from a Room `Flow` aggregate; snapshot only at close

**Choice**: While a session is OPEN, `expectedBalance` is derived live from a reactive query
(`Flow`) using the exact reference formula. At close, the computed value is *snapshotted* into
persisted columns (`expectedBalance`, `salesTotal`, `difference`).
**Alternatives considered**: recompute on-demand only; persist a running balance mutated per sale.
**Rationale**: The reference system had a live-vs-frozen defect where an OPEN session's persisted
balance columns were null and had to be recomputed ad hoc (`toDtoWithLiveSales`). We avoid repeating
that by making LIVE the *only* source while OPEN (never read stale columns) and freezing exactly once
at close. Compose's reactive model makes the live `Flow` the natural, always-correct display source.

### Decision: Payment-method catalog is read-only (seed-only) in Slice A

**Choice**: The seeded `CASH`/`TRANSFER` payment methods are read-only in Slice A — no admin CRUD
screen. The catalog is populated exactly once by the `onCreate` seeder and never edited in-app.
**Alternatives considered**: mirror the reference backend's `/admin/payment-methods` CRUD behind the
manager-PIN gate.
**Rationale**: The MVP selling path only needs the two seeded methods to *function* (the
`affectsCashBalance` flag drives the cash-session math); nothing in Slice A requires adding or editing
methods. Editing is deferred to Slice B (`android-pos-hardening`) where a PIN-gated admin catalog can
land alongside other management surfaces, keeping MVP scope tight. Consequence: there is **no**
payment-method edit screen in this slice (see File Changes).

## Data Flow — order create (the critical atomic path)

```
ScanScreen/POS ─barcode─▶ ProductRepo.findByBarcode ─▶ cart (ViewModel state)
      │                                                        │
   confirm sale                                                ▼
      └──────────────▶ SalesRepository.createOrder(cart, payment, methodId)
                             │  db.withTransaction {                    (single Room txn spanning DAOs)
                             │    1. resolve payment method: methodId?.let { fail PaymentMethodNotFound if absent } ?: CASH
                             │    2. resolve open CashSession or fail NoOpenSession
                             │    3. nextOrderNumber = max(today)+1
                             │    4. orderId = SalesDao.insertOrderWithLines(order, lines)
                             │    5. for each line (single writer — no lock ordering):
                             │         InventoryDao.applyMovementAtomic(OUT, productId, qty, orderId)
                             │           └─ guard lives HERE: stock<qty (or no row) -> throw InsufficientStock
                             │  }  -> Result<OrderId> | DomainError
                             ▼
                    ViewModel maps Result -> UI (receipt view / error snackbar)
```

Order-create does **not** duplicate stock-mutation logic. The `@Transaction` boundary is the
repository-level `db.withTransaction { }` (Room's suspend transaction API, which spans multiple DAOs
in one unit), calling `SalesDao.insertOrderWithLines` for the order/lines and the SAME
`InventoryDao.applyMovementAtomic` primitive per line for each `OUT` movement — the single stock
mutation owner, identical to the code path a standalone manual movement takes. `InsufficientStock`
(or `PaymentMethodNotFound`) thrown anywhere inside the transaction rolls back the whole unit (Room
propagates the exception and aborts the `withTransaction` block); the repository catches it and
returns `Result.failure`. No partial state can survive a mid-operation crash, and there is exactly
one place stock is decremented.

## File Changes (packages under `com.idos.pos`, single `:app` module)

| Path | Action | Description |
|------|--------|-------------|
| `core/db/PosDatabase.kt` | Create | `@Database` v1, all DAOs, `@TypeConverter`s (Instant↔Long, BigDecimal↔String), seed callback |
| `core/db/Converters.kt` | Create | Money as `BigDecimal` stored TEXT; timestamps as epoch-millis `Long` |
| `core/di/AppContainer.kt` | Create | Builds DB + repositories; held by `PosApplication` |
| `core/di/Locals.kt` | Create | `LocalAppContainer` CompositionLocal + `posViewModel { }` factory helper |
| `core/ui/` | Create | Shared Compose components, `MoneyFormatter`, theme |
| `catalog/{Product,Category,UnitMeasure}Entity.kt`, `*Dao.kt`, `CatalogRepository.kt`, `*ViewModel.kt`, `*Screen.kt` | Create | Product CRUD, category/unit pickers, unique code/barcode. `CatalogRepository.createProduct` validates the `unitMeasureId` reference **before insert** and returns `Result.failure(UnitMeasureNotFound)` if it resolves to no row; duplicate code/barcode → `DuplicateCode`. Seeds a zero-stock `inventory` row on create (no movement) |
| `inventory/InventoryEntity.kt`, `InventoryMovementEntity.kt`, `*Dao.kt`, `InventoryRepository.kt`, screens/VMs | Create | Ledger + running-stock projection; single-owner mutation primitive |
| `sales/{Order,OrderLine,PaymentMethod}Entity.kt`, `*Dao.kt`, `SalesRepository.kt`, POS + cart VMs/screens | Create | Repository-level `db.withTransaction` order-create (routes stock through `InventoryDao.applyMovementAtomic`); daily sequence. `PaymentMethod` is seed-only — a read-only DAO/query for the POS selector, **no edit screen** (editing deferred to Slice B) |
| `cashsession/{CashSession,CashMovement}Entity.kt`, `*Dao.kt`, `CashSessionRepository.kt`, VMs/screens | Create | Open/close, live `expectedBalance` Flow, cash movements |
| `currency/CurrencyEntity.kt`, `CurrencyDao.kt`, `CurrencyRepository.kt` | Create | Display-only conversion rows; `total / exchangeRate` |
| `permission/PinGate.kt`, `PinRepository.kt`, `PinGateDialog.kt` | Create | Hashed manager-PIN, reusable gate composable |
| `scan/BarcodeScanScreen.kt`, `BarcodeAnalyzer.kt` | Create | CameraX + MLKit; `AndroidView(PreviewView)`; permission flow |

## Room schema (v1 — no migration history)

**Naming convention (reconciled after PR2 review):** column names below are Kotlin entity property names. No `@ColumnInfo(name=...)` overrides are used anywhere, so Room generates the actual SQLite column as the camelCase property name verbatim (e.g. `affectsCashBalance`, `exchangeRate`, `displayOrder`), NOT the snake_case shown in earlier drafts of this table. PR2's `PaymentMethodEntity`/`CurrencyEntity` already implement this camelCase-by-default convention; every later phase must follow the same convention for consistency — do not introduce `@ColumnInfo` snake_case overrides for some tables and not others.

| Table | Key | FKs | Indices / constraints |
|-------|-----|-----|-----------------------|
| `unit_measure` | id (auto) | — | unique(`code`) |
| `category` | id | — | unique(`code`) |
| `product` | id | `unitMeasureId` (RESTRICT), `categoryId` (SET NULL, nullable) | **unique(`code`)**, **unique(`barcode`)** (nullable → SQLite allows many NULLs), index(`categoryId`), index(`unitMeasureId`); cols: `name` TEXT, `code` TEXT, `barcode` TEXT(nullable), `price` TEXT(BigDecimal), `costPrice` TEXT(BigDecimal) |
| `inventory` | `productId` (PK = FK, 1:1) | `productId` (CASCADE) | cols: `stock` INT **CHECK(`stock` >= 0)** (DB backstop under the app-level guard), `minimumStock` INT, `updatedAt` |
| `inventory_movement` | id | `productId` (RESTRICT), `orderId` (nullable) | index(`productId`), index(`orderId`), index(`createdAt`); cols: `type` TEXT(IN/OUT/ADJUST), `quantity`, `description`, `createdBy`, `createdAt` |
| `payment_method` | id | — | unique(`code`); cols: `name`, `affectsCashBalance` BOOL, `active` |
| `currency` | id | — | unique(`code`); cols: `name`, `symbol`, `exchangeRate` TEXT(BigDecimal), `active`, `displayOrder` |
| `cash_session` | id | — | index(`status`); cols: `openedAt`, `closedAt`, `openingBalance`, `closingBalance`, `expectedBalance`, `salesTotal`, `difference`, `status` (OPEN/CLOSED) |
| `cash_movement` | id | `sessionId` (CASCADE) | index(`sessionId`); cols: `type` (DEPOSIT/WITHDRAWAL), `amount`, `reason`, `createdAt` |
| `orders` | id | `sessionId` (RESTRICT), `paymentMethodId` (RESTRICT) | index(`sessionId`), index(`createdAt`); cols: `orderNumber` INT, `total`, `payment`, `changeAmount`, `status` (COMPLETED default; VOID reserved for Slice B), `type` (SALE only in Slice A), `createdAt` |
| `order_line` | id | `orderId` (CASCADE), `productId` (RESTRICT) | index(`orderId`), index(`productId`); cols: `quantity`, `price`, `costPrice`, `subtotal` |

Notes: `orders` (not `order` — SQL reserved) mirrors the reference table-name workaround.
Money is `BigDecimal` persisted as TEXT (never `Double`) to preserve exact currency arithmetic.
Daily order number = `SELECT MAX(order_number) WHERE created_at in [todayStart,todayEnd) + 1`,
store-scoping dropped. Per-store price override is dropped (single store) — `order_line.price`
snapshots `product.price` at sale time.

### Transaction boundaries

- `SalesRepository.createOrder(...)` — wraps `db.withTransaction { }` (Room's suspend, multi-DAO
  transaction). It calls `SalesDao.insertOrderWithLines(...)` for the `orders`/`order_line` rows and
  `InventoryDao.applyMovementAtomic(...)` once per line for the `OUT` movement. It never mutates
  `inventory.stock` or inserts `inventory_movement` directly — that is delegated to the single owner.
- `InventoryDao.applyMovementAtomic(movement)` — the ONE stock-mutation primitive, `@Transaction`-
  annotated: append the movement row + update `inventory.stock` (IN `+`, OUT `−`, ADJUST `set`) in one
  DAO transaction. **The negative-stock guard lives HERE** so it applies uniformly to every caller
  (sale line or standalone manual `OUT`): if the target `inventory` row is missing, or `stock < qty`
  for an `OUT`, it throws `InsufficientStock` and persists no movement (Room rolls the DAO transaction
  back). ADJUST rejects negative absolute quantities as invalid input.
- Read aggregates (`cashSalesFlow`, `expectedBalanceComponentsFlow`) are `Flow`-returning `@Query`,
  not `@Transaction`.

## Interfaces / Contracts

```kotlin
sealed interface DomainError {
    data class InsufficientStock(val productId: Long, val available: Int, val requested: Int) : DomainError
    data class DuplicateCode(val code: String) : DomainError
    data class PaymentMethodNotFound(val id: Long) : DomainError   // order references a non-existent payment method
    data class UnitMeasureNotFound(val id: Long) : DomainError     // product references a missing unit of measure
    data object NoOpenSession : DomainError
    data object SessionAlreadyOpen : DomainError
    data object PinIncorrect : DomainError
}

// Single mutation owner for stock — no other class touches inventory.stock.
interface InventoryRepository {
    suspend fun applyMovement(m: NewMovement): Result<Unit>      // IN/OUT/ADJUST (ADJUST is PIN-gated upstream)
    fun stockFlow(productId: Long): Flow<Int>
}

interface SalesRepository {
    suspend fun createOrder(cart: Cart, payment: BigDecimal, methodId: Long?): Result<Long>
}

interface CashSessionRepository {
    fun currentSessionFlow(): Flow<CashSessionView?>             // live expectedBalance while OPEN
    suspend fun open(opening: BigDecimal): Result<Long>          // fails SessionAlreadyOpen
    suspend fun close(counted: BigDecimal): Result<CashSessionView> // snapshots expectedBalance
    suspend fun addMovement(type: CashMovementType, amount: BigDecimal, reason: String?): Result<Unit>
}
```

Live `expectedBalance` (single formula, used by both `currentSessionFlow` and `close`):
`opening + Σ DEPOSIT − Σ WITHDRAWAL + Σ (cash sales where payment_method.affects_cash_balance = 1)`.

## CameraX + MLKit barcode integration

- Dedicated `BarcodeScanScreen` (not a dialog — full camera surface reads best).
  `AndroidView { PreviewView }` bound to a CameraX `Preview` + `ImageAnalysis` use case on the
  lifecycle. `BarcodeAnalyzer : ImageAnalysis.Analyzer` feeds frames to MLKit
  `BarcodeScanning.getClient()`, requesting only 1D formats (EAN/UPC/CODE_128).
- **Permission**: `rememberLauncherForActivityResult(RequestPermission)` on `CAMERA`. Screen shows a
  rationale + "grant" state until permission is held; denied-permanently → settings deep-link hint.
- **Routing**: on first successful decode, analyzer debounces (ignore repeat frames of same value
  within ~1s), emits the raw barcode string up to the ViewModel, which calls
  `CatalogRepository.findByBarcode(code)`. Hit → add to cart / open product. Miss → "unknown barcode"
  prompt offering create-product. Same scanner surface is reusable by `android-pos-licensing` later
  (QR activation) — no rework.

## PIN-gate mechanism

- **Storage**: salted hash (`PBKDF2WithHmacSHA256`, random per-install salt) persisted in
  **EncryptedSharedPreferences** (Jetpack Security). Not Room — the PIN is app-config/security data,
  not domain data, and must not land in DB backups/exports (Slice B). DataStore rejected: no
  at-rest encryption out of the box.
- **Check flow**: `PinGateDialog` composable — a reusable gate wrapping any sensitive action.
  Caller does `PinGate.require { performEdit() }`; the dialog verifies input hash == stored hash,
  invoking the lambda only on match, else surfaces `PinIncorrect`. Invoked before **product price
  edit** and **ADJUST movements** (the two Slice-A sensitive actions). First-run: if no PIN set, a
  one-time "set manager PIN" flow seeds it.
- **Explicit non-goals (Slice A)**: no PIN reset/recovery, no multiple PINs, no lockout/throttling,
  no account/role system. These are deliberately excluded — none is trivial enough to justify silent
  scope creep, and the single-fixed-cashier model doesn't need them yet.

## Testing Strategy

| Layer | What to test | Approach |
|-------|-------------|----------|
| Unit (JVM) | `SalesRepository.createOrder` atomicity + `InsufficientStock` rollback; daily order-number sequence; `expectedBalance` formula; currency conversion (`total/exchangeRate`, rounding); PIN hash verify; code/barcode uniqueness | **Robolectric + in-memory Room** (`Room.inMemoryDatabaseBuilder`) — real DAOs on the JVM, JUnit4 + coroutines-test + Turbine for `Flow` |
| Instrumented (device/emulator) | CameraX/MLKit scan pipeline; EncryptedSharedPreferences PIN storage | AndroidX Test + Espresso; MLKit needs a real device/emulator |
| Compose UI | POS cart totals + live currency rows recompute; `PinGateDialog` blocks/admits; open-session balance updates | `createComposeRule` UI tests (JVM where possible) |

**Testing stack recommendation**: Gradle (KTS) build, JUnit4 + AndroidX Test + Robolectric for
JVM-side Room/business-logic tests, coroutines-test + Turbine for `Flow`, Espresso for the few
instrumented (camera/crypto) cases. Confirm in `openspec/config.yaml` once the skeleton lands.

**Strict TDD Mode recommendation: partial — YES for the domain/repository layer, NO app-wide.**
The atomic order-create, `expectedBalance`, uniqueness guards, PIN verify, and currency math are
pure, deterministic, and high-value — write these tests first (Robolectric+Room is fast). Compose
UI and CameraX scanning are exploratory/device-bound and poorly served by strict red-green-refactor;
cover them with tests written alongside, not before. So set `strict_tdd` scoped to the service/data
layer rather than flipping it globally true.

## Migration / Rollout

No migration — Room schema is v1, greenfield, no production data. Rollback = discard the change
folder + unmerged Slice A commits (per proposal). Seed data (CASH/TRANSFER payment methods; CUP
primary + USD alt currency) loaded via a `RoomDatabase.Callback.onCreate` seeder; default USD rate
is a spec-level detail for `sdd-spec`.

## Open Questions

- [ ] Default CUP↔USD `exchange_rate` seed value — deferred to `sdd-spec` (flagged in proposal).
- [ ] Is a `settings/`-owned single "primary currency" record needed, or is primary currency an
      implicit constant (CUP) in Slice A? Leaning implicit-constant; confirm at spec.
