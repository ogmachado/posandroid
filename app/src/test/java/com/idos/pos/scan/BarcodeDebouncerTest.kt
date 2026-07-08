package com.idos.pos.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit test (task 6.1) — [BarcodeDebouncer] has no MLKit/CameraX
 * dependency, so its debounce DECISION is fully covered here without
 * Robolectric or a device/emulator. design.md "on first successful decode,
 * analyzer debounces (ignore repeat frames of same value within ~1s)".
 */
class BarcodeDebouncerTest {

    @Test
    fun firstDecode_isAlwaysEmitted() {
        val debouncer = BarcodeDebouncer(windowMillis = 1_000)

        assertTrue(debouncer.shouldEmit("123456789012", nowMillis = 0))
    }

    @Test
    fun sameValue_withinWindow_isSuppressed() {
        val debouncer = BarcodeDebouncer(windowMillis = 1_000)

        assertTrue(debouncer.shouldEmit("123456789012", nowMillis = 0))
        assertFalse(debouncer.shouldEmit("123456789012", nowMillis = 500))
    }

    @Test
    fun sameValue_afterWindowElapses_isEmittedAgain() {
        val debouncer = BarcodeDebouncer(windowMillis = 1_000)

        assertTrue(debouncer.shouldEmit("123456789012", nowMillis = 0))
        assertTrue(debouncer.shouldEmit("123456789012", nowMillis = 1_000))
    }

    @Test
    fun differentValue_withinWindow_isEmittedImmediately() {
        val debouncer = BarcodeDebouncer(windowMillis = 1_000)

        assertTrue(debouncer.shouldEmit("123456789012", nowMillis = 0))
        assertTrue(debouncer.shouldEmit("999999999999", nowMillis = 100))
    }

    @Test
    fun reset_clearsLastValue_soImmediateRepeatIsEmittedAgain() {
        val debouncer = BarcodeDebouncer(windowMillis = 1_000)

        assertTrue(debouncer.shouldEmit("123456789012", nowMillis = 0))
        debouncer.reset()
        assertTrue(debouncer.shouldEmit("123456789012", nowMillis = 50))
    }
}
