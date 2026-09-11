# Design: android-pos-role-permissions (Centralized session-role capability model + defense-in-depth enforcement)

## Technical Approach

One new pure file (`permission/RolePermissions.kt`) holding **capability-named extension predicates on `UserRole?`**, plus one thin Composable (`permission/RoleGate.kt`). Every existing session-role decision — the 4 composition sites and the 3 unenforced write paths — is re-expressed as a call to those same predicates. Nothing else about the architecture moves: no schema, no DAO, no entity, no migration, no nav route, no `PinGate` mechanism change.

The change has three independent seams:

1. **Predicate primitive + composition-site rewrites** (behavior-preserving refactor).
2. **Action-layer enforcement** on `AuthRepository.createUser`/`changePin` and the business-profile write path, returning a new `DomainError.NotPermitted` through the codebase's established `Result<T>` + `domainErrorOrNull()` idiom.
3. **PIN-callover scope extension** — `costPrice` joins `price`, and product *creation* joins *edit*, via a single shared pricing predicate in `catalog/`.

Two gating mechanisms stay deliberately distinct, as the proposal requires: **session-role predicates** answer "is my role allowed?"; **`PinGate`** answers "call an ADMIN over" (any-ADMIN, point-in-time, no carry-over — untouched).

## Architecture Decisions

| # | Decision | Choice | Rejected | Rationale |
|---|----------|--------|----------|-----------|
| A | **Where the action layer reads the caller's role** (the proposal's key open risk) | **Session-read, never a parameter.** Every enforcement point resolves the caller from `AuthRepository.currentSession.value?.role` and evaluates the shared predicate. Uniform across all three write paths. | Explicit `role`/`session` param on `createUser`/`changePin`/`save` | (1) **A parameter is forgeable.** The caller decides what role to claim, so defense-in-depth degrades into a convention — a caller that passes `UserRole.ADMIN`, or the wrong session, silently re-opens the exact hole this change exists to close. Session-read means there is one answer to "who am I" and no call site can contradict it. (2) The testability argument does not hold here: `AuthRepository` **owns** `currentSession`, `AuthRepositoryTest` is already Robolectric + in-memory Room, and establishing a CASHIER caller is one line (`login("cashier", pin)`) — cheaper than threading a param, and higher fidelity than a fake. (3) It preserves Decision F/H's "derive fresh from the session, never store or carry permission state". |
| B | **Layer placement of the enforcement**, given Decision A | Lowest point that already reaches the session **without inverting a package dependency**: inside `AuthRepository` for `createUser`/`changePin` (it owns the session — zero new deps); inside **`BusinessProfileViewModel.save`** (`permission/` package, gains an `authRepository` dep) for the business profile. | `BusinessProfileRepository(dao, authRepository)` | `permission` → `business` already exists (`BusinessProfileViewModel` imports `business.BusinessProfileRepository`). Injecting `AuthRepository` into `business/` would add the reverse edge and make the two packages **mutually dependent** — the first package cycle in this codebase. `BusinessProfileViewModel.save` is still *below the UI*: the proposal's success criterion ("reject a CASHIER caller … invoked directly, bypassing the UI") is met by a test that calls the ViewModel with no Compose involved. What must be uniform per the proposal — the **role source** — is uniform (Decision A); the placement is "lowest point reachable without a dependency inversion". |
| C | **One predicate set for both layers** | The UI and the enforcement layer call the **same** predicate for the same capability. No separate "enforcement predicates". | Parallel enforcement-only predicates | If the two layers used different predicates they could disagree — "UI hidden but action open" is precisely the bug class this change closes. One predicate per capability makes divergence impossible by construction. |
| D | **Nullable receiver: `UserRole?.canX()`** | Predicates are extensions on `UserRole?`, returning `false` for `null`. | Extensions on non-null `UserRole` + `?.canX() == true` at call sites | **Fail-closed by construction.** Four of the six call sites hold a nullable role (`MainActivity.currentRole`, `currentSession.value?.role`); a nullable receiver removes all `?.`/`== true` noise and makes "no session ⇒ not permitted" a property of the primitive rather than of each caller. Still a pure, JVM-testable extension function, and it remains callable on a non-null `UserRole` (e.g. `visibleTabsFor`'s param stays non-null and unchanged). |
| E | **`DomainError.NotPermitted` as a `data object`** | One payload-free variant. | `NotPermitted(capability)` / a `Capability` enum | A central `Capability` enum is an explicit non-goal. Stringly-typed payloads buy nothing here: this is a defense-in-depth path an operator should never see, so a generic message is correct. Matches the `PinIncorrect`/`NoOpenSession` precedent. |
| F | **CASHIER barcode block: decide in a pure function, render at `VentaRoute`** | `internal fun unknownBarcodeAction(role, barcode): UnknownBarcodeAction` (`NavigateToCreate` \| `ShowCreationBlocked`) in `PosNavHost.kt`; `VentaRoute(role, …)` renders a compact dismissible inline notice above `PosScreen` on `ShowCreationBlocked`. | Toast/Snackbar; `AlertDialog`; new `PosScreen` params; role check inside `PosScreen` | The real scan path is camera-bound and **untestable under Robolectric** (documented in `BarcodeScanScreen`/`PosNavHostTest`), so the decision must live in a pure function to be testable at all — exactly the `visibleTabsFor` + `VisibleTabsForTest` precedent. Snackbar needs `Scaffold`, and `Scaffold` + dialog is this codebase's documented Robolectric hang trigger; a plain inline `Text` row is the established error idiom (`SALE_ERROR_TEST_TAG`, `FORM_ERROR_TEST_TAG`). Keeping it in `VentaRoute` leaves `PosScreen`'s public API untouched (there is no `PosScreenTest` today) and keeps role knowledge in `nav/`, where `PosNavHost(role)` already lives. **Sale stays usable**: `PosScreen` sets `isScanning = false` *before* invoking `onUnknownBarcode`, so the operator lands back on the live cart with the notice — scan-again and manual entry both keep working, and nothing is cleared. |
| G | **One shared pricing predicate for edit and creation** | `internal fun pricingRequiresCallover(originalPrice: BigDecimal?, originalCostPrice: BigDecimal?, price, costPrice)` — `null` original = creation baseline of `ZERO`. `submitUpdate` and `createProduct` both branch on it. | Separate `costPriceChanged` branch; "always gate creation" | One predicate ⇒ **one prompt** when both fields change (a success criterion), and identical semantics for edit and creation ("did the operator set/change a monetary value?"). The `null`-as-`ZERO` baseline makes creation a special case of the same rule instead of a second rule, and leaves a genuinely zero-priced new product ungated. `compareTo` (not `equals`) preserves the existing scale-insensitive `BigDecimal` comparison. |
| H | **`createProduct` takes `pinGate` as a param** | `ProductViewModel.createProduct(pinGate, …)`, mirroring `submitUpdate`. | Injecting a gate into the ViewModel; gating in `CatalogRepository` | `ProductFormScreen` already receives a `pinGate` and both create/edit nav routes already build one via `rememberPinGate` — so this is a signature change with **zero** nav/DI change. Gating in `CatalogRepository` is rejected: it is shared with the sale flow, and the proposal scopes creation's defense to (a) no reachable CASHIER path + (b) the PIN callover — not a caller-role check in the catalog repository. |

## Interfaces

```kotlin
// permission/RolePermissions.kt — pure, no Compose/Android imports (Decisions C, D)
fun UserRole?.canManageUsers(): Boolean = this == UserRole.ADMIN            // createUser / changePin / users overlay
fun UserRole?.canAccessBusinessProfile(): Boolean = this == UserRole.ADMIN  // profile write path / profile overlay
fun UserRole?.canAccessProductCatalog(): Boolean = this == UserRole.ADMIN   // Productos tab
fun UserRole?.canAccessInventory(): Boolean = this == UserRole.ADMIN        // Inventario tab
fun UserRole?.canCreateProducts(): Boolean = this == UserRole.ADMIN         // barcode hand-off

// permission/RoleGate.kt — UI sugar only, never the enforcement mechanism
@Composable
fun RoleGate(role: UserRole?, requires: UserRole?.() -> Boolean, content: @Composable () -> Unit) {
    if (role.requires()) content()
}
// call site: RoleGate(role = currentRole, requires = { canManageUsers() }) { Row { …header buttons… } }

// permission/AuthRepository.kt (Decisions A, E) — signatures
suspend fun createUser(username: String, pin: String, role: UserRole): Result<Unit>  // unchanged shape
suspend fun changePin(userId: Long, newPin: String): Result<Unit>                    // Unit -> Result<Unit>
// both begin: if (!_currentSession.value?.role.canManageUsers()) return Result.failure(DomainException(DomainError.NotPermitted))

// permission/BusinessProfileViewModel.kt (Decision B)
class BusinessProfileViewModel(
    private val businessProfileRepository: BusinessProfileRepository,
    private val authRepository: AuthRepository,   // NEW
) : ViewModel() {
    suspend fun save(name: String, address: String, phone: String): Result<Unit>  // Unit -> Result<Unit>
}

// nav/PosNavHost.kt (Decision F)
internal sealed interface UnknownBarcodeAction {
    data class NavigateToCreate(val route: String) : UnknownBarcodeAction
    data object ShowCreationBlocked : UnknownBarcodeAction
}
internal fun unknownBarcodeAction(role: UserRole?, barcode: String): UnknownBarcodeAction =
    if (role.canCreateProducts()) UnknownBarcodeAction.NavigateToCreate(productCreateRoute(barcode))
    else UnknownBarcodeAction.ShowCreationBlocked

internal fun visibleTabsFor(role: UserRole): List<PosTab> = posTabs.filter { tab ->   // signature unchanged
    when (tab.route) {
        ROUTE_PRODUCTOS -> role.canAccessProductCatalog()
        ROUTE_INVENTARIO -> role.canAccessInventory()
        else -> true                                   // Venta/Caja: every authenticated role
    }
}

// catalog/ProductViewModel.kt (Decisions G, H)
internal fun pricingRequiresCallover(
    originalPrice: BigDecimal?, originalCostPrice: BigDecimal?,
    price: BigDecimal, costPrice: BigDecimal,
): Boolean = price.compareTo(originalPrice ?: BigDecimal.ZERO) != 0 ||
    costPrice.compareTo(originalCostPrice ?: BigDecimal.ZERO) != 0

// core/domain/DomainError.kt
data object NotPermitted : DomainError
```

UI copy for the block (English, matching this codebase's field/button copy): **"Product not registered. Ask an ADMIN to add it before selling it."**

## Data Flow

```
composition (UX filter — role arrives as a param, derived fresh from the session)
  AppRoot: currentRole = (authGateState as? Authenticated)?.role
    ├─ RoleGate(currentRole) { canManageUsers() }        -> header buttons row      [MainActivity:195]
    ├─ showUserManagement && currentRole.canManageUsers()        -> UserManagementScreen  [:222]
    ├─ showBusinessProfile && currentRole.canAccessBusinessProfile() -> BusinessProfileScreen [:224]
    └─ PosNavHost(role) -> visibleTabsFor(role) -> per-tab predicates              [PosNavHost:341]

enforcement (never trusts its caller — reads the session itself)
  UserManagementScreen -> UserManagementViewModel.createUser/changeOwnPin
        -> AuthRepository.{createUser,changePin}
             -> currentSession.value?.role.canManageUsers() ? proceed : Result.failure(NotPermitted)
  BusinessProfileScreen -> BusinessProfileViewModel.save
             -> currentSession.value?.role.canAccessBusinessProfile() ? repository.save(...) : failure(NotPermitted)

barcode hand-off (Decision F)
  PosScreen scan -> unknown barcode -> isScanning=false -> onUnknownBarcode(code)
        -> unknownBarcodeAction(role, code)
             ADMIN   -> NavigateToCreate      -> navController.navigate(productCreateRoute(code))
             CASHIER -> ShowCreationBlocked   -> inline dismissible notice above the live cart
                                                 (cart intact; scan-again / manual entry still work)

PIN callover (mechanism unchanged — any-ADMIN, point-in-time, no carry-over)
  ProductFormScreen Save
    create -> createProduct(pinGate, …) -> pricingRequiresCallover(null, null, price, cost)
    edit   -> submitUpdate(pinGate, …)  -> pricingRequiresCallover(orig.price, orig.costPrice, price, cost)
        true  -> pinGate.require(perform)  -> PinGateDialog -> verifyAdminPin -> perform()
        false -> perform()
        perform() bumps _saveCompleted only on a non-error result -> LaunchedEffect -> onSaved()
```

## File Changes

| Path | Action | Strict TDD? | Description |
|------|--------|-------------|-------------|
| `permission/RolePermissions.kt` | Create | No (not in scope patterns) | 5 pure `UserRole?` capability predicates |
| `permission/RoleGate.kt` | Create | No (UI) | `RoleGate(role, requires) { content }` |
| `core/domain/DomainError.kt` | Modify | **Yes** (`core/domain/**`) | add `data object NotPermitted` |
| `permission/AuthRepository.kt` | Modify | **Yes** (`**/*Repository.kt`) | `createUser`/`changePin` session-role check; `changePin` → `Result<Unit>` |
| `permission/BusinessProfileViewModel.kt` | Modify | No (`*ViewModel.kt`) | `authRepository` dep; `save` → `Result<Unit>` + role check |
| `permission/BusinessProfileScreen.kt` | Modify | No (`*Screen.kt`) | close only on success; inline error row + test tag |
| `permission/UserManagementViewModel.kt` | Modify | No | `changeOwnPin` surfaces `changePin`'s `Result` via `_lastError` |
| `MainActivity.kt` | Modify | No | 3 inline `== UserRole.ADMIN` → `RoleGate` (header) + predicates (2 `when` branches) |
| `nav/PosNavHost.kt` | Modify | No | `visibleTabsFor` per-tab predicates; `unknownBarcodeAction`; `VentaRoute(role, …)` + blocked notice |
| `catalog/ProductViewModel.kt` | Modify | No | `pricingRequiresCallover`; `createProduct(pinGate, …)` gated; `submitUpdate` branch widened; class-doc reversal |
| `catalog/ProductFormScreen.kt` | Modify | No | pass `pinGate` to `createProduct`; class-doc reversal |
| `openspec/changes/android-pos-auth/specs/{role-based-navigation,user-management,permission-gate}/spec.md` | Amend | — | spec phase |
| `openspec/changes/android-pos-auth-login-first/specs/business-profile/spec.md` | Amend | — | spec phase |
| `openspec/changes/android-pos-role-permissions/specs/role-capability-model/spec.md` | Create | — | spec phase |

## Invariants that MUST survive (regression traps)

1. **`ensureDefaultAdminSeeded()` must not route through `createUser`.** It inserts via `userDao.insert` directly and runs from `PosApplication.onCreate` with **no session** — a session-gated `createUser` would break first-run bootstrap entirely. Verified: it already bypasses `createUser`. Do not "simplify" it into `createUser`.
2. **`_isSaving` must be set *inside* `performCreate`**, not at the top of `createProduct` — the exact reason documented on `submitUpdate`: a dismissed PIN dialog never runs the pending action, so a guard set before the prompt would latch `_isSaving = true` forever and permanently disable Save.
3. **`_saveCompleted` semantics unchanged**: bumped only inside the persistence coroutine on a non-error result, never on `pinGate.require`. This is what makes `ProductFormScreen`'s `LaunchedEffect(saveCompleted)` cover the *new* PIN-delayed create path for free.
4. **`PinGate` itself is not edited.** Only its call arguments and branch conditions change.
5. `visibleTabsFor` keeps its non-null `UserRole` signature and declared tab order (`filter` preserves it) — `VisibleTabsForTest` stays green unmodified.

## Testing Strategy

| Layer | What | Approach |
|-------|------|----------|
| Pure JVM (no Robolectric) | `RolePermissions` — each predicate for ADMIN / CASHIER / `null` | new `RolePermissionsTest`, plain JUnit (the `VisibleTabsForTest` precedent) |
| Pure JVM | `unknownBarcodeAction(role, barcode)`; `pricingRequiresCallover` (price-only / cost-only / both / neither / scale-differing) | new `UnknownBarcodeActionTest`, `ProductPricingCalloverTest` |
| Pure JVM | `visibleTabsFor` per-role tab sets | existing `VisibleTabsForTest`, unmodified |
| Robolectric + in-mem Room (**strict RED/GREEN** — `**/*Repository.kt`, `core/domain/**`) | `AuthRepository.createUser`/`changePin` reject a CASHIER session and a `null` session; still succeed for an ADMIN session | extend `AuthRepositoryTest`; establish the caller with `login(...)` — no fake needed |
| Robolectric + Compose (alongside) | `BusinessProfileViewModel.save` rejects a CASHIER session (called directly, no UI); screen does not close and shows the error | extend `BusinessProfileScreenTest` / new ViewModel test |
| Robolectric + Compose (alongside) | `ProductViewModel.createProduct` opens the gate when pricing is set and persists only after a correct PIN; `submitUpdate` gates on `costPrice`-only change; both-changed prompts once | extend `ProductViewModelTest`, `ProductFormScreenTest` |
| Robolectric + Compose (alongside) | CASHIER shell does not reach product-create via the hand-off; notice renders; ADMIN still navigates | extend `PosNavHostTest` (drives the pure decision + the `VentaRoute` notice; the camera path itself remains untestable) |

**Known test-migration cost** (for `sdd-tasks` to budget, not a blocker): `createUser` becoming session-gated breaks fixtures that call it with no session — `AuthRepositoryTest` (3 tests), `EnforcementGateTest:324`, `UserManagementScreenTest:118,151,152`. Fix is one added `login(admin, DEFAULT_ADMIN_PIN)` per setup (higher fidelity, since those screens are ADMIN-only anyway). `createProduct` gaining `pinGate` + a callover breaks `ProductViewModelTest:178,210,222,288`, which must now also `pinGate.submit(correctPin)`; the `PinGate(verifyPin = { … }, scope = Unconfined)` fake fixture already exists in those files.

## Migration / Rollout

No migration required — no schema, DAO, entity, or nav-route change. Rollback is a plain commit revert per the proposal's rollback plan.

**Review-budget note (for `sdd-tasks`)**: three naturally separable slices — **(1)** `RolePermissions.kt` + `RoleGate.kt` + the 4 composition-site rewrites (behavior-preserving, pure-JVM-tested); **(2)** `DomainError.NotPermitted` + the three action-layer enforcement points + their test-fixture migration; **(3)** the barcode block + the `costPrice`/creation callover extension. Each is independently verifiable and revertible.

## Open Questions

None blocking. Judgment calls left visible for human awareness:

- **Decision B is a deliberate asymmetry** (repository-level for auth, ViewModel-level for business-profile) chosen over creating a `permission ↔ business` package cycle. If a future change moves `UserRole` into `core/`, business-profile enforcement can drop into `BusinessProfileRepository` with no other rework.
- The CASHIER notice at `VentaRoute` level remains visible above the camera preview if the operator immediately re-opens the scanner; it is compact and dismissible. Worth a glance at apply time.
- `changePin` is blanket ADMIN-only (proposal open question 1), so a CASHIER can never change their own PIN. No self-service UI exists today.
