package com.idos.pos.scan

/**
 * Pure debounce decision for repeat barcode decodes coming off the same
 * frame stream (task 6.1; design.md "CameraX + MLKit barcode integration" —
 * "on first successful decode, analyzer debounces (ignore repeat frames of
 * same value within ~1s)").
 *
 * Deliberately has NO dependency on MLKit/CameraX types so it is fully
 * unit-testable in plain JUnit without a real camera/MLKit pipeline (see
 * [BarcodeDebouncerTest]) — the `scan` package is excluded from strict TDD
 * (openspec/config.yaml `strict_tdd_scope`) precisely because the pipeline
 * around this class is device-bound/exploratory, but the debounce DECISION
 * itself is pure and deterministic, so it gets a real test regardless.
 * [BarcodeAnalyzer] is the thin `ImageAnalysis.Analyzer` glue that feeds
 * decoded values through this class.
 */
class BarcodeDebouncer(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {

    private var lastValue: String? = null
    private var lastEmittedAtMillis: Long = Long.MIN_VALUE

    /**
     * Returns `true` (and records [value]/[nowMillis] as the new baseline)
     * when [value] should be emitted to the caller — either it's a new
     * value, or the same value reappearing after [windowMillis] has
     * elapsed. Returns `false` for a repeat of the same value within the
     * window (the frame should be ignored).
     */
    fun shouldEmit(value: String, nowMillis: Long): Boolean {
        val isRepeatWithinWindow = value == lastValue && (nowMillis - lastEmittedAtMillis) < windowMillis
        if (isRepeatWithinWindow) return false

        lastValue = value
        lastEmittedAtMillis = nowMillis
        return true
    }

    /** Clears debounce state — the next [shouldEmit] call always emits. */
    fun reset() {
        lastValue = null
        lastEmittedAtMillis = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 1_000L
    }
}
