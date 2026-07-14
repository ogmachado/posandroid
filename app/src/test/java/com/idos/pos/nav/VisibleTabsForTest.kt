package com.idos.pos.nav

import com.idos.pos.permission.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test written alongside (task 3.7; `nav/PosNavHost.kt` is UI/alongside per
 * `openspec/config.yaml` `strict_tdd_scope`) covering `role-based-navigation`
 * spec's fixed per-role tab set. Plain JUnit — [visibleTabsFor] is a pure
 * function with no Android/Compose dependency, so no Robolectric runner is
 * needed here.
 */
class VisibleTabsForTest {

    @Test
    fun cashierRole_seesExactlyVentaAndCaja() {
        val routes = visibleTabsFor(UserRole.CASHIER).map { it.route }

        assertEquals(listOf(ROUTE_VENTA, ROUTE_CAJA), routes)
    }

    @Test
    fun adminRole_seesAllFourTabs_inExistingOrder() {
        val routes = visibleTabsFor(UserRole.ADMIN).map { it.route }

        assertEquals(listOf(ROUTE_VENTA, ROUTE_PRODUCTOS, ROUTE_INVENTARIO, ROUTE_CAJA), routes)
    }
}
