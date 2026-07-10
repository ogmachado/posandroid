package com.idos.pos

import android.app.Application
import com.idos.pos.core.di.AppContainer
import com.idos.pos.licensing.LicenseStatus
import kotlinx.coroutines.runBlocking

/**
 * Builds the single [AppContainer] instance for the app's lifetime (task 1.6;
 * design.md "Decision: Manual DI via AppContainer service-locator + Compose
 * bridge"). Registered as `android:name` in AndroidManifest.xml.
 *
 * `android-pos-licensing` Phase 5 (task 5.2) adds [initialLicenseStatus]: the
 * license status evaluated once, synchronously, here in [onCreate] — before
 * [MainActivity] ever composes — so the very first frame already renders the
 * correct side of the enforcement gate (specs/license-enforcement-gate/spec.md
 * "Gate Is a Top-Level Composable Swap") instead of a placeholder/loading
 * state that would then flicker to the real one.
 *
 * **Why `runBlocking` here and not a Compose-side `LaunchedEffect` +
 * loading state**: [AppContainer.licenseRepository]'s `currentStatus()` is a
 * cheap, fully local read (one Room row + one RSA signature verify already
 * held in memory — no network, no disk scan) and this is a single-activity,
 * solo-operator retail POS with no Hilt/WorkManager and no other startup
 * work competing for the main thread. Blocking `onCreate` for a
 * microseconds-scale local check is simpler and avoids a gate that itself
 * needs a loading/blank state — that tradeoff would need to be re-examined if
 * `currentStatus()` ever grows a network call. Room's suspend DAO functions
 * dispatch off the calling thread internally, so this does not violate
 * `Cannot access database on the main thread` even though the production
 * database is not built with `allowMainThreadQueries()`.
 */
class PosApplication : Application() {
    lateinit var appContainer: AppContainer
    lateinit var initialLicenseStatus: LicenseStatus
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer.create(this)
        initialLicenseStatus = runBlocking { appContainer.licenseRepository.currentStatus() }
    }
}
