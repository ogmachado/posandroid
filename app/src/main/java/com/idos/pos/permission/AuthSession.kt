package com.idos.pos.permission

/**
 * In-memory authenticated-session identity (design.md Decision F —
 * `AuthRepository.currentSession`). Never persisted — `user-identity` spec
 * "Session Identity Is Held In-Memory Only".
 */
data class AuthSession(val userId: Long, val username: String, val role: UserRole)
