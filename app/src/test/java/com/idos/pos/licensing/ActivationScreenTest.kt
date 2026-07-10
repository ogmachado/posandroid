package com.idos.pos.licensing

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val INSTALLATION_ID = "test-installation-id"
private const val VALID_JWS = "valid-candidate-jws"

/**
 * Compose UI test written alongside the implementation (task 4.3;
 * `ActivationViewModel`/`ActivationScreen` are excluded from strict TDD per
 * openspec/config.yaml `strict_tdd_scope` — design.md Testing Strategy
 * "Alongside (Compose)"). Exercises the real [ActivationScreen] wired to a
 * real [ActivationViewModel], but built via the [LicenseRepository]-direct
 * secondary constructor with fake collaborators — see
 * [ActivationViewModel]'s KDoc for why: a real [com.idos.pos.core.di.AppContainer]'s
 * [com.idos.pos.core.di.AppContainer.licenseRepository] needs Android Keystore
 * (via [InstallationIdStore]), unavailable under Robolectric in this
 * environment — the same limitation [LicenseRepositoryTest] and
 * [LicenseVerifierTest] already work around by faking `verify`/
 * `installationIdProvider` instead of constructing the real Keystore-bound
 * collaborators.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class ActivationScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var dao: FakeActivationLicenseStateDao

    private fun claims(
        issuedAt: Instant = Instant.parse("2026-06-01T00:00:00Z"),
        expirationDate: LocalDate = LocalDate.now(ZoneOffset.UTC).plusDays(30),
    ) = LicenseVerifier.Claims(
        licenseId = "license-001",
        issuedAt = issuedAt,
        expirationDate = expirationDate,
        businessId = "biz-1",
        businessName = "Acme Bar",
        businessTaxId = "TAX-1",
        product = LicenseVerifier.PRODUCT_ANDROID_POS,
        machineId = INSTALLATION_ID,
    )

    private fun viewModel(
        verify: (String, String, LocalDate, Boolean) -> LicenseVerifier.Result = { _, _, _, _ ->
            LicenseVerifier.Result(LicenseStatus.VALID, claims())
        },
    ): ActivationViewModel {
        dao = FakeActivationLicenseStateDao()
        val repository = LicenseRepository(
            dao = dao,
            verify = verify,
            installationIdProvider = { INSTALLATION_ID },
            evaluateHeartbeat = { now, _, _, _, _ ->
                ClockRollbackDetector.Result(compromised = false, heartbeatToPersist = now)
            },
        )
        return ActivationViewModel(repository)
    }

    private suspend fun waitUntil(timeoutMs: Long = 2_000, block: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!block()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    // --- Requirement: Preview Before Install ---

    @Test
    fun pastingAValidCandidate_previewShowsClaims_withoutInstalling() {
        val viewModel = viewModel()

        composeTestRule.setContent { ActivationScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag(PASTE_TEXT_FIELD_TEST_TAG).performTextInput(VALID_JWS)
        composeTestRule.onNodeWithTag(PASTE_PREVIEW_BUTTON_TEST_TAG).performClick()

        composeTestRule.onNodeWithTag(PREVIEW_STATUS_TEST_TAG).assertTextContains("VALID", substring = true)
        composeTestRule.onNodeWithTag(PREVIEW_CLAIMS_TEST_TAG).assertTextContains("Acme Bar", substring = true)
        assertEquals(0, dao.upsertCallCount)
    }

    // --- Requirement: Install Persists a Verified License ---

    @Test
    fun confirmingInstall_onAValidPreview_succeeds_andNotifiesCaller() = runBlocking {
        val viewModel = viewModel()
        var installedCalled = false

        composeTestRule.setContent {
            ActivationScreen(onInstalled = { installedCalled = true }, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag(PASTE_TEXT_FIELD_TEST_TAG).performTextInput(VALID_JWS)
        composeTestRule.onNodeWithTag(PASTE_PREVIEW_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(INSTALL_BUTTON_TEST_TAG).performClick()

        waitUntil { dao.upsertCallCount == 1 }
        shadowOf(Looper.getMainLooper()).idle()

        composeTestRule.onNodeWithTag(INSTALL_SUCCESS_TEST_TAG).assertExists()
        assertTrue(installedCalled)
    }

    @Test
    fun confirmingInstall_whenRepositoryRejectsAsRollback_showsDistinguishableFailureReason() = runBlocking {
        // Given a stored license newer than the candidate's issuedAt — install()
        // rejects this as a rollback/replay even though the candidate previews
        // as VALID (preview() does not check the rollback guard — only install() does).
        val candidateIssuedAt = Instant.parse("2020-01-01T00:00:00Z")
        val viewModel = viewModel(
            verify = { _, _, _, _ -> LicenseVerifier.Result(LicenseStatus.VALID, claims(issuedAt = candidateIssuedAt)) },
        )
        dao.stored = LicenseStateEntity(
            jws = "already-installed-jws",
            installedIssuedAt = Instant.parse("2026-06-01T00:00:00Z").epochSecond,
            compromised = false,
        )

        composeTestRule.setContent { ActivationScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag(PASTE_TEXT_FIELD_TEST_TAG).performTextInput(VALID_JWS)
        composeTestRule.onNodeWithTag(PASTE_PREVIEW_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(INSTALL_BUTTON_TEST_TAG).performClick()

        waitUntil { viewModel.uiState.value is ActivationUiState.InstallFailure }
        shadowOf(Looper.getMainLooper()).idle()

        composeTestRule.onNodeWithTag(INSTALL_FAILURE_TEST_TAG)
            .assertTextContains("older than the one already installed", substring = true)
        assertEquals(0, dao.upsertCallCount)
    }

    // --- Race guard: a new preview must not be clobbered by a stale in-flight install() ---

    @Test
    fun whileInstallIsInFlight_previewAndImportControlsAreDisabled() = runBlocking {
        val viewModel = viewModel()
        val gate = CompletableDeferred<Unit>()
        dao.upsertGate = gate

        composeTestRule.setContent { ActivationScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag(PASTE_TEXT_FIELD_TEST_TAG).performTextInput(VALID_JWS)
        composeTestRule.onNodeWithTag(PASTE_PREVIEW_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(INSTALL_BUTTON_TEST_TAG).performClick()

        // install() is parked on `gate` off the main thread (see
        // FakeActivationLicenseStateDao.upsertGate's doc) — the ViewModel/UI
        // state is reliably frozen at Installing until we complete it below.
        assertEquals(ActivationUiState.Installing, viewModel.uiState.value)
        composeTestRule.onNodeWithTag(PASTE_TEXT_FIELD_TEST_TAG).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(PASTE_PREVIEW_BUTTON_TEST_TAG).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(IMPORT_FILE_BUTTON_TEST_TAG).assertIsNotEnabled()

        // Let the in-flight install resolve so no coroutine is left pending
        // past the end of the test.
        gate.complete(Unit)
        waitUntil { dao.upsertCallCount == 1 }
    }

    @Test
    fun staleInFlightInstallResult_doesNotOverrideANewerPreview() = runBlocking {
        // Defense-in-depth at the ViewModel level (ActivationViewModel.confirmInstall's
        // own stale-write guard): even if something bypassed the UI-level disabled
        // controls and drove a NEW preview while the original install() was still
        // in flight, the stale result must not stomp the newer state when it resolves.
        val viewModel = viewModel()
        val gate = CompletableDeferred<Unit>()
        dao.upsertGate = gate

        composeTestRule.setContent { ActivationScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag(PASTE_TEXT_FIELD_TEST_TAG).performTextInput(VALID_JWS)
        composeTestRule.onNodeWithTag(PASTE_PREVIEW_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(INSTALL_BUTTON_TEST_TAG).performClick()
        assertEquals(ActivationUiState.Installing, viewModel.uiState.value)

        // Simulate a bypass of the (now-disabled) UI controls by calling the
        // ViewModel directly — onCandidateProvided is synchronous, so this
        // immediately replaces Installing with a fresh Preview while the
        // original install() is still parked on `gate`.
        viewModel.onCandidateProvided("a-different-candidate-jws")
        val newerPreview = viewModel.uiState.value
        assertTrue(newerPreview is ActivationUiState.Preview)

        // Now let the ORIGINAL install() coroutine resume and resolve.
        gate.complete(Unit)
        waitUntil { dao.upsertCallCount == 1 }
        shadowOf(Looper.getMainLooper()).idle()

        // The stale success result must have been dropped, not applied — the
        // newer preview is still showing.
        assertEquals(newerPreview, viewModel.uiState.value)
    }

    @Test
    fun previewingARejectedCandidate_showsADistinguishableReason_perStatus() {
        val viewModel = viewModel(
            verify = { _, _, _, _ -> LicenseVerifier.Result(LicenseStatus.MACHINE_MISMATCH, claims = null) },
        )

        composeTestRule.setContent { ActivationScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag(PASTE_TEXT_FIELD_TEST_TAG).performTextInput(VALID_JWS)
        composeTestRule.onNodeWithTag(PASTE_PREVIEW_BUTTON_TEST_TAG).performClick()

        composeTestRule.onNodeWithTag(REJECTION_MESSAGE_TEST_TAG)
            .assertTextContains("bound to a different device", substring = true)
        composeTestRule.onNodeWithTag(INSTALL_BUTTON_TEST_TAG).assertDoesNotExist()
    }

    // --- Requirement: Both File Import and Paste Are Supported ---

    @Test
    fun fileImportPath_and_pasteTextPath_produceEquivalentPreviewOutcomes() {
        // The real SAF system file picker cannot be driven under Robolectric
        // (no instrumented picker activity exists to satisfy the request —
        // the same testability boundary this codebase already draws around
        // Keystore/camera, e.g. InstallationIdStore/CameraPreviewWithAnalysis).
        // This test instead drives the SAME production function
        // (readJwsFromUri, backed by a real file:// Uri) that
        // ActivationScreen's launcher callback calls, and confirms it
        // produces an outcome identical to the paste-text path for the same
        // license content — proving both paths converge on the same
        // ActivationViewModel.onCandidateProvided call (spec.md "Both File
        // Import and Paste Are Supported").
        val tempFile = File.createTempFile("license", ".lic").apply { writeText(VALID_JWS) }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        try {
            val fileJws = readJwsFromUri(context, Uri.fromFile(tempFile))
            assertEquals(VALID_JWS, fileJws)

            val fileViewModel = viewModel()
            fileViewModel.onCandidateProvided(fileJws!!)
            val fileOutcome = fileViewModel.uiState.value as ActivationUiState.Preview

            val pasteViewModel = viewModel()
            pasteViewModel.onCandidateProvided(VALID_JWS)
            val pasteOutcome = pasteViewModel.uiState.value as ActivationUiState.Preview

            assertEquals(pasteOutcome.status, fileOutcome.status)
            assertEquals(pasteOutcome.claims, fileOutcome.claims)
        } finally {
            tempFile.delete()
        }
    }
}

private class FakeActivationLicenseStateDao : LicenseStateDao {
    var stored: LicenseStateEntity? = null
    var upsertCallCount = 0
        private set

    /**
     * When set, [upsert] suspends on this (off the main thread, via
     * [Dispatchers.Default]) until the test completes it — lets a test freeze
     * [ActivationViewModel.confirmInstall]'s in-flight coroutine at exactly
     * the `Installing` state for as long as needed. A plain [performClick] is
     * NOT enough to observe that transient state on its own: Compose's
     * test-rule idle-wait pumps the shared Robolectric main looper until all
     * pending work (including this ViewModel's `Dispatchers.Main`-scheduled
     * coroutine) drains, so with no real suspension point the install()
     * coroutine completes before `performClick()` even returns. Moving the
     * wait to a different dispatcher sidesteps that idle-wait entirely.
     */
    var upsertGate: CompletableDeferred<Unit>? = null

    override suspend fun find(id: Int): LicenseStateEntity? = stored

    override suspend fun upsert(entity: LicenseStateEntity) {
        upsertGate?.let { gate -> withContext(Dispatchers.Default) { gate.await() } }
        upsertCallCount++
        stored = entity
    }
}
