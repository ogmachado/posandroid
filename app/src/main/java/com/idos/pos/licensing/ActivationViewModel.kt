package com.idos.pos.licensing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the activation flow (task 4.1; specs/license-activation/spec.md).
 * Exposed by [ActivationViewModel] and consumed by [ActivationScreen] — the
 * closest existing analog is [com.idos.pos.scan.ScanUiState] (a sealed
 * multi-step result type owned by a single-[AppContainer]-arg ViewModel).
 */
sealed interface ActivationUiState {
    data object Idle : ActivationUiState

    /**
     * Verify-without-persist result for a not-yet-installed candidate
     * (spec.md "Preview Before Install"). [claims] mirrors
     * [LicenseRepository.preview]'s own [LicensePreview.claims] — present for
     * VALID/IN_GRACE_PERIOD/EXPIRED, `null` for a structural/crypto/binding
     * rejection (INVALID_SIGNATURE/WRONG_PRODUCT/MACHINE_MISMATCH/MALFORMED).
     */
    data class Preview(
        val jws: String,
        val status: LicenseStatus,
        val claims: LicenseVerifier.Claims?,
    ) : ActivationUiState {
        /**
         * Only VALID/IN_GRACE_PERIOD candidates may be confirmed for install —
         * mirrors [LicenseRepository.install]'s own acceptance set (an
         * already-EXPIRED candidate is rejected at install time even though it
         * previews with claims — see that method's KDoc).
         */
        val isInstallable: Boolean
            get() = status == LicenseStatus.VALID || status == LicenseStatus.IN_GRACE_PERIOD
    }

    data object Installing : ActivationUiState
    data class InstallSuccess(val status: LicenseStatus) : ActivationUiState
    data class InstallFailure(val message: String) : ActivationUiState
}

/**
 * Backs [ActivationScreen] (task 4.1) — file-import (SAF `OpenDocument`) /
 * paste-text → preview → confirm → install, per
 * specs/license-activation/spec.md. Constructed via
 * [com.idos.pos.core.di.posViewModel] with the sole [AppContainer] constructor
 * argument, same convention as [com.idos.pos.catalog.ProductViewModel] /
 * [com.idos.pos.scan.ScanViewModel].
 *
 * **Deviation — secondary [LicenseRepository] constructor for testability**:
 * the primary constructor takes [LicenseRepository] directly rather than only
 * [AppContainer]. [AppContainer.licenseRepository] is wired to a
 * Keystore-backed [InstallationIdStore], and Android Keystore is NOT available
 * under Robolectric in this environment — confirmed by [InstallationIdStore]'s
 * own KDoc and by the removed `InstallationIdStoreTest` (task 2.1: "Keystore
 * unavailable under Robolectric here"). [installationId] below calls
 * [LicenseRepository.installationId] eagerly, and every one of
 * [LicenseRepository]'s methods calls it internally too — so constructing this
 * ViewModel against a *real* [AppContainer] under Robolectric would throw
 * `KeyStoreException` unconditionally, making it untestable no matter which
 * method is exercised. [ActivationScreenTest] (task 4.3) therefore constructs
 * this class with a fake [LicenseRepository] built the exact same way
 * [LicenseRepositoryTest] already does (fakes for dao/verify/
 * installationIdProvider/evaluateHeartbeat) — never through a real
 * [AppContainer]. This mirrors how [com.idos.pos.permission.PinGate] already
 * decouples the (also Keystore-bound) [com.idos.pos.permission.PinRepository]
 * from testable call sites in this codebase.
 *
 * The public `constructor(container: AppContainer)` below is what
 * [com.idos.pos.core.di.posViewModel]'s `getConstructor(AppContainer::class.java)`
 * reflection call finds for production wiring — reflection matches by exact
 * parameter type, so this second constructor does not interfere with it.
 */
class ActivationViewModel internal constructor(
    private val licenseRepository: LicenseRepository,
) : ViewModel() {

    constructor(container: AppContainer) : this(container.licenseRepository)

    /**
     * [LicenseRepository.installationId] — shown (copyable) on
     * [ActivationScreen] so the operator can report it to the vendor for
     * issuance (spec.md's activation flow + design.md "Data flow").
     */
    val installationId: String = licenseRepository.installationId()

    private val _uiState = MutableStateFlow<ActivationUiState>(ActivationUiState.Idle)
    val uiState: StateFlow<ActivationUiState> = _uiState.asStateFlow()

    /**
     * Preview-without-persist entry point for BOTH the file-import and the
     * paste-text path (spec.md "Both File Import and Paste Are Supported") —
     * [ActivationScreen] funnels the decoded JWS string from either input
     * method through this single method, so both produce an identical
     * [ActivationUiState.Preview] outcome for the same license content.
     */
    fun onCandidateProvided(jws: String) {
        val preview = licenseRepository.preview(jws).getOrNull()
        _uiState.value = if (preview != null) {
            ActivationUiState.Preview(jws = jws, status = preview.status, claims = preview.claims)
        } else {
            // preview() is documented to never fail as a Result — this branch
            // is an unreachable-in-practice safety net, not a real code path.
            ActivationUiState.InstallFailure(GENERIC_REJECTION_MESSAGE)
        }
    }

    /**
     * Confirms and persists the currently previewed candidate (spec.md
     * "Install Persists a Verified License" / "Rollback/Replay Protection").
     * No-op if nothing is currently previewed.
     */
    fun confirmInstall() {
        val previewed = _uiState.value as? ActivationUiState.Preview ?: return
        _uiState.value = ActivationUiState.Installing
        viewModelScope.launch {
            val result = licenseRepository.install(previewed.jws)
            // Stale-write guard: [ActivationScreen] disables the preview/import
            // controls while status is Installing (defense #1), but this is a
            // second, source-of-truth-level defense — if uiState is no longer
            // Installing by the time this coroutine resolves (e.g. a future
            // change re-enables those controls, or reset() ran), a NEWER
            // preview/state is already showing and this stale result must be
            // dropped rather than stomping it.
            if (_uiState.value == ActivationUiState.Installing) {
                _uiState.value = result.fold(
                    onSuccess = { status -> ActivationUiState.InstallSuccess(status) },
                    onFailure = { ActivationUiState.InstallFailure(rejectionMessage(result.domainErrorOrNull())) },
                )
            }
        }
    }

    /**
     * Returns to [ActivationUiState.Idle] — e.g. after an install failure, so
     * the operator can try a different candidate.
     */
    fun reset() {
        _uiState.value = ActivationUiState.Idle
    }

    companion object {
        const val GENERIC_REJECTION_MESSAGE = "Could not read this license."

        /**
         * Distinguishable, operator-actionable message per rejection reason —
         * specs/license-activation/spec.md's own "Design-Level Open Questions"
         * section flags this as unresolved ("the operator cannot self-diagnose
         * a cryptographic failure"), and design.md never actually closes it
         * despite its "Open Questions: None" claim. Scoped to this
         * ViewModel/Screen pair only — deliberately not a general-purpose
         * DomainError-to-string mapper (no such mapper exists elsewhere in
         * this codebase; other screens just interpolate `"$lastError"`).
         */
        fun rejectionMessage(status: LicenseStatus): String = when (status) {
            LicenseStatus.INVALID_SIGNATURE -> "This license file's signature could not be verified."
            LicenseStatus.WRONG_PRODUCT -> "This license was not issued for this app."
            LicenseStatus.MACHINE_MISMATCH -> "This license is bound to a different device."
            LicenseStatus.EXPIRED -> "This license has expired."
            LicenseStatus.MALFORMED -> "This license file is invalid or corrupted."
            LicenseStatus.COMPROMISED -> "This device's license has been flagged as compromised."
            LicenseStatus.NOT_CONFIGURED -> "No license is currently installed."
            LicenseStatus.VALID, LicenseStatus.IN_GRACE_PERIOD -> ""
        }

        private fun rejectionMessage(error: DomainError?): String = when (error) {
            is DomainError.LicenseVerificationRejected -> rejectionMessage(error.status)
            DomainError.LicenseRollbackRejected -> "This license is older than the one already installed."
            else -> GENERIC_REJECTION_MESSAGE
        }
    }
}
