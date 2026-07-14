package com.idos.pos.permission

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
 * Backs [UserManagementScreen] (`user-management` / `first-run-onboarding`
 * "credential must be changeable" specs; design.md Decision J). Excluded from
 * strict TDD (ViewModel classes, per `openspec/config.yaml`
 * `strict_tdd_scope.exclude`) — covered alongside by `UserManagementScreenTest`.
 *
 * Reachability itself (ADMIN-only) is enforced one layer up, by [MainActivity]'s
 * `AppRoot` header action (design.md Decision J) — this ViewModel and screen
 * assume they are only ever composed for an ADMIN session; they do not
 * re-check the role themselves.
 */
class UserManagementViewModel(
    private val authRepository: AuthRepository,
    private val userDao: UserDao,
) : ViewModel() {

    constructor(container: AppContainer) : this(container.authRepository, container.userDao)

    private val _users = MutableStateFlow<List<UserEntity>>(emptyList())
    val users: StateFlow<List<UserEntity>> = _users.asStateFlow()

    private val _lastError = MutableStateFlow<DomainError?>(null)
    val lastError: StateFlow<DomainError?> = _lastError.asStateFlow()

    /** One-shot-event counter, same idiom as [com.idos.pos.catalog.ProductViewModel.saveCompleted]. */
    private val _userCreated = MutableStateFlow(0)
    val userCreated: StateFlow<Int> = _userCreated.asStateFlow()

    private val _pinChanged = MutableStateFlow(0)
    val pinChanged: StateFlow<Int> = _pinChanged.asStateFlow()

    init {
        refreshUsers()
    }

    private fun refreshUsers() {
        viewModelScope.launch { _users.value = userDao.findAll() }
    }

    fun clearError() {
        _lastError.value = null
    }

    /**
     * ADMIN-authored account creation (`user-management` "Only ADMIN Can
     * Create Accounts"). Rejects a duplicate username
     * ([DomainError.DuplicateUsername]) and a blank PIN ([DomainError.BlankPin])
     * via [AuthRepository.createUser] — this ViewModel never duplicates that
     * validation, it only surfaces the resulting [DomainError].
     */
    fun createUser(username: String, pin: String, role: UserRole) {
        viewModelScope.launch {
            val result = authRepository.createUser(username, pin, role)
            val error = result.domainErrorOrNull()
            _lastError.value = error
            if (error == null) {
                _userCreated.value += 1
                refreshUsers()
            }
        }
    }

    /**
     * Credential-change flow for the currently authenticated session
     * (`first-run-onboarding` "The Seeded Credential Must Be Changeable") — a
     * no-op if no session is active (should not happen: this screen is only
     * reachable from an authenticated ADMIN header action).
     */
    fun changeOwnPin(newPin: String) {
        val session = authRepository.currentSession.value ?: return
        viewModelScope.launch {
            authRepository.changePin(session.userId, newPin)
            _pinChanged.value += 1
        }
    }
}
