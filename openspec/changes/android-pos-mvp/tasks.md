# Tasks: android-pos-mvp (Slice A — offline single-device POS core)

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~3500–6000 (new files only; no existing code to diff against) |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR1 → PR8 (see Suggested Work Units) |
| Delivery strategy | ask-on-risk (default — not overridden in this launch) |
| Chain strategy | pending — user decision needed |

Decision needed before apply: Yes
Chained PRs recommended: Yes
Chain strategy: pending
400-line budget risk: High

This is a from-scratch app: ~45 new files (entities, DAOs, repositories, ViewModels,
Compose screens, tests) across 7 capabilities plus bootstrap/DI/seed infra. Treating
it as one PR would produce a diff no reviewer can meaningfully review in one pass.
Chaining is not a downgrade for "just an MVP" — it is required at this size.

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Gradle/Android bootstrap + core DB/DI shell (Phases 0–1) | PR 1 | Base for all; no feature logic |
| 2 | Seeded catalogs + PIN gate primitive (Phases 2–3) | PR 2 | Depends on PR 1 |
| 3 | Product catalog (Phase 4) | PR 3 | Depends on PR 2 (PIN gate, unit/category) |
| 4 | Inventory ledger (Phase 5) | PR 4 | Depends on PR 3 (product FK) + PR 2 (PIN gate) |
| 5 | Barcode scan (Phase 6) | PR 5 | Depends on PR 3 (`findByBarcode`) |
| 6 | Cash session (Phase 7) | PR 6 | Depends on PR 2 (payment method) |
| 7 | Sales order — critical atomic path (Phase 8) | PR 7 | Depends on PR 3, 4, 6 |
| 8 | Currency display + integration pass (Phases 9–10) | PR 8 | Depends on PR 7 |

## Phase 0: Project Bootstrap (no Gradle project exists yet)

- [x] 0.1 Create Gradle Android project, single `:app` module, package `com.idos.pos` (Kotlin DSL)
- [x] 0.2 Pin `minSdk=26`, `compileSdk`/`targetSdk=34` — **assumption**: design.md left this unpinned; 26 covers CameraX/MLKit min + broad device reach
- [x] 0.3 Add Compose BOM + Compose UI/Material3 + activity-compose + navigation-compose deps
- [x] 0.4 Add Room (runtime, ktx, KSP compiler) deps
- [x] 0.5 Add CameraX (core/camera2/lifecycle/view) + MLKit barcode-scanning deps
- [x] 0.6 Add Jetpack Security (EncryptedSharedPreferences) dep for PIN storage
- [x] 0.7 Add kotlinx-coroutines-android dep
- [x] 0.8 Add test deps: JUnit4, Robolectric, AndroidX Test, coroutines-test, Turbine, Espresso
- [x] 0.9 Update `openspec/config.yaml`: confirm testing stack, set `test_command`/`build_command`, scope `strict_tdd` to domain/repo layer per design's testing recommendation
- [ ] 0.10 Commit Gradle wrapper + `.gitignore` — **intentionally left unchecked**: delivery context for this PR1 batch explicitly instructs no commit/stage/push; wrapper + `.gitignore` files exist in the working tree, ready for the user's own review/commit

## Phase 1: Core Infrastructure

- [x] 1.1 Create `core/db/PosDatabase.kt` — `@Database` v1 skeleton (entities added per phase below)
- [x] 1.2 Create `core/db/Converters.kt` — BigDecimal↔TEXT, Instant↔Long
- [x] 1.3 Define `core/domain/DomainError.kt` — sealed interface, all 7 variants per design
- [x] 1.4 Create `core/di/AppContainer.kt` — builds Room DB; repo slots filled per capability phase
- [x] 1.5 Create `core/di/Locals.kt` — `LocalAppContainer` CompositionLocal + `posViewModel {}` factory
- [x] 1.6 Create `PosApplication.kt` — builds `AppContainer` in `onCreate`
- [x] 1.7 RED/GREEN (Robolectric): `AppContainer` builds an in-memory-DB test double for use by every later repo test

## Phase 2: Seeded Catalogs (must exist before sales-order)

- [x] 2.1 `catalog/UnitMeasureEntity.kt` + `UnitMeasureDao.kt` (unique `code`)
- [x] 2.2 `catalog/CategoryEntity.kt` + `CategoryDao.kt` (unique `code`)
- [x] 2.3 `sales/PaymentMethodEntity.kt` + `PaymentMethodDao.kt` — read-only query for POS selector (no edit screen, per design)
- [x] 2.4 `currency/CurrencyEntity.kt` + `CurrencyDao.kt`
- [x] 2.5 RED (Robolectric): seed callback yields `CASH(affectsCashBalance=true)`/`TRANSFER(false)` + active USD currency
- [x] 2.6 GREEN: `RoomDatabase.Callback.onCreate` seeder in `PosDatabase.kt`; seed USD `exchangeRate` as a named, documented, easily-editable constant (placeholder default, e.g. `540`) — NOT a permanent hardcoded business value
- [x] 2.7 Register all 4 entities/DAOs on `PosDatabase`; wire into `AppContainer`

## Phase 3: Permission Gate (standalone; needed before price-edit/ADJUST wiring)

- [x] 3.1 RED (Robolectric): PBKDF2 hash+verify round-trip, correct and incorrect PIN
- [x] 3.2 GREEN: `permission/PinRepository.kt` — EncryptedSharedPreferences-backed hash + verify; first-run "set PIN" path
- [x] 3.3 `permission/PinGateDialog.kt` + `PinGate.require {}` helper (point-in-time check, no session carry-over)
- [x] 3.4 UI test (written alongside): dialog admits on correct PIN, blocks + surfaces `PinIncorrect` on wrong PIN; two gated actions in a row each re-prompt

## Phase 4: Product Catalog

- [x] 4.1 `catalog/ProductEntity.kt` + `ProductDao.kt` — unique(`code`), unique-if-present(`barcode`), FK unit_measure(RESTRICT)/category(SET NULL)
- [x] 4.2 RED (Robolectric): duplicate code rejected, blank barcode normalized to absent, duplicate barcode rejected, missing unit-of-measure rejected, update preserves uniqueness (own-code/own-barcode keep succeeds)
- [x] 4.3 GREEN: `catalog/CatalogRepository.kt` create/update — validates FK + uniqueness, returns `DuplicateCode`/`UnitMeasureNotFound`; seeds zero-stock `inventory` row on create with no movement — **deviation**: the `inventory` table doesn't exist until Phase 5, so the seed call is a marked `TODO(Phase 5 — inventory ledger)` in `CatalogRepository.createProduct` instead of an actual call; wire it once `InventoryRepository`/`InventoryDao` land (see Deviations note below)
- [x] 4.4 RED/GREEN: `findByBarcode` repository query + not-found path
- [x] 4.5 `catalog/ProductViewModel.kt` + `ProductListScreen.kt`/`ProductFormScreen.kt` (category/unit pickers)
- [x] 4.6 Wire price-edit path through `PinGate.require {}` (depends on Phase 3); UI test confirms no bypass path exists
- [x] 4.7 Register `ProductEntity`/`ProductDao` on `PosDatabase`; wire `CatalogRepository` into `AppContainer`

## Phase 5: Inventory Ledger (single mutation primitive; needed before sales-order)

- [x] 5.1 `inventory/InventoryEntity.kt` (1:1 product, `CHECK(stock>=0)`) + `InventoryMovementEntity.kt` + `InventoryDao.kt` — **deviation**: no DB-level `CHECK(stock>=0)` constraint; Room 2.6.1 (this project's pinned version) has no `@Entity(checkConstraints=...)` parameter (that API is a later Room release). The authoritative guard — the one the spec requires — is the app-level check inside `InventoryDao.applyMovementAtomic`, unaffected by this gap
- [x] 5.2 RED (Robolectric): IN creates row from none, OUT decrements, OUT rejects when insufficient/no-row (no mutation, no row persisted), ADJUST sets absolute, ADJUST rejects negative qty — `InventoryDaoTest.kt`
- [x] 5.3 GREEN: `InventoryDao.applyMovementAtomic` `@Transaction` primitive — append movement + update stock in one DAO transaction; throws `InsufficientStock`, rolls back on violation. Also added `DomainError.InvalidMovementQuantity` (9th variant) for ADJUST/negative-quantity rejection — the same kind of DomainError-contract gap `DuplicateBarcode` closed in Phase 4
- [x] 5.4 `inventory/InventoryRepository.kt` — `applyMovement`/`stockFlow` — **deviation**: PIN-gating for ADJUST is wired in `InventoryViewModel` (task 5.5), not inside this repository. `PinGate` is a Compose-state-backed UI primitive (`mutableStateOf`, built via `rememberPinGate`) that a plain suspend repository cannot depend on without coupling this layer to the Compose runtime; the one existing precedent (`ProductViewModel.submitUpdate`, task 4.6) wraps the repository call in `pinGate.require {}` at the ViewModel layer, and this phase follows that exact precedent instead
- [x] 5.5 `inventory/InventoryViewModel.kt` + `InventoryListScreen.kt`/`InventoryMovementFormScreen.kt` (IN/OUT/ADJUST forms); minimum-stock editable independent of a movement, never PIN-gated
- [x] 5.6 Registered `InventoryEntity`/`InventoryMovementEntity`/`InventoryDao` on `PosDatabase`; wired `InventoryRepository` into `AppContainer`. Also closed `CatalogRepository.createProduct`'s Phase 4 `TODO(Phase 5 — inventory ledger)` — now calls `inventoryRepository.seedZeroStock(id)`

## Phase 6: Barcode Scan (depends on Phase 4's `findByBarcode`)

- [x] 6.1 `scan/BarcodeAnalyzer.kt` — MLKit 1D-format analyzer (EAN-13/EAN-8/UPC-A/UPC-E/CODE-128), debounced decode (~1s). Debounce DECISION extracted into a plain `scan/BarcodeDebouncer.kt` class (no MLKit/CameraX dependency) specifically so it's unit-testable in plain JUnit (`BarcodeDebouncerTest`) without a real camera/MLKit pipeline
- [x] 6.2 `scan/BarcodeScanScreen.kt` — CameraX `PreviewView` via `AndroidView` + camera `CAMERA` permission via `rememberLauncherForActivityResult`. Permission lifecycle extracted into a pure `scan/CameraPermissionState.kt` (`resolveCameraPermissionState`, unit-testable in plain JUnit) + a `CameraPermissionGate` composable (rationale/grant UI while ungranted, settings deep-link hint when permanently denied) — covered by a `createComposeRule` UI test (`CameraPermissionGateTest`) exercising the gate directly instead of a real system permission dialog
- [x] 6.3 **Deviation from the literal wording**: `sales/CartViewModel` does not exist yet (lands in Phase 8/PR7, after this PR/PR5), so there is no "cart-add" to wire. Built `scan/ScanViewModel.kt` instead — owns decode → `CatalogRepository.findByBarcode` and exposes `ScanUiState` (`Idle`/`Found(product)`/`NotFound(barcode)`). `BarcodeScanScreen`'s `onProductFound` callback is left as an explicit, documented hook for Phase 8 to wire "add to cart" (same deferred-TODO pattern `CatalogRepository`'s Phase 4→5 inventory-seed TODO used). The "unknown barcode → create product" path on a miss IS wired for real via `onUnknownBarcode` — `catalog/ProductFormScreen.kt` already exists (Phase 4) and gained an `initialBarcode` param to pre-fill the scanned code
- [x] 6.4 Instrumented test written alongside: `androidTest/.../scan/BarcodeScanPipelineTest.kt` generates a real EAN-13 bitmap at runtime (ZXing encoder, `androidTestImplementation`-only dependency) and feeds it through `BarcodeAnalyzer`'s decode seam to confirm the real on-device MLKit pipeline resolves the encoded value. **Unverified in the apply environment for this PR** — no Android SDK/emulator is configured there, only `./gradlew testDebugUnitTest` (JVM/Robolectric) could be run; this file requires `./gradlew connectedAndroidTest` against a real device/emulator. Whoever has one available should run it and confirm before relying on it. Everything that CAN run on the JVM landed as real, passing unit/Robolectric tests instead: `BarcodeDebouncerTest`, `CameraPermissionStateTest`, `ScanViewModelTest`, `CameraPermissionGateTest` (all green under `./gradlew testDebugUnitTest`). Also added `tasks.withType<Test> { forkEvery = 1 }` to `app/build.gradle.kts` — some existing Compose UI tests (`ProductFormScreenTest`, `InventoryMovementFormScreenTest`) intentionally render an `AlertDialog` whose Robolectric Compose idle-detection never fully settles within that test method (documented in their own class docs); left in a shared forked JVM this leaked into later unrelated Compose test classes including the new `CameraPermissionGateTest` and made its `setContent` hang — reproducible only once enough prior Compose tests had run, and not fixed by raising Espresso's idling timeout. Forking one JVM per test class isolates that static state without touching any Phase 3/4/5 test file

## Phase 7: Cash Session (needed before sales-order — order requires an open session)

- [x] 7.1 `cashsession/CashSessionEntity.kt` + `CashMovementEntity.kt` + `CashSessionDao.kt`
- [x] 7.2 RED (Robolectric): single-open-session invariant, movement rejected with none/closed session, second close rejected — `CashSessionDaoTest.kt`
- [x] 7.3 GREEN: `CashSessionRepository.open/close/addMovement` — `SessionAlreadyOpen`/`NoOpenSession` errors. No new `DomainError` variant was needed: `NoOpenSession` (already existed since task 1.3) covers BOTH "no session was ever opened" and "the session exists but is CLOSED" — there is no separate "session not open" variant, matching the instruction to reuse existing variants
- [x] 7.4 RED/GREEN: live `expectedBalance` `Flow` (opening + Σdeposits − Σwithdrawals); snapshot columns (`expectedBalance`, `salesTotal`, `difference`) written only at close — **deviation, ordering caveat**: the full formula per spec.md also adds "Σ cash-affecting sale totals", which requires joining the `orders` table. `orders`/`OrderEntity` don't exist until Phase 8 (PR7), which itself depends on this phase's `CashSessionRepository` (an order can't be created without an open session) — so Phase 7 cannot implement or test that term without inventing order data, which would violate Phase 8's ownership of its own schema (same reasoning as the Phase 4→5 and Phase 5→8 TODOs already in this codebase). Implemented `expectedBalance = openingBalance + Σdeposits − Σwithdrawals` now, with the cash-sales term as an explicit, obvious seam: `computeExpectedBalance(openingBalance, movementsNet, cashSalesTotal = BigDecimal.ZERO)` in the new `cashsession/ExpectedBalance.kt` — a single pure function used by BOTH `CashSessionRepository.currentSessionFlow` (live) and `CashSessionDao.closeSessionAtomic` (snapshot) so the two formulas never drift apart, with `cashSalesTotal` as the one parameter Phase 8 needs to stop defaulting to zero once it adds a `cashSalesFlow`/one-shot query joining `orders`+`payment_method` (`affectsCashBalance = true`). Movements-net itself is computed in Kotlin over `BigDecimal` (never SQL `SUM()`), to avoid SQLite silently widening the TEXT-stored `BigDecimal` money columns to floating point. **Spec scenarios covered**: "Open the first session", "Attempt to open a second session while one is open", "Deposit on an open session", "Movement attempted with no open session", "Movement attempted against a closed session", "Close an already-closed session", "Close with no sales and no movements" (`CashSessionDaoTest`/`CashSessionRepositoryTest`), plus the deposit/withdrawal arithmetic in isolation. **Spec scenarios explicitly DEFERRED to Phase 8** (not fabricated, not silently skipped): "Close with deposits, a withdrawal, and one cash sale" and "Close with a non-cash-affecting sale excluded from the formula" — both require a real order/payment-method-linked sale, which cannot exist until Phase 8 lands `SalesRepository`/`orders`. **DEFERRAL CLOSED (Phase 8, task 8.3)**: now that `SalesRepository`/`orders` exist, `CashSessionDao` gained `cashSaleTotalsForSession`/`cashSaleTotalsForSessionFlow` (a join over `orders`/`payment_method` filtered to `affectsCashBalance = true`, folded in Kotlin over `BigDecimal` — never SQL `SUM()`, per this note's own "never `Double`"-adjacent reasoning), and both `CashSessionRepository.currentSessionFlow` (live) and `CashSessionDao.closeSessionAtomic` (snapshot) now pass the real cash-sales total into `computeExpectedBalance` instead of the old `BigDecimal.ZERO` default. Both previously-deferred scenarios are now covered as new tests in `CashSessionRepositoryTest.kt`; `CashSessionDaoTest.kt` is left unchanged, still covering only the movements-only arithmetic in isolation (the DAO-level test class was never in Phase 8's explicit scope for this closure — see tasks.md Phase 8, task 8.3)
- [x] 7.5 `cashsession/CashSessionViewModel.kt` + open/close/movement `Screen.kt`s showing live balance — `CashSessionOpenScreen.kt`, `CashSessionScreen.kt` (live `expectedBalance` + movement entry), `CashSessionCloseScreen.kt`. None of these actions are PIN-gated (design.md's two Slice-A sensitive actions are product-price-edit and ADJUST movements only). No dedicated Compose UI test was added for these screens — matches the existing precedent that not every `Screen.kt` in this codebase has one (e.g. `InventoryListScreen.kt`/`ProductListScreen.kt` don't either); `Screen.kt`/`ViewModel.kt` are explicitly outside `strict_tdd_scope` per `openspec/config.yaml`
- [x] 7.6 Register cash_session/cash_movement entities/DAO on `PosDatabase`; wire `CashSessionRepository` into `AppContainer`

## Phase 8: Sales Order — the critical atomic path (depends on Phases 2, 4, 5, 7)

- [x] 8.1 `sales/OrderEntity.kt` + `OrderLineEntity.kt` + `SalesDao.kt` (`insertOrderWithLines`)
- [x] 8.2 RED (Robolectric): atomic two-line success (stock+lines persisted), one-line failure rolls back whole order and both products' stock, daily order-number resets across a day boundary, payment method defaults/explicit/not-found, no-open-session rejection — `SalesRepositoryTest.kt` (+ `SalesDaoTest.kt` for the DAO-level insert/range-query mechanics, `DayBoundaryTest.kt` for the pure day-arithmetic in isolation, mirroring the `InventoryDaoTest`/`InventoryRepositoryTest` split)
- [x] 8.3 GREEN: `sales/SalesRepository.createOrder` — `db.withTransaction {}` spanning `SalesDao.insertOrderWithLines` + per-line `InventoryDao.applyMovementAtomic(OUT)`; resolves payment method + open session; daily sequence query via `sales/DayBoundary.kt`'s `todayRange(clock)`, injected via a `Clock` constructor parameter (default `Clock.systemDefaultZone()`) so the day-boundary arithmetic is testable independent of the JVM default timezone/actual date. **Also closes the Phase 7 → Phase 8 seam** (see Phase 7's 7.4 note below): `CashSessionDao` gained `cashSaleTotalsForSession`/`cashSaleTotalsForSessionFlow` (join `orders`/`payment_method` filtered to `affectsCashBalance = true`, folded in Kotlin over `BigDecimal` via `ExpectedBalance.kt`'s new `sumMoney()` — never SQL `SUM()`), and both `CashSessionRepository.currentSessionFlow` (live, via `combine`) and `CashSessionDao.closeSessionAtomic` (snapshot) now pass the real total instead of the old `BigDecimal.ZERO` default. The two previously-deferred spec scenarios ("Close with deposits, a withdrawal, and one cash sale" and "Close with a non-cash-affecting sale excluded") are now covered in `CashSessionRepositoryTest.kt`
- [x] 8.4 `sales/CartViewModel.kt` + `sales/PosScreen.kt` — cart building, confirm-sale, error snackbar mapped from `DomainError`. **Also closes the Phase 6 scan-to-cart seam**: `PosScreen` renders `scan/BarcodeScanScreen` with `onProductFound = { product -> viewModel.addToCart(product) }` — a real call site for the hook `ScanViewModel`/`BarcodeScanScreen`'s class docs left open since Phase 6 (PR5); `CartViewModel.addToCart` snapshots `product.price`/`costPrice` into the `CartLine` at add-to-cart time (design.md: "`order_line.price` snapshots `product.price` at sale time") and merges repeated adds of the same product into one line (quantity++) rather than duplicating lines. Covered by `CartViewModelTest.kt` (tests written alongside, per `strict_tdd_scope`'s `ViewModel.kt` exclusion); no dedicated Compose UI test for `PosScreen.kt` itself, matching the existing precedent that not every `Screen.kt` has one (e.g. `CashSessionScreen.kt`, task 7.5's note)
- [x] 8.5 Registered `orders`/`order_line` entities/`SalesDao` on `PosDatabase`; wired `SalesRepository` into `AppContainer`

## Phase 9: Currency Display (seed from Phase 2; UI attaches to Phase 8's order screen)

- [x] 9.1 RED/GREEN (Robolectric + in-memory Room + Turbine, `CurrencyRepositoryTest.kt`): `currency/CurrencyRepository.kt` — `activeCurrenciesFlow` (thin pass-through over `CurrencyDao.findActiveFlow`'s `active = 1 ORDER BY displayOrder` query) + `convert(total, currency)` = `total / exchangeRate`. **Rounding convention deviation note**: no existing rounding convention for *division* was found elsewhere in the codebase (money elsewhere is only ever added/subtracted via `sumMoney()`/`netAmount()` in `cashsession/ExpectedBalance.kt` — never divided), so `RoundingMode.HALF_UP` scale 2 was introduced here per this task's own explicit instruction, not carried over from a prior file
- [x] 9.2 Compose UI test written alongside (`CurrencyConversionRowTest.kt`; `strict_tdd_scope` excludes `**/*Screen.kt`/`**/*ViewModel.kt` — this composable is neither, but is pure presentational Compose UI, so it follows the same "written alongside" convention as every other Compose UI test in this codebase, e.g. `ProductFormScreenTest.kt`): confirms `currency/CurrencyConversionRow.kt`'s `CurrencyConversionRows` composable recomputes live when `amount` changes (mutable Compose state, no recomposition-caching), and that an inactive currency renders no row at all (`assertDoesNotExist`)
- [x] 9.3 Added `currency/CurrencyConversionRow.kt`'s `CurrencyConversionRows` composable to `sales/PosScreen.kt`, below the total row AND below a live change-amount row. **Deviation from the literal task wording**: `PosScreen.kt` had no live "change" display at all before this task — only `CartViewModel.confirmSale`'s persisted `OrderEntity.changeAmount`, computed post-confirm. Rendering a conversion row "below the change amount" requires a change amount to exist pre-confirm, so a live preview (`parsedPayment - cart.total`, recomputed from the payment `OutlinedTextField` on every keystroke, never persisted) was added as the minimal vehicle for that row rather than silently dropping half the requirement — documented inline in `PosScreen.kt`'s class doc

## Phase 10: Integration & Verification

- [x] 10.1 End-to-end Robolectric pass (`EndToEndPosFlowTest.kt`, package `com.idos.pos`, wired through the real `AppContainer` DI graph): scan (via `CatalogRepository.findByBarcode`, the same call `ScanViewModel` makes once MLKit hands it a decoded string) → cart (`CartViewModel.addToCart`) → sale (`CartViewModel.confirmSale`, the real atomic `SalesRepository.createOrder` path) → stock decrement (verified via `InventoryDao.findByProductId`/movement count) → cash deposit → session close (`CashSessionRepository.close`, verified `expectedBalance` includes the cash sale) → alt-currency conversion row against the persisted order total. **Confirmed no Android SDK/emulator is configured in this apply environment** (no `adb`/connected device; `./gradlew testDebugUnitTest`, JVM/Robolectric only, is what actually ran) — so this is the Robolectric half of "Robolectric/instrumented," substituting the barcode-scan step's domain-level lookup for the literal camera/MLKit decode (which `BarcodeScanPipelineTest.kt`, task 6.4, already documented as `connectedAndroidTest`-only and unverified in this same environment). See the test class's own doc for the full proposal Success-Criteria traceability, including which two criteria are NOT exercised here (PIN-gating, license absence) because they are already covered by their own dedicated test classes
- [x] 10.2 Recorded bootstrap decisions (minSdk 26 / compileSdk+targetSdk 34, package `com.idos.pos`, seed exchange-rate placeholder `540`) in `openspec/project.md`'s new "Bootstrap Decisions (recorded PR8/Phase 10)" section
