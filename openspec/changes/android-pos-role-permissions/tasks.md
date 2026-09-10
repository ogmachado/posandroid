# Tasks: android-pos-role-permissions (Centralized session-role capability model + defense-in-depth enforcement)

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~650-900 (2 new pure files + 1 new `DomainError` variant + 3 action-layer enforcement points + 1 barcode-hand-off decision function + 1 shared pricing predicate + 4 composition-site rewrites, each with matching new/extended tests, plus mandatory test-fixture migration across 6 existing test classes) |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 -> PR 3 (see Suggested Work Units) |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: No — resolved (stacked-to-main)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

This change touches 10 existing files plus 2 new ones across three genuinely separate concerns (behavior-preserving predicate refactor, session-gated write-path enforcement with a breaking-signature test-fixture migration, and a new pricing-callover branch plus a new nav decision function). One PR would bury a reviewer in unrelated diffs — a pure refactor, a security-relevant enforcement change, and a UX/pricing behavior change all read differently. Matches the `android-pos-auth`/`android-pos-licensing` precedent for chaining at this size.

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | `RolePermissions.kt` + `RoleGate.kt` + the 4 composition-site rewrites (Phase 1) | PR 1 | No enforcement-layer change; behavior-preserving, pure-JVM-tested. Base for PR 2 and PR 3. |
| 2 | `DomainError.NotPermitted` + the 3 action-layer enforcement points + their test-fixture migration (Phase 2) | PR 2 | Depends on PR 1 (`RolePermissions.kt` predicates). Independent of PR 3. |
| 3 | Barcode block + `costPrice`/creation PIN-callover extension (Phase 3) | PR 3 | Depends on PR 1 (`canCreateProducts()`). Independent of PR 2 — may reorder with it. |

## Phase 1: Predicate Primitive + Composition-Site Rewrites (Work Unit 1 / PR 1)

- [x] 1.1 Create `permission/RolePermissions.kt` — 5 pure extension predicates on `UserRole?` (`canManageUsers`, `canAccessBusinessProfile`, `canAccessProductCatalog`, `canAccessInventory`, `canCreateProducts`), each `= this == UserRole.ADMIN`. No Compose/Android imports (Decision D — nullable receiver, fail-closed on `null`).
- [x] 1.2 Test (alongside, pure JVM, not strict-TDD scope): new `RolePermissionsTest.kt` — each of the 5 predicates returns `true` for `ADMIN`, `false` for `CASHIER`, `false` for `null`.
- [x] 1.3 Create `permission/RoleGate.kt` — `@Composable fun RoleGate(role: UserRole?, requires: UserRole?.() -> Boolean, content: @Composable () -> Unit)` delegating to `role.requires()` only (Decision C — no separate role-comparison logic).
- [x] 1.4 `MainActivity.kt` (`AppRoot`) — replace the header buttons row's inline `currentRole == UserRole.ADMIN` check with `RoleGate(role = currentRole, requires = { canManageUsers() })`; replace `showUserManagement && currentRole == UserRole.ADMIN` with `showUserManagement && currentRole.canManageUsers()`; replace `showBusinessProfile && currentRole == UserRole.ADMIN` with `showBusinessProfile && currentRole.canAccessBusinessProfile()`.
- [x] 1.5 `nav/PosNavHost.kt` — rewrite `visibleTabsFor(role)`'s `when` branches to call `role.canAccessProductCatalog()` for `ROUTE_PRODUCTOS` and `role.canAccessInventory()` for `ROUTE_INVENTARIO`. Keep the non-null `UserRole` param and declared tab order unchanged (Invariant 5).
- [x] 1.6 Regression check (alongside): run existing `VisibleTabsForTest.kt` unmodified — must stay green, confirming the rewrite is behavior-preserving. **Verification caveat**: could not execute `./gradlew testDebugUnitTest` in the apply sandbox — `debugUnitTestCompileClasspath` fails to resolve pre-existing `androidx.test`/Compose-UI-test artifacts from `dl.google.com` (confirmed network-level 404 on the sandbox, reproduced identically on the unmodified base commit via `git stash` — pre-existing, unrelated to this diff). `:app:compileDebugKotlin` (main sourceset, includes all Phase 1 files) succeeds. `visibleTabsFor`'s new per-tab-route branch is verified by code-level equivalence to read exactly the same truth table `VisibleTabsForTest` asserts (CASHIER: Venta+Caja only; ADMIN: all four, same order) — must be re-run by CI or a network-unrestricted environment before merge.

## Phase 2: Action-Layer Enforcement + Test-Fixture Migration (Work Unit 2 / PR 2) — DONE

- [x] 2.1 RED (`**/*Repository.kt`, `core/domain/**` — strict TDD): extend `AuthRepositoryTest.kt` — `createUser` invoked with a `CASHIER` (or `null`) session is rejected, returns `Result.failure` wrapping `DomainError.NotPermitted`, no user row persisted; `changePin` invoked with a `CASHIER` (or `null`) session is rejected, target user's stored credential unchanged; both still succeed for an `ADMIN` session. **Verified RED**: `./gradlew :app:compileDebugUnitTestKotlin` failed on `Unresolved reference: NotPermitted` (5 errors) — confirmed failing for the right reason before any production code changed.
- [x] 2.2 GREEN: add `data object NotPermitted : DomainError` to `core/domain/DomainError.kt`. In `permission/AuthRepository.kt`, `createUser` and `changePin` each begin `if (!_currentSession.value?.role.canManageUsers()) return Result.failure(DomainException(DomainError.NotPermitted))`; change `changePin`'s return type from `Unit` to `Result<Unit>`. **Verified GREEN**: `./gradlew :app:testDebugUnitTest --tests "com.idos.pos.permission.AuthRepositoryTest"` — 18/18 passing, 0 failures (test-execution environment issue from Phase 1 did NOT reproduce this batch — see Issues Found).
- [x] 2.3 Mandatory, same PR (build/test-breaking otherwise): added `login(admin, DEFAULT_ADMIN_PIN)` to setup in the 3 pre-existing `AuthRepositoryTest` cases that call `createUser` with no session, and to `EnforcementGateTest.kt`'s `seedCashierAndLogin` helper (line ~324). `UserManagementScreenTest.kt:118,151,152` needed NO change — its `setUp()` already establishes an ADMIN session (`ensureDefaultAdminSeeded()` + `login(...)`) before every test for unrelated reasons, so those `createUser` calls already run under an ADMIN session.
- [x] 2.4 Regression check (Invariant 1): confirmed `ensureDefaultAdminSeeded()` still inserts via `userDao.insert` directly (not `createUser`) — read `AuthRepository.kt` before editing; untouched by this change.
- [x] 2.5 `permission/UserManagementViewModel.kt` — `changeOwnPin` surfaces `changePin`'s new `Result<Unit>` via `_lastError`, mirroring `createUser`'s existing error-surfacing pattern.
- [x] 2.6 `permission/BusinessProfileViewModel.kt` — added `authRepository: AuthRepository` constructor dep; changed `save(name, address, phone)` return type from `Unit` to `Result<Unit>`; begins `if (!authRepository.currentSession.value?.role.canAccessBusinessProfile()) return Result.failure(DomainException(DomainError.NotPermitted))`, else delegates to `businessProfileRepository.save(...)`.
- [x] 2.7 `permission/BusinessProfileScreen.kt` — confirm button now awaits `save`'s `Result`, closes only on success, and renders an inline error row (`BUSINESS_PROFILE_ERROR_TEST_TAG`) on rejection, mirroring `SALE_ERROR_TEST_TAG`/`FORM_ERROR_TEST_TAG`.
- [x] 2.8 `core/di/AppContainer.kt` — **no diff required**: `AppContainer` already exposes `authRepository` as a lazy val consumed identically by both `create()` and `createInMemory()`, and `BusinessProfileViewModel` is constructed via `posViewModel`'s reflection-based `AppContainer`-constructor factory (`core/di/Locals.kt`), not by `AppContainer` directly. The actual wiring point was `BusinessProfileViewModel`'s own secondary constructor (task 2.6), which now passes `container.authRepository` through. Confirmed by reading `Locals.kt`/`AppContainer.kt` before concluding — see Deviations.
- [x] 2.9 Test (alongside, Robolectric + Compose): extended `BusinessProfileScreenTest.kt` — `setUp()` now seeds+logs in an ADMIN session (required by the new gate); new test `cashierSession_invokingSave_isRejected_screenStaysOpenWithError` logs in as CASHIER, invokes `save` via the UI, and asserts the screen does not close, the error row renders, and the business-profile row stays unset. Existing ADMIN-path tests (`savingProfile_persistsNameAddressPhone`, `existingProfile_preLoadsIntoFields`) still pass unmodified in behavior (only `setUp` changed).

## Phase 3: Barcode Block + costPrice/Creation PIN-Callover Extension (Work Unit 3 / PR 3)

- [x] 3.1 `catalog/ProductViewModel.kt` — add `internal fun pricingRequiresCallover(originalPrice: BigDecimal?, originalCostPrice: BigDecimal?, price: BigDecimal, costPrice: BigDecimal): Boolean` (Decision G — `null` original = `ZERO` baseline, `compareTo` not `equals`, scale-insensitive).
- [x] 3.2 Test (alongside, pure JVM): new `ProductPricingCalloverTest.kt` — price-only changed, cost-only changed, both changed (single `true`), neither changed, scale-differing values (e.g. `100` vs `100.00`) treated as unchanged.
- [x] 3.3 `catalog/ProductViewModel.kt` — `createProduct` takes `pinGate` as a param (mirrors `submitUpdate`); branches on `pricingRequiresCallover(null, null, price, costPrice)`: `true` -> `pinGate.require(::performCreate)`, `false` -> `performCreate()` directly. Invariant 2: set `_isSaving = true` INSIDE `performCreate`, never at the top of `createProduct`. Invariant 3: bump `_saveCompleted` only inside the persistence coroutine on a non-error result. Invariant 4: do not modify `PinGate.kt` itself — only call arguments/branch conditions change.
- [x] 3.4 `catalog/ProductViewModel.kt` — widen `submitUpdate`'s existing gate condition to `pricingRequiresCallover(originalPrice, originalCostPrice, price, costPrice)` so a `costPrice`-only change also triggers the callover; reverse the class doc describing the old price-only/edit-only scope.
- [x] 3.5 `catalog/ProductFormScreen.kt` — pass the existing `pinGate` (already built via `rememberPinGate` in the create/edit nav routes) into `createProduct(pinGate, …)`; reverse the class doc describing the old scope.
- [x] 3.6 Mandatory, same PR (build/test-breaking otherwise): update `ProductViewModelTest.kt:178,210,222,288` to add `pinGate.submit(correctPin)` for creation-path assertions now that `createProduct` requires a gate, reusing the existing `PinGate(verifyPin = { … }, scope = Unconfined)` fixture already present in that file.
- [x] 3.7 Test (alongside, Robolectric + Compose): extend `ProductViewModelTest.kt`/`ProductFormScreenTest.kt` — `createProduct` opens the PIN gate when `price`/`costPrice` is set and persists only after a correct PIN; a zero-priced creation is ungated; both `price` and `costPrice` changing together (edit or creation) prompts exactly once.
- [ ] 3.8 `nav/PosNavHost.kt` — add `internal sealed interface UnknownBarcodeAction` (`NavigateToCreate(route: String)` / `ShowCreationBlocked` data object) and `internal fun unknownBarcodeAction(role: UserRole?, barcode: String): UnknownBarcodeAction` calling `role.canCreateProducts()`.
- [ ] 3.9 Test (alongside, pure JVM): new `UnknownBarcodeActionTest.kt` — `ADMIN` returns `NavigateToCreate(productCreateRoute(barcode))`; `CASHIER` and `null` both return `ShowCreationBlocked`.
- [ ] 3.10 `nav/PosNavHost.kt`'s `VentaRoute(role, …)` — on `ShowCreationBlocked`, render a compact dismissible inline notice above `PosScreen`: "Product not registered. Ask an ADMIN to add it before selling it."; on `NavigateToCreate`, `navController.navigate(route)` as today. Confirm `PosScreen` already sets `isScanning = false` before invoking `onUnknownBarcode` — cart stays intact, scan-again/manual entry keep working.
- [ ] 3.11 Test (alongside, Robolectric + Compose): extend `PosNavHostTest.kt` — a `CASHIER` session scanning an unknown barcode does not navigate to product-create and the inline notice renders with the cart intact; an `ADMIN` session still navigates to product-create.
- [ ] 3.12 End-to-end regression pass covering this change's success criteria: CASHIER barcode block engaged, ADMIN unaffected; `costPrice`/creation PIN callover enforced with a single prompt when both fields change; `PinGate.kt` itself unmodified; `ensureDefaultAdminSeeded` still bypasses `createUser`; `VisibleTabsForTest` stays green unmodified.
