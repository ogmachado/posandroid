package com.idos.pos.scan

/**
 * Camera-permission lifecycle state for [BarcodeScanScreen] (task 6.2;
 * design.md "Permission: ... Screen shows a rationale + 'grant' state until
 * permission is held; denied-permanently → settings deep-link hint.").
 *
 * Kept as a plain sealed type — computed from Android's permission APIs at
 * the call site via [resolveCameraPermissionState] — so the rationale/denied
 * UI ([CameraPermissionGate]) is testable under Robolectric without driving
 * a real system permission dialog. Same testability boundary
 * [com.idos.pos.permission.PinGate] draws around
 * [com.idos.pos.permission.PinRepository] (a fake `verifyPin` function
 * instead of the real EncryptedSharedPreferences-backed one).
 */
sealed interface CameraPermissionState {
    /** Permission held — render the actual camera preview/analysis content. */
    data object Granted : CameraPermissionState

    /** Not held yet; either never asked, or asked and Android still allows a rationale re-prompt. */
    data object NotGranted : CameraPermissionState

    /** Denied with no rationale available — Android's signal that the user chose "don't ask again". */
    data object PermanentlyDenied : CameraPermissionState
}

/**
 * Pure resolution of [CameraPermissionState] from the three signals Android
 * exposes around runtime permissions. `shouldShowRationale` (from
 * `ActivityCompat.shouldShowRequestPermissionRationale`) is `false` in two
 * distinct situations — never asked yet, and asked-then-permanently-denied —
 * which is why [hasRequestedBefore] disambiguates them here (call-site state,
 * not something Android exposes directly).
 *
 * No Android dependency at all — fully unit-testable in plain JUnit
 * ([CameraPermissionStateTest]).
 */
fun resolveCameraPermissionState(
    hasPermission: Boolean,
    hasRequestedBefore: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionState = when {
    hasPermission -> CameraPermissionState.Granted
    !hasRequestedBefore -> CameraPermissionState.NotGranted
    shouldShowRationale -> CameraPermissionState.NotGranted
    else -> CameraPermissionState.PermanentlyDenied
}
