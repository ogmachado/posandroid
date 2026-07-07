package com.idos.pos.core.di

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED/GREEN (task 1.7, Robolectric): [AppContainer.createInMemory] builds a
 * working in-memory Room database — the test double every later repository test
 * in this codebase reuses instead of touching a real on-disk database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerTest {

    @Test
    fun createInMemory_buildsAnOpenDatabase() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force the connection open

        assertNotNull(container.database)
        assertTrue(container.database.isOpen)

        container.database.close()
    }

    @Test
    fun createInMemory_seedsThroughTheSameCallbackAsProduction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force onCreate

        val methods = container.paymentMethodDao.findAll()

        assertTrue(methods.any { it.code == "CASH" })

        container.database.close()
    }
}
