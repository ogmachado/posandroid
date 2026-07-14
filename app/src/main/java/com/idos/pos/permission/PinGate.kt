package com.idos.pos.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.idos.pos.core.domain.DomainError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reusable manager-PIN gate wrapping any sensitive action — product price edits
 * and `ADJUST` inventory movements (see specs/permission-gate/spec.md).
 *
 * Usage: `pinGate.require { performEdit() }`. The gate shows [PinGateDialog] and
 * invokes the lambda ONLY on a correct PIN; on an incorrect PIN it surfaces
 * [DomainError.PinIncorrect] and the action is never invoked.
 *
 * **No session carry-over**: every [require] call resets gate state and demands
 * a fresh PIN entry — a prior successful check never exempts a later gated
 * action (specs/permission-gate/spec.md "PIN Verification Is a Point-in-Time
 * Check"). There is deliberately no "remember for N minutes" convenience here.
 *
 * Depends on a `verifyPin` function rather than a concrete repository so this
 * class (and [PinGateDialog]) stay unit-testable without touching Android
 * Keystore/EncryptedSharedPreferences (see [PinHasher] doc for why).
 *
 * **Suspend verifier (design.md Decision G, `android-pos-auth` Phase 3)**:
 * [verifyPin] is `suspend` because the credential now lives in Room
 * ([AuthRepository.verifyAdminPin] queries every `ADMIN`-role row on the
 * calling coroutine) — a synchronous main-thread DAO read is forbidden in
 * production. [submit] launches the check in [scope] rather than blocking;
 * the retired single-secret [com.idos.pos.permission.PinHasher]-only lookup
 * this class used before Phase 3 was synchronous only because the old
 * standalone PIN store (the now-deleted `PinRepository`) was a plain
 * EncryptedSharedPreferences read.
 */
class PinGate(
    private val verifyPin: suspend (String) -> Boolean,
    private val scope: CoroutineScope,
) {

    var isVisible: Boolean by mutableStateOf(false)
        private set

    var lastError: DomainError.PinIncorrect? by mutableStateOf(null)
        private set

    /**
     * Bumped on every [require] call. [PinGateDialog] keys its local input-text
     * `remember` on this instead of on [isVisible] so two consecutive gated
     * actions never share leftover input state — merely toggling [isVisible]
     * false→true is not a reliable `remember` disposal boundary across a single
     * composition subtree, but a monotonically-increasing id always is.
     */
    var requestId: Int by mutableStateOf(0)
        private set

    private var pendingAction: (() -> Unit)? = null

    /** Requests a fresh PIN entry before running [action]. Always re-prompts. */
    fun require(action: () -> Unit) {
        pendingAction = action
        lastError = null
        requestId++
        isVisible = true
    }

    /**
     * Called by [PinGateDialog] when the operator submits a PIN attempt.
     * Launches the (now suspend) [verifyPin] check in [scope] — never blocks
     * the caller.
     */
    fun submit(pin: String) {
        val action = pendingAction ?: return
        scope.launch {
            if (verifyPin(pin)) {
                lastError = null
                isVisible = false
                pendingAction = null
                action()
            } else {
                lastError = DomainError.PinIncorrect
            }
        }
    }

    fun dismiss() {
        isVisible = false
        pendingAction = null
        lastError = null
    }
}

/**
 * Wires [AuthRepository.verifyAdminPin] as the gate's suspend verifier
 * (design.md Decision G) — replaces the retired
 * `rememberPinGate(pinRepository: PinRepository)` overload. `scope` is a
 * fresh [rememberCoroutineScope], matching the same idiom
 * [OnboardingScreen] already uses for its own suspend calls.
 */
@Composable
fun rememberPinGate(authRepository: AuthRepository): PinGate {
    val scope = rememberCoroutineScope()
    return remember(authRepository) { PinGate(verifyPin = authRepository::verifyAdminPin, scope = scope) }
}
