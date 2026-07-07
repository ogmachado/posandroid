package com.idos.pos

import android.app.Application
import com.idos.pos.core.di.AppContainer

/**
 * Builds the single [AppContainer] instance for the app's lifetime (task 1.6;
 * design.md "Decision: Manual DI via AppContainer service-locator + Compose
 * bridge"). Registered as `android:name` in AndroidManifest.xml.
 */
class PosApplication : Application() {
    lateinit var appContainer: AppContainer

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer.create(this)
    }
}
