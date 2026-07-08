package com.idos.pos.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel
import java.util.concurrent.Executors

/**
 * Dedicated full-screen scan surface (task 6.2; design.md "CameraX + MLKit
 * barcode integration" — "not a dialog — full camera surface reads best").
 * `AndroidView { PreviewView }` bound to a CameraX `Preview` + `ImageAnalysis`
 * use case on the current lifecycle, feeding frames through [BarcodeAnalyzer]
 * once camera permission is held.
 *
 * **Permission flow**: [rememberLauncherForActivityResult] on `CAMERA`.
 * [resolveCameraPermissionState] resolves the current [CameraPermissionState]
 * and [CameraPermissionGate] renders the matching UI — rationale + "grant"
 * while ungranted, a settings deep-link hint once permanently denied.
 *
 * **Cart-wiring deviation (tasks.md 6.3)**: tasks.md's literal wording is
 * "wire decode → CatalogRepository.findByBarcode → cart-add hit / unknown-
 * barcode create-product prompt on miss", but `sales/CartViewModel` does not
 * exist yet — it lands in Phase 8 (PR7), after this PR. [onProductFound] is
 * therefore an explicit callback: this screen and [ScanViewModel] have NO
 * dependency on any sales/cart type. Whoever wires Phase 8's `PosScreen`
 * passes an `onProductFound = { product -> cartViewModel.addToCart(product) }`
 * lambda here. The "unknown barcode → offer create product" path IS wired
 * for real via [onUnknownBarcode] — `catalog/ProductFormScreen.kt` already
 * exists (Phase 4) and accepts an `initialBarcode` to pre-fill (see that
 * screen's doc). Same deferred-TODO pattern
 * [com.idos.pos.catalog.CatalogRepository]'s class doc used for the Phase 4 →
 * Phase 5 inventory-seed TODO.
 */
@Composable
fun BarcodeScanScreen(
    onProductFound: (ProductEntity) -> Unit,
    onUnknownBarcode: (barcode: String) -> Unit,
    viewModel: ScanViewModel = posViewModel(LocalAppContainer.current),
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var permissionState by remember {
        mutableStateOf(
            resolveCameraPermissionState(
                hasPermission = hasCameraPermission(context),
                hasRequestedBefore = false,
                shouldShowRationale = false,
            ),
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionState = resolveCameraPermissionState(
            hasPermission = granted,
            hasRequestedBefore = true,
            shouldShowRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
            } ?: false,
        )
    }

    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is ScanUiState.Found -> {
                onProductFound(state.product)
                viewModel.resetToIdle()
            }
            is ScanUiState.NotFound -> {
                onUnknownBarcode(state.barcode)
                viewModel.resetToIdle()
            }
            ScanUiState.Idle -> Unit
        }
    }

    CameraPermissionGate(
        state = permissionState,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onOpenAppSettings = { context.openAppSettings() },
    ) {
        CameraPreviewWithAnalysis(
            modifier = Modifier.fillMaxSize(),
            onBarcodeScanned = viewModel::onBarcodeDecoded,
        )
    }
}

/**
 * Renders [content] when [state] is [CameraPermissionState.Granted], else
 * the matching rationale/permanently-denied UI. Split out from
 * [BarcodeScanScreen] so it can be exercised directly by
 * [CameraPermissionGateTest] under Robolectric with a synthetic [state] —
 * there is no way to drive a real system permission dialog under Robolectric
 * (same testability boundary [com.idos.pos.permission.PinGateDialog] draws
 * around [com.idos.pos.permission.PinRepository]).
 */
@Composable
fun CameraPermissionGate(
    state: CameraPermissionState,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    when (state) {
        CameraPermissionState.Granted -> content()
        CameraPermissionState.NotGranted -> CameraPermissionRationale(onRequestPermission)
        CameraPermissionState.PermanentlyDenied -> CameraPermissionPermanentlyDenied(onOpenAppSettings)
    }
}

@Composable
private fun CameraPermissionRationale(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Camera access is needed to scan product barcodes.",
            modifier = Modifier.testTag(CAMERA_RATIONALE_TEXT_TEST_TAG),
        )
        Button(
            onClick = onRequestPermission,
            modifier = Modifier.testTag(CAMERA_GRANT_BUTTON_TEST_TAG),
        ) {
            Text("Grant camera access")
        }
    }
}

@Composable
private fun CameraPermissionPermanentlyDenied(onOpenAppSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Camera permission was denied. Enable it from Settings to scan barcodes.",
            modifier = Modifier.testTag(CAMERA_DENIED_TEXT_TEST_TAG),
        )
        Button(
            onClick = onOpenAppSettings,
            modifier = Modifier.testTag(CAMERA_OPEN_SETTINGS_BUTTON_TEST_TAG),
        ) {
            Text("Open Settings")
        }
    }
}

/**
 * Live CameraX preview + [BarcodeAnalyzer] wiring. Genuinely device-bound —
 * `ProcessCameraProvider` requires a real (or emulated) camera and cannot run
 * under Robolectric — so this is not covered by a JVM test; see
 * [BarcodeScanPipelineTest] (instrumented, task 6.4) for the decode-side
 * coverage this composable's [BarcodeAnalyzer] uses.
 */
@Composable
private fun CameraPreviewWithAnalysis(
    modifier: Modifier = Modifier,
    onBarcodeScanned: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = modifier.testTag(CAMERA_PREVIEW_TEST_TAG),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(cameraExecutor, BarcodeAnalyzer(onBarcodeScanned = onBarcodeScanned)) }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
    }
    startActivity(intent)
}

const val CAMERA_RATIONALE_TEXT_TEST_TAG = "camera-permission-rationale-text"
const val CAMERA_GRANT_BUTTON_TEST_TAG = "camera-permission-grant-button"
const val CAMERA_DENIED_TEXT_TEST_TAG = "camera-permission-denied-text"
const val CAMERA_OPEN_SETTINGS_BUTTON_TEST_TAG = "camera-permission-open-settings-button"
const val CAMERA_PREVIEW_TEST_TAG = "camera-preview"
