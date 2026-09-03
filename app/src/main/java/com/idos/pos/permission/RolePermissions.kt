package com.idos.pos.permission

/**
 * Single source of truth for session-role authorization
 * (`android-pos-role-permissions` design.md Decisions C/D;
 * `role-capability-model` spec "Role Predicates Are The Single Source Of
 * Truth For Session-Role Authorization"). Pure extension predicates on
 * [UserRole]? — no Compose/Android imports, plain-JVM-testable — consulted
 * both at composition (UX filter, e.g. [com.idos.pos.nav.PosNavHost]'s
 * `visibleTabsFor`) and at action entry points (e.g. [AuthRepository]'s
 * write paths), so the two layers can never disagree.
 *
 * **Nullable receiver, fail-closed on `null`** (Decision D): every predicate
 * returns `false` when [UserRole]? is `null` — an unauthenticated/no-session
 * caller is never granted a capability. This lets call sites that already
 * hold a nullable role (e.g. `AuthGateState.Authenticated?.role`,
 * `AuthRepository.currentSession.value?.role`) call these directly, with no
 * `?.canX() == true` boilerplate at the call site.
 *
 * Distinct, coexisting mechanism from [PinGate]'s any-ADMIN PIN callover —
 * these predicates answer "may this session's role do X", not "has an ADMIN
 * been called over for this specific action".
 */
fun UserRole?.canManageUsers(): Boolean = this == UserRole.ADMIN

fun UserRole?.canAccessBusinessProfile(): Boolean = this == UserRole.ADMIN

fun UserRole?.canAccessProductCatalog(): Boolean = this == UserRole.ADMIN

fun UserRole?.canAccessInventory(): Boolean = this == UserRole.ADMIN

fun UserRole?.canCreateProducts(): Boolean = this == UserRole.ADMIN
