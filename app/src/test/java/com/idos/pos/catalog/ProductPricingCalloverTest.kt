package com.idos.pos.catalog

import java.math.BigDecimal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test written alongside (`ProductViewModel.kt` is outside
 * `openspec/config.yaml`'s `strict_tdd_scope` include patterns). Plain
 * JUnit — [pricingRequiresCallover] is a pure function with no Android/Compose
 * dependency (`VisibleTabsForTest`/`RolePermissionsTest` precedent).
 *
 * Covers `android-pos-role-permissions` design.md Decision G: `price`-only,
 * `costPrice`-only, and both-changed together (a single `true`, not two
 * separate signals) all trigger a callover; neither changing does not;
 * `null` originals (creation) use a `ZERO` baseline; scale-differing but
 * numerically-equal `BigDecimal`s (`compareTo`, not `equals`) are treated as
 * unchanged.
 */
class ProductPricingCalloverTest {

    private val original = BigDecimal("100.00")
    private val originalCost = BigDecimal("50.00")

    @Test
    fun priceOnlyChanged_requiresCallover() {
        assertTrue(
            pricingRequiresCallover(
                originalPrice = original,
                originalCostPrice = originalCost,
                price = BigDecimal("120.00"),
                costPrice = originalCost,
            ),
        )
    }

    @Test
    fun costPriceOnlyChanged_requiresCallover() {
        assertTrue(
            pricingRequiresCallover(
                originalPrice = original,
                originalCostPrice = originalCost,
                price = original,
                costPrice = BigDecimal("60.00"),
            ),
        )
    }

    @Test
    fun bothPriceAndCostPriceChanged_requiresCallover_asASingleSignal() {
        assertTrue(
            pricingRequiresCallover(
                originalPrice = original,
                originalCostPrice = originalCost,
                price = BigDecimal("120.00"),
                costPrice = BigDecimal("60.00"),
            ),
        )
    }

    @Test
    fun neitherChanged_doesNotRequireCallover() {
        assertFalse(
            pricingRequiresCallover(
                originalPrice = original,
                originalCostPrice = originalCost,
                price = original,
                costPrice = originalCost,
            ),
        )
    }

    @Test
    fun scaleDifferingButNumericallyEqualValues_areTreatedAsUnchanged() {
        assertFalse(
            pricingRequiresCallover(
                originalPrice = BigDecimal("100"),
                originalCostPrice = BigDecimal("50"),
                price = BigDecimal("100.00"),
                costPrice = BigDecimal("50.00"),
            ),
        )
    }

    @Test
    fun nullOriginals_useZeroBaseline_soANonZeroCreationRequiresCallover() {
        assertTrue(
            pricingRequiresCallover(
                originalPrice = null,
                originalCostPrice = null,
                price = BigDecimal("30.00"),
                costPrice = BigDecimal("10.00"),
            ),
        )
    }

    @Test
    fun nullOriginals_withZeroPricing_doesNotRequireCallover() {
        assertFalse(
            pricingRequiresCallover(
                originalPrice = null,
                originalCostPrice = null,
                price = BigDecimal.ZERO,
                costPrice = BigDecimal.ZERO,
            ),
        )
    }
}
