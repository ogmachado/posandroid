package com.idos.pos.licensing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RED (task 1.1): [LicenseStatus] must expose exactly the 9 variants defined
 * by design.md's "Interfaces" section — 5 desktop-parity statuses
 * (VALID/IN_GRACE_PERIOD/EXPIRED/COMPROMISED/NOT_CONFIGURED) plus 4
 * Android-specific verification-rejection reasons
 * (INVALID_SIGNATURE/WRONG_PRODUCT/MACHINE_MISMATCH/MALFORMED) that
 * [LicenseVerifier] (task 1.3) returns to distinguish *why* a `.lic` was
 * rejected, per specs/license-verification/spec.md ("a reason distinguishable
 * from a signature failure").
 */
class LicenseStatusTest {

    @Test
    fun licenseStatus_hasExactlyTheNineVariantsFromDesign() {
        val names = LicenseStatus.entries.map { it.name }.toSet()

        assertEquals(
            setOf(
                "VALID",
                "IN_GRACE_PERIOD",
                "EXPIRED",
                "COMPROMISED",
                "NOT_CONFIGURED",
                "INVALID_SIGNATURE",
                "WRONG_PRODUCT",
                "MACHINE_MISMATCH",
                "MALFORMED"
            ),
            names
        )
    }

    @Test
    fun licenseStatus_eachVariant_isIndividuallyResolvableByValueOf() {
        val resolved = listOf(
            "VALID", "IN_GRACE_PERIOD", "EXPIRED", "COMPROMISED", "NOT_CONFIGURED",
            "INVALID_SIGNATURE", "WRONG_PRODUCT", "MACHINE_MISMATCH", "MALFORMED"
        ).map { LicenseStatus.valueOf(it) }

        assertEquals(9, resolved.distinct().size)
    }
}
