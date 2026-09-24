/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.tee

object TeeRetryPolicy {
    /**
     * Nine post-boot_completed retries with capped exponential backoff (2s -> 15s).
     * Worst-case probe schedule after boot_completed completes:
     * +2s, +6s, +14s, +29s, +44s, +59s, +74s, +89s, +104s (~104s total).
     */
    const val MAX_ATTEMPTS = 9
    private const val INITIAL_DELAY_MS = 2_000L
    private const val MAX_DELAY_MS = 15_000L
    private const val BOOT_COMPLETED_POLL_MS = 500L
    private const val BOOT_COMPLETED_TIMEOUT_MS = 90_000L

    fun delayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceAtMost(3)
        return minOf(INITIAL_DELAY_MS shl shift, MAX_DELAY_MS)
    }

    fun shouldContinueRetrying(state: TeeState, attempt: Int): Boolean =
        state == TeeState.UNKNOWN && attempt <= MAX_ATTEMPTS

    fun pollBootCompletedIntervalMs(): Long = BOOT_COMPLETED_POLL_MS

    fun bootCompletedTimeoutMs(): Long = BOOT_COMPLETED_TIMEOUT_MS

    /** Sum of inter-probe delays once boot_completed is already true. */
    fun maxPostBootCompletedWindowMs(): Long = (1..MAX_ATTEMPTS).sumOf { delayMs(it) }
}
