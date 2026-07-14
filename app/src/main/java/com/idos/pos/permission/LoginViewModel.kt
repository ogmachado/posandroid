package com.idos.pos.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idos.pos.core.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs [LoginScreen] (`login-gate` spec) — lists existing users for the
 * picker UX (design.md "Judgment calls ... Login UX: user-picker + PIN pad,
 * chosen for no-keyboard ergonomics") and authenticates the selected user's
 * PIN via [AuthRepository.login]. On success, [AuthGateViewModel] observes
 * [AuthRepository.currentSession] directly — this ViewModel never navigates.
 * Excluded from strict TDD (ViewModel classes, per `openspec/config.yaml`
 * `strict_tdd_scope.exclude`) — covered alongside by `LoginScreenTest`.
 */
class LoginViewModel(
    private val authRepository: AuthRepository,
    private val userDao: UserDao,
) : ViewModel() {

    constructor(container: AppContainer) : this(container.authRepository, container.userDao)

    private val _users = MutableStateFlow<List<UserEntity>>(emptyList())
    val users: StateFlow<List<UserEntity>> = _users.asStateFlow()

    private val _loginFailed = MutableStateFlow(false)
    val loginFailed: StateFlow<Boolean> = _loginFailed.asStateFlow()

    init {
        viewModelScope.launch { _users.value = userDao.findAll() }
    }

    /** Per-user verification (`login-gate` "Correct User + PIN Authenticates"). */
    fun login(username: String, pin: String) {
        viewModelScope.launch {
            val success = authRepository.login(username, pin)
            _loginFailed.value = !success
        }
    }

    /** Clears a prior failure — called when the operator picks a (possibly different) user again. */
    fun clearLoginFailed() {
        _loginFailed.value = false
    }
}
