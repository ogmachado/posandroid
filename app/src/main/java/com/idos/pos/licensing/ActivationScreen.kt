package com.idos.pos.licensing

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel

/**
 * Operator-facing activation screen (task 4.2; specs/license-activation/spec.md).
 * Shows this device's installation-ID (copyable, to report to the vendor),
 * accepts a candidate license via file-import (SAF `OpenDocument`) or
 * paste-text, previews it, and lets the operator confirm the install.
 *
 * **Deliberately a plain [Column], not `Scaffold`** — same reasoning as
 * [com.idos.pos.catalog.ProductFormScreen]'s class doc: this screen has no
 * top bar/FAB to justify `Scaffold`, and this codebase has a documented
 * Robolectric `AppNotIdleException` hang from `Scaffold` composed alongside a
 * dialog-like conditional composition in this environment.
 *
 * **SAF wiring (design.md "In Scope" / no prior SAF usage in this repo)**:
 * mirrors [com.idos.pos.scan.BarcodeScanScreen]'s launcher-wiring idiom — this
 * composable holds the [androidx.activity.result.ActivityResultLauncher] via
 * [rememberLauncherForActivityResult], and its callback pushes the result
 * (here: the file's decoded JWS text, read via [readJwsFromUri]) into the
 * ViewModel, exactly like [com.idos.pos.scan.ScanViewModel.onBarcodeDecoded]
 * receives a decoded barcode from [com.idos.pos.scan.BarcodeAnalyzer]. Both
 * the file-import and paste-text paths converge on the SAME
 * [ActivationViewModel.onCandidateProvided] call so they produce identical
 * outcomes for identical content (spec.md "Both File Import and Paste Are
 * Supported").
 */
@Composable
fun ActivationScreen(
    onInstalled: () -> Unit = {},
    viewModel: ActivationViewModel = posViewModel(LocalAppContainer.current),
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uiState by viewModel.uiState.collectAsState()

    var pastedText by remember { mutableStateOf("") }
    var fileReadError by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val jws = readJwsFromUri(context, uri)
            if (jws != null) {
                fileReadError = null
                viewModel.onCandidateProvided(jws)
            } else {
                fileReadError = "Could not read the selected file."
            }
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ActivationUiState.InstallSuccess) {
            onInstalled()
        }
    }

    // Disabled while an install() is in flight — otherwise the operator could
    // start a NEW preview (file or paste) mid-install, clobbering the
    // Installing state; when the original install() later resolves it would
    // then stomp that newer preview with a stale success/failure result. See
    // ActivationViewModel.confirmInstall's stale-write guard for the matching
    // defense-in-depth on the ViewModel side.
    val candidateControlsEnabled = uiState !is ActivationUiState.Installing

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(text = "This device's installation ID (report this to the vendor):")
        Text(
            text = viewModel.installationId,
            modifier = Modifier.testTag(INSTALLATION_ID_TEST_TAG),
        )
        Button(
            onClick = { clipboardManager.setText(AnnotatedString(viewModel.installationId)) },
            modifier = Modifier.testTag(COPY_INSTALLATION_ID_BUTTON_TEST_TAG),
        ) {
            Text("Copy installation ID")
        }

        Button(
            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            enabled = candidateControlsEnabled,
            modifier = Modifier.testTag(IMPORT_FILE_BUTTON_TEST_TAG),
        ) {
            Text("Import license file")
        }
        if (fileReadError != null) {
            Text(
                text = fileReadError.orEmpty(),
                modifier = Modifier.testTag(FILE_READ_ERROR_TEST_TAG),
            )
        }

        OutlinedTextField(
            value = pastedText,
            onValueChange = { pastedText = it },
            enabled = candidateControlsEnabled,
            label = { Text("Or paste license text") },
            modifier = Modifier.fillMaxWidth().testTag(PASTE_TEXT_FIELD_TEST_TAG),
        )
        Button(
            onClick = { viewModel.onCandidateProvided(pastedText) },
            enabled = candidateControlsEnabled,
            modifier = Modifier.testTag(PASTE_PREVIEW_BUTTON_TEST_TAG),
        ) {
            Text("Preview pasted license")
        }

        when (val state = uiState) {
            ActivationUiState.Idle -> Unit

            is ActivationUiState.Preview -> {
                Text(
                    text = "Status: ${state.status}",
                    modifier = Modifier.testTag(PREVIEW_STATUS_TEST_TAG),
                )
                if (state.claims != null) {
                    val claims = state.claims
                    Text(
                        text = "Business: ${claims.businessName.orEmpty()} — expires ${claims.expirationDate}",
                        modifier = Modifier.testTag(PREVIEW_CLAIMS_TEST_TAG),
                    )
                }
                if (state.isInstallable) {
                    Button(
                        onClick = { viewModel.confirmInstall() },
                        modifier = Modifier.testTag(INSTALL_BUTTON_TEST_TAG),
                    ) {
                        Text("Install")
                    }
                } else {
                    Text(
                        text = ActivationViewModel.rejectionMessage(state.status),
                        modifier = Modifier.testTag(REJECTION_MESSAGE_TEST_TAG),
                    )
                }
            }

            ActivationUiState.Installing -> {
                CircularProgressIndicator(modifier = Modifier.testTag(INSTALLING_INDICATOR_TEST_TAG))
            }

            is ActivationUiState.InstallSuccess -> {
                Text(
                    text = "License installed — status: ${state.status}",
                    modifier = Modifier.testTag(INSTALL_SUCCESS_TEST_TAG),
                )
            }

            is ActivationUiState.InstallFailure -> {
                Text(
                    text = state.message,
                    modifier = Modifier.testTag(INSTALL_FAILURE_TEST_TAG),
                )
            }
        }
    }
}

/**
 * Reads the full text content of a SAF-picked [uri] (a `.lic` file, expected
 * to contain a raw JWS string) via [Context.getContentResolver]. `null` on any
 * I/O failure (missing permission, revoked grant, unreadable stream) — the
 * caller surfaces a generic read-failure message rather than a
 * license-verification-specific one, since this failure happens before
 * [LicenseRepository] ever sees the content.
 */
internal fun readJwsFromUri(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.trim()
} catch (e: Exception) {
    null
}

const val INSTALLATION_ID_TEST_TAG = "activation-installation-id"
const val COPY_INSTALLATION_ID_BUTTON_TEST_TAG = "activation-copy-installation-id-button"
const val IMPORT_FILE_BUTTON_TEST_TAG = "activation-import-file-button"
const val FILE_READ_ERROR_TEST_TAG = "activation-file-read-error"
const val PASTE_TEXT_FIELD_TEST_TAG = "activation-paste-text-field"
const val PASTE_PREVIEW_BUTTON_TEST_TAG = "activation-paste-preview-button"
const val PREVIEW_STATUS_TEST_TAG = "activation-preview-status"
const val PREVIEW_CLAIMS_TEST_TAG = "activation-preview-claims"
const val INSTALL_BUTTON_TEST_TAG = "activation-install-button"
const val INSTALLING_INDICATOR_TEST_TAG = "activation-installing-indicator"
const val INSTALL_SUCCESS_TEST_TAG = "activation-install-success"
const val INSTALL_FAILURE_TEST_TAG = "activation-install-failure"
const val REJECTION_MESSAGE_TEST_TAG = "activation-rejection-message"
