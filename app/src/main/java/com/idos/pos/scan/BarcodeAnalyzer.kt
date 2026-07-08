package com.idos.pos.scan

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * `ImageAnalysis.Analyzer` glue (task 6.1; design.md "CameraX + MLKit
 * barcode integration"). Requests only 1D formats (EAN-13/EAN-8/UPC-A/UPC-E/
 * CODE-128) per design.md ("requesting only 1D formats (EAN/UPC/CODE_128)"),
 * and routes every decode through [debouncer] before invoking
 * [onBarcodeScanned].
 *
 * The debounce DECISION lives entirely in [BarcodeDebouncer] — a plain class
 * with no MLKit/CameraX dependency — so it is covered by a plain JUnit test
 * ([BarcodeDebouncerTest]) instead of an instrumented one. This class is the
 * thin glue that is genuinely device-bound (real `ImageProxy`/MLKit model)
 * and is therefore excluded from strict TDD (the `scan` package, in
 * openspec/config.yaml `strict_tdd_scope`) and covered instead by
 * [BarcodeScanPipelineTest] (task 6.4, instrumented, written alongside).
 */
class BarcodeAnalyzer(
    private val onBarcodeScanned: (String) -> Unit,
    private val debouncer: BarcodeDebouncer = BarcodeDebouncer(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
            )
            .build(),
    )

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        processInputImage(inputImage) { imageProxy.close() }
    }

    /**
     * Runs the same MLKit decode + [debouncer] gate [analyze] uses, but
     * decoupled from [ImageProxy]/CameraX so an instrumented test can drive
     * it directly with an [InputImage] built from a generated bitmap fixture
     * ([BarcodeScanPipelineTest], task 6.4) instead of needing a live camera
     * frame — there is no practical way to fabricate a real `ImageProxy`
     * backed by `android.media.Image` outside an actual camera pipeline.
     */
    internal fun processInputImage(inputImage: InputImage, onComplete: () -> Unit = {}) {
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstNotNullOfOrNull { it.rawValue }
                if (value != null && debouncer.shouldEmit(value, clock())) {
                    onBarcodeScanned(value)
                }
            }
            .addOnCompleteListener { onComplete() }
    }
}
