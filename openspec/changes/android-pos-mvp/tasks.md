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

- [ ] 7.1 `cashsession/CashSessionEntity.kt` + `CashMovementEntity.kt` + `CashSessionDao.kt`
- [ ] 7.2 RED (Robolectric): single-open-session invariant, movement rejected with none/closed session, second close rejected
- [ ] 7.3 GREEN: `CashSessionRepository.open/close/addMovement` — `SessionAlreadyOpen`/`NoOpenSession` errors
- [ ] 7.4 RED/GREEN: live `expectedBalance` `Flow` (opening + Σdeposits − Σwithdrawals + Σcash-affecting sales); snapshot columns written only at close, per formula scenarios
- [ ] 7.5 `cashsession/CashSessionViewModel.kt` + open/close/movement `Screen.kt`s showing live balance
- [ ] 7.6 Register cash_session/cash_movement entities/DAO on `PosDatabase`; wire `CashSessionRepository` into `AppContainer`

## Phase 8: Sales Order — the critical atomic path (depends on Phases 2, 4, 5, 7)

- [ ] 8.1 `sales/OrderEntity.kt` + `OrderLineEntity.kt` + `SalesDao.kt` (`insertOrderWithLines`)
- [ ] 8.2 RED (Robolectric): atomic two-line success (stock+lines persisted), one-line failure rolls back whole order and both products' stock, daily order-number resets across a day boundary, payment method defaults/explicit/not-found, no-open-session rejection
- [ ] 8.3 GREEN: `sales/SalesRepository.createOrder` — `db.withTransaction {}` spanning `SalesDao.insertOrderWithLines` + per-line `InventoryDao.applyMovementAtomic(OUT)`; resolves payment method + open session; daily sequence query
- [ ] 8.4 `sales/CartViewModel.kt` + `sales/PosScreen.kt` — cart building, confirm-sale, error snackbar mapped from `DomainError`
- [ ] 8.5 Register `orders`/`order_line` entities/DAO on `PosDatabase`; wire `SalesRepository` into `AppContainer`

## Phase 9: Currency Display (seed from Phase 2; UI attaches to Phase 8's order screen)

- [ ] 9.1 RED/GREEN: `currency/CurrencyRepository.kt` — `activeCurrenciesFlow`, `total/exchangeRate` conversion with rounding
- [ ] 9.2 Compose UI test (written alongside): conversion row recomputes live on total change; inactive currency renders no row
- [ ] 9.3 Add conversion-row rendering to `PosScreen.kt`/order-detail composable, below total and below change amount

## Phase 10: Integration & Verification

- [ ] 10.1 End-to-end Robolectric/instrumented pass: scan → cart → sale → stock decrement → session close, against proposal Success Criteria
- [ ] 10.2 Record bootstrap decisions (minSdk/targetSdk, package name, seed exchange-rate placeholder) in `openspec/project.md`
