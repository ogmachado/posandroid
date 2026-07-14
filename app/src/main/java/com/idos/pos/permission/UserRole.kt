package com.idos.pos.permission

/**
 * The two roles a locally-authenticated user may have (design.md "Identity
 * model & credential mechanics", `user-identity` spec "Every user has exactly
 * one role"). Stored on [UserEntity.role] as [UserRole.name] — a plain string
 * column, no `Converters.kt` change (mirrors how `license_state` stores raw
 * strings).
 */
enum class UserRole { ADMIN, CASHIER }
