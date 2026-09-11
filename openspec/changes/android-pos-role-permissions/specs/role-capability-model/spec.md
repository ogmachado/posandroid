# Role Capability Model Specification

## Purpose

Centralizes session-role authorization behind pure, named predicates on `UserRole` (`permission/RolePermissions.kt`), replacing ad hoc inline `currentRole == UserRole.ADMIN` checks. Predicates are the single source of truth for "may this role do X" and MUST be consulted both where UI is composed and where the corresponding write action executes — UI-only gating is not sufficient enforcement. This is a session-role model; it is a distinct, coexisting mechanism from the `permission-gate` any-ADMIN PIN callover, which it does not replace.

## Requirements

### Requirement: Role Predicates Are The Single Source Of Truth For Session-Role Authorization

The system MUST expose role-authorization decisions as pure, named extension predicates on `UserRole` (at minimum `canManageUsers()`, `canAccessBusinessProfile()`, `canCreateProducts()`). Composition and action code MUST NOT contain inline `role == UserRole.ADMIN`-style comparisons once this capability lands.

#### Scenario: A composition site asks the predicate instead of comparing role directly
- GIVEN a screen or nav function needs to decide whether the current session's role permits an action
- WHEN that decision is made
- THEN it calls a named predicate from `RolePermissions.kt` (e.g. `role.canManageUsers()`) rather than comparing `role == UserRole.ADMIN` inline

#### Scenario: Predicates are pure and testable without Compose or Robolectric
- GIVEN a `UserRole` value
- WHEN a predicate is invoked on it in a plain JVM unit test
- THEN it returns the correct boolean with no Compose, Robolectric, or Android framework dependency

### Requirement: Permission State Is Derived Fresh From The Session, Never Persisted

The system MUST evaluate every predicate against the current session's role at the moment of the check, and MUST NOT cache, persist, or carry a computed permission result over across checks or sessions.

#### Scenario: A predicate reflects a newly-switched session's role, not a prior one
- GIVEN a session previously authenticated as `CASHIER` has ended and a new session authenticated as `ADMIN` has begun
- WHEN a predicate is evaluated for the new session
- THEN it reflects the `ADMIN` role — no stale `CASHIER`-derived result is reused

#### Scenario: No standalone permission table or flag is stored
- GIVEN the app's persisted data
- WHEN it is inspected
- THEN no table, row, or flag stores a precomputed "may do X" permission state — every check reads `UserRole` and evaluates the predicate at call time

### Requirement: Role-Gated Actions Are Enforced At The Action Layer, Not Only By UI Reachability

For any action gated by a role predicate, the corresponding ViewModel or Repository write path MUST itself evaluate the predicate against the caller's role and reject the call when it fails, independent of whether the UI that would normally trigger it was reachable.

#### Scenario: A write path invoked directly, bypassing the UI, still rejects a disallowed role
- GIVEN a role-gated action's ViewModel/Repository function
- WHEN it is invoked directly in a test with a caller role that fails the corresponding predicate
- THEN the action MUST be rejected — its effect (persisted write, navigation, or side effect) MUST NOT occur

#### Scenario: Hiding the triggering UI element alone does not satisfy this requirement
- GIVEN a role-gated action whose UI trigger is hidden for a disallowed role
- WHEN the underlying action function is reachable through any other code path (e.g. a hand-off from another feature)
- THEN the action layer's own predicate check MUST still block it — UI absence alone is not sufficient enforcement

### Requirement: A UI Gating Composable, Where Used, Is Sugar Over The Predicates — Not A Separate Mechanism

A `RoleGate(requires = ...) { content }` Composable, where used, MUST delegate directly to the same `RolePermissions.kt` predicates. It MUST NOT implement or duplicate its own role-comparison logic.

#### Scenario: RoleGate shows content only when the underlying predicate passes
- GIVEN a `RoleGate(requires = RolePermissions::canManageUsers) { ManageUsersButton() }` composition
- WHEN the current session's role fails `canManageUsers()`
- THEN the gated content is not composed
