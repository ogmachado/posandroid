package com.idos.pos.scan

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/**
 * Instrumented test (task 6.4; design.md Testing Strategy — "Instrumented
 * (device/emulator) ... CameraX/MLKit scan pipeline ... AndroidX Test +
 * Espresso; MLKit needs a real device/emulator").
 *
 * **NOT run/verified in the apply environment for this PR** — there is no
 * Android SDK/emulator configured there, only `./gradlew testDebugUnitTest`
 * (JVM/Robolectric) runs. This file requires `./gradlew connectedAndroidTest`
 * against a real device/emulator, which that environment cannot execute.
 * Whoever has a device/emulator available should run it and confirm before
 * relying on it — see tasks.md 6.4's inline note.
 *
 * Rather than shipping a static barcode image asset, this generates a real
 * EAN-13 bitmap at runtime with ZXing's encoder (`androidTestImplementation`
 * test-only dependency — see `app/build.gradle.kts`), then feeds it through
 * the SAME MLKit `BarcodeScanning` pipeline [BarcodeAnalyzer] uses
 * ([BarcodeAnalyzer.processInputImage], the seam extracted in task 6.1 for
 * exactly this purpose) to confirm the real on-device scanner resolves the
 * encoded value end-to-end.
 */
@RunWith(AndroidJUnit4::class)
class BarcodeScanPipelineTest {

    @Test
    fun analyzer_resolvesAKnownEan13Barcode() {
        // Given — a real EAN-13 barcode ("4006381333931" is a well-known
        // valid EAN-13 test value with a correct check digit) rendered to a bitmap.
        val expectedValue = "4006381333931"
        val bitmap = renderEan13Bitmap(expectedValue)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        var decoded: String? = null
        val analyzer = BarcodeAnalyzer(onBarcodeScanned = { decoded = it })

        // When — the analyzer's decode seam processes the generated image.
        runBlocking { awaitProcessInputImage(analyzer, inputImage) }

        // Then — the real MLKit pipeline resolves the exact encoded value.
        assertEquals(expectedValue, decoded)
    }

    private fun renderEan13Bitmap(value: String, widthPx: Int = 600, heightPx: Int = 300): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.EAN_13, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        for (x in 0 until widthPx) {
            for (y in 0 until heightPx) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private suspend fun awaitProcessInputImage(analyzer: BarcodeAnalyzer, inputImage: InputImage) {
        suspendCancellableCoroutine<Unit> { continuation ->
            analyzer.processInputImage(inputImage) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
