package com.idos.pos.core.domain

/**
 * Adapter wrapping a [DomainError] in a [Throwable] so repository methods can
 * return `Result<T>` per design.md "Typed failure via Kotlin Result + sealed
 * DomainError, not thrown Spring-style exceptions" — Kotlin's `Result.failure`
 * requires a [Throwable], and [DomainError] deliberately is not one (it is a
 * plain sealed interface, exhaustively `when`-matchable with no stack-trace
 * ceremony). This is the first repository (task 4.3, `CatalogRepository`) to
 * need the `Result<T>`/[DomainError] contract described in design.md's
 * "Interfaces / Contracts" section, so the adapter is introduced here rather
 * than being pre-existing scaffolding from Phase 1.
 *
 * Callers should not inspect [message]/stack trace — always unwrap via [error]
 * (or the [domainErrorOrNull] extension) instead of string-matching.
 */
class DomainException(val error: DomainError) : Exception()

/**
 * Extracts the wrapped [DomainError] from a failed [Result], or `null` if the
 * [Result] succeeded or failed with something other than a [DomainException].
 */
fun <T> Result<T>.domainErrorOrNull(): DomainError? =
    (exceptionOrNull() as? DomainException)?.error
