package com.idos.pos.permission

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI test written alongside (task 3.4 originally; retargeted by
 * `android-pos-auth` Phase 3 task 3.2 for [PinGate]'s suspend verifier
 * constructor — design.md Decision G; design.md Testing Strategy — Compose UI
 * "createComposeRule UI tests (JVM where possible)"). Uses a fake `verifyPin`
 * function (not a real [AuthRepository]) — the fake exercises exactly the
 * same [PinGate] contract a real [AuthRepository.verifyAdminPin] would.
 *
 * **Suspend seam**: [PinGate]'s constructor now takes a `suspend (String) ->
 * Boolean` verifier plus a `CoroutineScope`. Each test below supplies a
 * [TestScope] as that scope and explicitly calls `testScheduler.advanceUntilIdle()`
 * after driving the confirm click, to deterministically await the async check
 * ([PinGate.submit] launches it, never blocking) before asserting on
 * [PinGate.isVisible]/`actionRan` — matching the exact instruction to
 * "await/advance the async check before each assertion".
 *
 * Covers specs/permission-gate/spec.md scenarios: "Correct PIN allows a price
 * edit", "Incorrect PIN blocks a price edit", and "Two gated actions in a row
 * each require the PIN" (no session carry-over).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinGateDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val correctPin = "1234"

    @Test
    fun correctPin_admitsTheGatedAction() {
        var actionRan = false
        val testScope = TestScope()
        val gate = PinGate(verifyPin = { it == correctPin }, scope = testScope)
        gate.require { actionRan = true }

        composeTestRule.setContent { PinGateDialog(gate) }

        composeTestRule.onNodeWithTag(PIN_INPUT_TEST_TAG).performTextInput(correctPin)
        composeTestRule.onNodeWithTag(PIN_CONFIRM_TEST_TAG).performClick()
        testScope.testScheduler.advanceUntilIdle()

        assertTrue(actionRan)
        assertEquals(false, gate.isVisible)
    }

    @Test
    fun incorrectPin_blocksTheGatedAction_andSurfacesPinIncorrect() {
        var actionRan = false
        val testScope = TestScope()
        val gate = PinGate(verifyPin = { it == correctPin }, scope = testScope)
        gate.require { actionRan = true }

        composeTestRule.setContent { PinGateDialog(gate) }

        composeTestRule.onNodeWithTag(PIN_INPUT_TEST_TAG).performTextInput("9999")
        composeTestRule.onNodeWithTag(PIN_CONFIRM_TEST_TAG).performClick()
        testScope.testScheduler.advanceUntilIdle()

        assertEquals(false, actionRan)
        assertTrue(gate.isVisible)
        composeTestRule.onNodeWithTag(PIN_ERROR_TEST_TAG).assertExists()
    }

    @Test
    fun twoGatedActionsInARow_eachRequirePinAgain_noCarryOver() {
        var firstActionRan = false
        var secondActionRan = false
        val testScope = TestScope()
        val gate = PinGate(verifyPin = { it == correctPin }, scope = testScope)

        // First gated action: correct PIN, admitted.
        gate.require { firstActionRan = true }
        composeTestRule.setContent { PinGateDialog(gate) }
        composeTestRule.onNodeWithTag(PIN_INPUT_TEST_TAG).performTextInput(correctPin)
        composeTestRule.onNodeWithTag(PIN_CONFIRM_TEST_TAG).performClick()
        testScope.testScheduler.advanceUntilIdle()
        assertTrue(firstActionRan)
        assertEquals(false, gate.isVisible)

        // Second gated action immediately after: must re-prompt, no carry-over.
        gate.require { secondActionRan = true }
        assertTrue("gate must re-open for the second action", gate.isVisible)

        composeTestRule.onNodeWithTag(PIN_INPUT_TEST_TAG).performTextInput(correctPin)
        composeTestRule.onNodeWithTag(PIN_CONFIRM_TEST_TAG).performClick()
        testScope.testScheduler.advanceUntilIdle()

        assertTrue(secondActionRan)
    }
}
