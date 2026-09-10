package com.idos.pos.nav

import com.idos.pos.permission.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test written alongside (`nav/PosNavHost.kt` is outside
 * `openspec/config.yaml`'s `strict_tdd_scope` include patterns).
 * [unknownBarcodeAction] itself has no Compose dependency, but it delegates
 * to [productCreateRoute], which calls `android.net.Uri.encode` — a real
 * Android framework call that is not stubbed under a plain (non-Robolectric)
 * JUnit run, unlike `visibleTabsFor`/`RolePermissions`' truly Android-free
 * predicates. `RobolectricTestRunner` is used here (rather than a plain
 * `JUnit4` runner) purely to make that one framework call resolvable.
 *
 * Covers `android-pos-role-permissions` design.md Decision F: `ADMIN` may
 * create the missing product; `CASHIER` and a `null` (no-session) role are
 * both blocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnknownBarcodeActionTest {

    private val barcode = "1234567890123"

    @Test
    fun admin_navigatesToCreate_withTheScannedBarcodePreFilled() {
        assertEquals(
            UnknownBarcodeAction.NavigateToCreate(productCreateRoute(barcode)),
            unknownBarcodeAction(UserRole.ADMIN, barcode),
        )
    }

    @Test
    fun cashier_isBlocked() {
        assertEquals(
            UnknownBarcodeAction.ShowCreationBlocked,
            unknownBarcodeAction(UserRole.CASHIER, barcode),
        )
    }

    @Test
    fun noSession_isBlocked() {
        assertEquals(
            UnknownBarcodeAction.ShowCreationBlocked,
            unknownBarcodeAction(null, barcode),
        )
    }
}
