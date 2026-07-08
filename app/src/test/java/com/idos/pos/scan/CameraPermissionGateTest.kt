package com.idos.pos.scan

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI test written alongside (task 6.2/6.4; design.md Testing
 * Strategy — "Compose UI ... createComposeRule UI tests (JVM where
 * possible)"; the `scan` package is excluded from strict TDD per openspec/config.yaml
 * `strict_tdd_scope` since CameraX/MLKit itself is exploratory/device-bound).
 *
 * Exercises [CameraPermissionGate] directly with each [CameraPermissionState]
 * rather than driving a real system permission dialog through
 * [BarcodeScanScreen] — the same testability boundary
 * [com.idos.pos.permission.PinGateDialogTest] draws around
 * [com.idos.pos.permission.PinGate] (a fake `verifyPin` instead of the real
 * OS/Keystore-backed primitive Robolectric can't fully emulate). Covers
 * design.md's "Screen shows a rationale + 'grant' state until permission is
 * held; denied-permanently → settings deep-link hint."
 *
 * **`qualifiers = "w360dp-h640dp"`**: `@Config(sdk = [34])` alone resolves to
 * a zero-size Robolectric test window, which made `setContent` itself hang
 * (`AppNotIdleException`, "measure/layout lambdas... infinite composition
 * loop") for every state here — including the trivial `Granted` case with no
 * `fillMaxSize` content at all. Same root cause and same fix
 * [ProductFormScreenTest]'s doc comment documents (task 4.6).
 *
 * **Cross-class test-JVM isolation (`forkEvery = 1`, see `app/build.gradle.kts`)**:
 * this class, run as part of the FULL suite, was found to reliably hit
 * `AppNotIdleException` at `setContent` — even for the trivial `Granted` case
 * with no dialog involved — but only once enough OTHER Compose+AlertDialog
 * tests had run beforehand in the same forked test JVM (in particular,
 * [ProductFormScreenTest] and [com.idos.pos.inventory.InventoryMovementFormScreenTest]
 * intentionally render an `AlertDialog` and, per their own class docs,
 * already accept that Robolectric's Compose idle-detection never converges
 * for that composition — see their docs for the "never converges" finding).
 * Raising Espresso's idling timeout did NOT fix it (attempts scaled linearly
 * with the raised timeout — 120s still failed, proving it's a genuine
 * leaked/never-settling Composition from an earlier test class polluting
 * shared JVM-static Compose runtime state, not merely a slow-but-convergent
 * one). Since this class cannot fix those other tests' pre-existing
 * `AlertDialog` behavior (out of this PR's scope), `app/build.gradle.kts`
 * forces one JVM per test class (`forkEvery = 1`) instead, which fully
 * isolates each Robolectric/Compose static runtime and reproducibly fixes
 * this without touching any Phase 3/4/5 test file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class CameraPermissionGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val contentTag = "camera-content"

    @Test
    fun grantedState_rendersContent_notTheRationaleOrDeniedUi() {
        composeTestRule.setContent {
            CameraPermissionGate(
                state = CameraPermissionState.Granted,
                onRequestPermission = {},
                onOpenAppSettings = {},
            ) {
                Box { Text("camera content", modifier = Modifier.testTag(contentTag)) }
            }
        }

        composeTestRule.onNodeWithTag(contentTag).assertExists()
        composeTestRule.onNodeWithTag(CAMERA_RATIONALE_TEXT_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CAMERA_DENIED_TEXT_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun notGrantedState_showsRationale_andRequestsPermissionOnClick() {
        var requested = false

        composeTestRule.setContent {
            CameraPermissionGate(
                state = CameraPermissionState.NotGranted,
                onRequestPermission = { requested = true },
                onOpenAppSettings = {},
            ) {
                Box { Text("camera content", modifier = Modifier.testTag(contentTag)) }
            }
        }

        composeTestRule.onNodeWithTag(contentTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CAMERA_RATIONALE_TEXT_TEST_TAG).assertExists()

        composeTestRule.onNodeWithTag(CAMERA_GRANT_BUTTON_TEST_TAG).performClick()

        assertTrue(requested)
    }

    @Test
    fun permanentlyDeniedState_showsSettingsHint_andOpensSettingsOnClick() {
        var openedSettings = false

        composeTestRule.setContent {
            CameraPermissionGate(
                state = CameraPermissionState.PermanentlyDenied,
                onRequestPermission = {},
                onOpenAppSettings = { openedSettings = true },
            ) {
                Box { Text("camera content", modifier = Modifier.testTag(contentTag)) }
            }
        }

        composeTestRule.onNodeWithTag(contentTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CAMERA_DENIED_TEXT_TEST_TAG).assertExists()

        composeTestRule.onNodeWithTag(CAMERA_OPEN_SETTINGS_BUTTON_TEST_TAG).performClick()

        assertTrue(openedSettings)
    }
}
