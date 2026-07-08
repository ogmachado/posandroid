package com.idos.pos.scan

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.core.di.AppContainer
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Test written alongside (task 6.3; `ViewModel.kt` files are excluded from
 * strict TDD per openspec/config.yaml `strict_tdd_scope`). Exercises
 * [ScanViewModel.onBarcodeDecoded]'s state transitions against a real
 * in-memory [AppContainer]/[com.idos.pos.catalog.CatalogRepository] — same
 * setup convention as [com.idos.pos.catalog.ProductViewModelTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanViewModelTest {

    private lateinit var container: AppContainer
    private lateinit var viewModel: ScanViewModel

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force onCreate/seed

        viewModel = ScanViewModel(container)
    }

    @After
    fun tearDown() {
        container.database.close()
    }

    private suspend fun waitUntil(timeoutMs: Long = 2_000, block: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!block()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    @Test
    fun initialState_isIdle() {
        assertEquals(ScanUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun knownBarcode_emitsFound_withTheMatchingProduct() = runBlocking {
        val unitMeasureId = container.unitMeasureDao.insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        val productId = container.catalogRepository.createProduct(
            name = "Widget",
            code = "SKU-1",
            barcode = "1234567890123",
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        ).getOrThrow()

        viewModel.onBarcodeDecoded("1234567890123")
        waitUntil { viewModel.uiState.value is ScanUiState.Found }

        val state = viewModel.uiState.value
        assertTrue(state is ScanUiState.Found)
        assertEquals(productId, (state as ScanUiState.Found).product.id)
    }

    @Test
    fun unknownBarcode_emitsNotFound_withTheScannedValue() = runBlocking {
        viewModel.onBarcodeDecoded("0000000000000")
        waitUntil { viewModel.uiState.value is ScanUiState.NotFound }

        assertEquals(ScanUiState.NotFound("0000000000000"), viewModel.uiState.value)
    }

    @Test
    fun resetToIdle_returnsToIdleState() = runBlocking {
        viewModel.onBarcodeDecoded("0000000000000")
        waitUntil { viewModel.uiState.value is ScanUiState.NotFound }

        viewModel.resetToIdle()

        assertEquals(ScanUiState.Idle, viewModel.uiState.value)
    }
}
