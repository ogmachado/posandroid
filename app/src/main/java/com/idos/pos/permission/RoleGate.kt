package com.idos.pos.permission

import androidx.compose.runtime.Composable

/**
 * UI sugar over [RolePermissions] (design.md Decision C; `role-capability-model`
 * spec "A UI Gating Composable, Where Used, Is Sugar Over The Predicates") —
 * NEVER the enforcement mechanism itself. Renders [content] only when
 * [role] satisfies [requires], evaluated by calling [requires] as an
 * extension receiver on [role] so callers pass one of the named predicates
 * from `RolePermissions.kt` directly (e.g. `requires = { canManageUsers() }`)
 * instead of re-deriving role-comparison logic here.
 */
@Composable
fun RoleGate(
    role: UserRole?,
    requires: UserRole?.() -> Boolean,
    content: @Composable () -> Unit,
) {
    if (role.requires()) {
        content()
    }
}
