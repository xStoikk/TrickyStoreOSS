/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.tee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeeRetryPolicyTest {
    @Test
    fun postBootCompletedWindowCoversObservedRkpStartupDelay() {
        assertTrue(TeeRetryPolicy.maxPostBootCompletedWindowMs() >= 90_000L)
    }

    @Test
    fun retryScheduleMatchesBoundedBackoff() {
        assertEquals(2_000L, TeeRetryPolicy.delayMs(1))
        assertEquals(4_000L, TeeRetryPolicy.delayMs(2))
        assertEquals(8_000L, TeeRetryPolicy.delayMs(3))
        assertEquals(15_000L, TeeRetryPolicy.delayMs(4))
        assertEquals(104_000L, TeeRetryPolicy.maxPostBootCompletedWindowMs())
    }

    @Test
    fun continuesWhileUnknownAndWithinAttemptBudget() {
        assertTrue(TeeRetryPolicy.shouldContinueRetrying(TeeState.UNKNOWN, 1))
        assertTrue(TeeRetryPolicy.shouldContinueRetrying(TeeState.UNKNOWN, TeeRetryPolicy.MAX_ATTEMPTS))
        assertFalse(TeeRetryPolicy.shouldContinueRetrying(TeeState.UNKNOWN, TeeRetryPolicy.MAX_ATTEMPTS + 1))
    }

    @Test
    fun stopsRetryingOnceWorkingOrBroken() {
        assertFalse(TeeRetryPolicy.shouldContinueRetrying(TeeState.WORKING, 1))
        assertFalse(TeeRetryPolicy.shouldContinueRetrying(TeeState.BROKEN, 1))
    }
}
