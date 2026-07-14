package com.idos.pos

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.licensing.ActivationViewModel
import com.idos.pos.licensing.ClockRollbackDetector
import com.idos.pos.licensing.INSTALLATION_ID_TEST_TAG
import com.idos.pos.licensing.LicenseRepository
import com.idos.pos.licensing.LicenseStateDao
import com.idos.pos.licensing.LicenseStateEntity
import com.idos.pos.licensing.LicenseStatus
import com.idos.pos.licensing.LicenseVerifier
import com.idos.pos.permission.AuthRepository
import com.idos.pos.catalog.UnitMeasureEntity
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI test written alongside (task 5.4; `MainActivity`/`PosApplication`
 * are wiring/entry-point files, excluded from `strict_tdd_scope`, per
 * design.md Testing Strategy's "Alongside (Compose)" row — "gate swap").
 *
 * Exercises [EnforcementGate] directly with each [LicenseStatus] rather than
 * driving a real [MainActivity]/[PosApplication] under Robolectric — the same
 * testability boundary [com.idos.pos.scan.CameraPermissionGateTest] draws
 * around [com.idos.pos.scan.CameraPermissionGate] and
 * [com.idos.pos.licensing.ActivationScreenTest] draws around
 * [com.idos.pos.licensing.ActivationScreen]: a real [AppContainer.licenseRepository]
 * is wired to a Keystore-backed [com.idos.pos.licensing.InstallationIdStore],
 * unavailable under Robolectric in this environment, so the fake-collaborator
 * [ActivationViewModel] used here (built the same way
 * [com.idos.pos.licensing.LicenseRepositoryTest]/[com.idos.pos.licensing.ActivationScreenTest]
 * already do) is what makes the NOT_CONFIGURED/EXPIRED/COMPROMISED branches
 * testable at all on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class EnforcementGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun fakeActivationViewModel(): ActivationViewModel {
        val repository = LicenseRepository(
            dao = NoOpLicenseStateDao(),
            verify = { _, _, _, _ -> LicenseVerifier.Result(LicenseStatus.NOT_CONFIGURED) },
            installationIdProvider = { "test-installation-id" },
            evaluateHeartbeat = { now, _, _, _, _ -> ClockRollbackDetector.Result(compromised = false, heartbeatToPersist = now) },
        )
        return ActivationViewModel(repository)
    }

    // --- Requirement: Licensed status renders the app entry composable ---

    /**
     * [AppRoot]'s content is now [com.idos.pos.nav.PosNavHost] (the real
     * nav-shell wiring the POS screens — see that file's class doc), which
     * needs a real [AppContainer] via [LocalAppContainer] to construct its
     * screens' ViewModels. Previously `AppRoot()` rendered a static
     * placeholder with no DI needs at all, so this test (like the one below)
     * never had to provide one; wiring the real shell in makes that
     * necessary now. Mirrors
     * [gateFlippingBetweenLicensedAndUnlicensed_leavesSeededPosDataUntouched]'s
     * existing `AppContainer.createInMemory` + `CompositionLocalProvider` setup.
     */
    @Test
    fun validStatus_rendersAppRoot_notActivationScreen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force onCreate/seed
        seedBusinessProfileAndLogin(container)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                EnforcementGate(
                    licenseStatus = LicenseStatus.VALID,
                    onInstalled = {},
                    activationViewModel = fakeActivationViewModel(),
                )
            }
        }

        composeTestRule.onNodeWithTag(APP_ROOT_CONTENT_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(INSTALLATION_ID_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(GRACE_PERIOD_BANNER_TEST_TAG).assertDoesNotExist()

        container.database.close()
    }

    @Test
    fun inGracePeriodStatus_rendersAppRoot_withPersistentWarningBanner() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force onCreate/seed
        seedBusinessProfileAndLogin(container)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                EnforcementGate(
                    licenseStatus = LicenseStatus.IN_GRACE_PERIOD,
                    onInstalled = {},
                    activationViewModel = fakeActivationViewModel(),
                )
            }
        }

        composeTestRule.onNodeWithTag(APP_ROOT_CONTENT_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(GRACE_PERIOD_BANNER_TEST_TAG).assertExists()

        container.database.close()
    }

    // --- Requirement: Unlicensed status renders the activation composable, no escape path ---

    @Test
    fun notConfiguredStatus_rendersActivationScreen_notAppRoot() {
        composeTestRule.setContent {
            EnforcementGate(
                licenseStatus = LicenseStatus.NOT_CONFIGURED,
                onInstalled = {},
                activationViewModel = fakeActivationViewModel(),
            )
        }

        composeTestRule.onNodeWithTag(INSTALLATION_ID_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(APP_ROOT_CONTENT_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun expiredStatus_rendersActivationScreen_withNoEscapePathToAppRoot() {
        composeTestRule.setContent {
            EnforcementGate(
                licenseStatus = LicenseStatus.EXPIRED,
                onInstalled = {},
                activationViewModel = fakeActivationViewModel(),
            )
        }

        composeTestRule.onNodeWithTag(INSTALLATION_ID_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(APP_ROOT_CONTENT_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun compromisedStatus_rendersActivationScreen_withNoEscapePathToAppRoot() {
        composeTestRule.setContent {
            EnforcementGate(
                licenseStatus = LicenseStatus.COMPROMISED,
                onInstalled = {},
                activationViewModel = fakeActivationViewModel(),
            )
        }

        composeTestRule.onNodeWithTag(INSTALLATION_ID_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(APP_ROOT_CONTENT_TEST_TAG).assertDoesNotExist()
    }

    // --- Requirement: On-Device Data Survives Re-Blocking and Re-Activation ---

    /**
     * Interprets "existing POS data intact across a gate flip" pragmatically
     * (there is no real POS screen wired into [AppRoot] yet — see
     * [MainActivity]'s class doc): the [AppContainer] instance provided via
     * [LocalAppContainer] — and the seeded product/inventory data it reads —
     * is the SAME instance across every gate position, because it is provided
     * once, ABOVE the conditional branch, exactly like [MainActivity] provides
     * it once in [MainActivity.onCreate] before the status-driven
     * recomposition ever runs. Flipping the gate only swaps which composable
     * is composed under that provider; it never recreates the container or
     * touches the database.
     */
    @Test
    fun gateFlippingBetweenLicensedAndUnlicensed_leavesSeededPosDataUntouched() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force onCreate/seed

        val unitMeasureId = runBlocking {
            container.unitMeasureDao.insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        }
        val productId = runBlocking {
            container.catalogRepository.createProduct(
                name = "Widget",
                code = "SKU-GATE-1",
                barcode = "1112223334445",
                price = BigDecimal("10.00"),
                costPrice = BigDecimal("4.00"),
                unitMeasureId = unitMeasureId,
                categoryId = null,
            ).getOrThrow()
        }

        seedBusinessProfileAndLogin(container)

        var licenseStatus by mutableStateOf(LicenseStatus.NOT_CONFIGURED)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                EnforcementGate(
                    licenseStatus = licenseStatus,
                    onInstalled = {},
                    activationViewModel = fakeActivationViewModel(),
                )
            }
        }
        composeTestRule.onNodeWithTag(INSTALLATION_ID_TEST_TAG).assertExists()

        // Flip: unlicensed -> licensed -> grace -> unlicensed again.
        licenseStatus = LicenseStatus.VALID
        composeTestRule.onNodeWithTag(APP_ROOT_CONTENT_TEST_TAG).assertExists()

        licenseStatus = LicenseStatus.IN_GRACE_PERIOD
        composeTestRule.onNodeWithTag(GRACE_PERIOD_BANNER_TEST_TAG).assertExists()

        licenseStatus = LicenseStatus.EXPIRED
        composeTestRule.onNodeWithTag(INSTALLATION_ID_TEST_TAG).assertExists()

        // The seeded product/inventory data was never touched by any of the
        // gate flips above — same AppContainer/database throughout.
        val product = runBlocking { container.catalogRepository.findByBarcode("1112223334445") }
        assertNotNull(product)
        assertEquals(productId, product?.id)

        container.database.close()
    }
}

/**
 * `android-pos-auth` Phase 2 (task 2.5) inserted [com.idos.pos.permission.AuthGate]
 * in front of [com.idos.pos.nav.PosNavHost] inside `AppRoot` — composing it
 * with no business profile/session triggers `OnboardingScreen`'s
 * `LaunchedEffect(Unit)` (`ensureDefaultAdminSeeded()`), an async coroutine
 * this class's tests don't otherwise need. Pre-seeding a business profile +
 * authenticated ADMIN session here makes `AuthGate` resolve straight to
 * `Authenticated`, so no such background coroutine is left racing
 * `container.database.close()` at the end of each test.
 */
private fun seedBusinessProfileAndLogin(container: AppContainer) = runBlocking {
    container.businessProfileRepository.save("Acme", "123 Main St", "555-0100")
    container.authRepository.ensureDefaultAdminSeeded()
    container.authRepository.login(AuthRepository.DEFAULT_ADMIN_USERNAME, AuthRepository.DEFAULT_ADMIN_PIN)
}

/** Minimal no-op fake — only [ActivationViewModel.installationId] is read by these tests, never persisted state. */
private class NoOpLicenseStateDao : LicenseStateDao {
    override suspend fun find(id: Int): LicenseStateEntity? = null
    override suspend fun upsert(entity: LicenseStateEntity) = Unit
}
