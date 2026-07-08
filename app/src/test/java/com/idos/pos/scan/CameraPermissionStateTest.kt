package com.idos.pos.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JUnit test (task 6.2) — [resolveCameraPermissionState] has no
 * Android dependency, so every combination of the three signals Android
 * exposes around runtime permissions is covered here without Robolectric.
 */
class CameraPermissionStateTest {

    @Test
    fun granted_returnsGranted_regardlessOfOtherFlags() {
        assertEquals(
            CameraPermissionState.Granted,
            resolveCameraPermissionState(hasPermission = true, hasRequestedBefore = true, shouldShowRationale = true),
        )
        assertEquals(
            CameraPermissionState.Granted,
            resolveCameraPermissionState(hasPermission = true, hasRequestedBefore = false, shouldShowRationale = false),
        )
    }

    @Test
    fun neverRequestedYet_andNotGranted_returnsNotGranted() {
        assertEquals(
            CameraPermissionState.NotGranted,
            resolveCameraPermissionState(hasPermission = false, hasRequestedBefore = false, shouldShowRationale = false),
        )
    }

    @Test
    fun requestedOnce_deniedButRationaleStillAllowed_returnsNotGranted() {
        assertEquals(
            CameraPermissionState.NotGranted,
            resolveCameraPermissionState(hasPermission = false, hasRequestedBefore = true, shouldShowRationale = true),
        )
    }

    @Test
    fun requestedOnce_deniedWithoutRationale_returnsPermanentlyDenied() {
        assertEquals(
            CameraPermissionState.PermanentlyDenied,
            resolveCameraPermissionState(hasPermission = false, hasRequestedBefore = true, shouldShowRationale = false),
        )
    }
}
