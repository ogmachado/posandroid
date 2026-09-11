package com.idos.pos.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test written alongside (`permission/RolePermissions.kt` is outside
 * `openspec/config.yaml`'s `strict_tdd_scope` include patterns). Plain
 * JUnit — every predicate is a pure `UserRole?` extension with no
 * Android/Compose dependency, so no Robolectric runner is needed here
 * (`VisibleTabsForTest` precedent).
 *
 * Covers `role-capability-model` spec "Role Predicates Are The Single
 * Source Of Truth For Session-Role Authorization": each predicate returns
 * `true` for ADMIN, `false` for CASHIER, and `false` for `null`
 * (fail-closed, design.md Decision D).
 */
class RolePermissionsTest {

    @Test
    fun canManageUsers_trueForAdmin_falseForCashierAndNull() {
        assertTrue(UserRole.ADMIN.canManageUsers())
        assertFalse(UserRole.CASHIER.canManageUsers())
        assertFalse(null.canManageUsers())
    }

    @Test
    fun canAccessBusinessProfile_trueForAdmin_falseForCashierAndNull() {
        assertTrue(UserRole.ADMIN.canAccessBusinessProfile())
        assertFalse(UserRole.CASHIER.canAccessBusinessProfile())
        assertFalse(null.canAccessBusinessProfile())
    }

    @Test
    fun canAccessProductCatalog_trueForAdmin_falseForCashierAndNull() {
        assertTrue(UserRole.ADMIN.canAccessProductCatalog())
        assertFalse(UserRole.CASHIER.canAccessProductCatalog())
        assertFalse(null.canAccessProductCatalog())
    }

    @Test
    fun canAccessInventory_trueForAdmin_falseForCashierAndNull() {
        assertTrue(UserRole.ADMIN.canAccessInventory())
        assertFalse(UserRole.CASHIER.canAccessInventory())
        assertFalse(null.canAccessInventory())
    }

    @Test
    fun canCreateProducts_trueForAdmin_falseForCashierAndNull() {
        assertTrue(UserRole.ADMIN.canCreateProducts())
        assertFalse(UserRole.CASHIER.canCreateProducts())
        assertFalse(null.canCreateProducts())
    }
}
