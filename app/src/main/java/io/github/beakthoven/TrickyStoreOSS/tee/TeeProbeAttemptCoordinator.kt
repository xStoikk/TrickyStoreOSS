/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.tee

/** Separates one-shot bootstrap from repeatable attestation probe operations. */
class TeeProbeAttemptCoordinator(
    private val bootstrap: () -> Unit,
    private val operate: () -> TeeProbeResult,
) {
    private enum class BootstrapState {
        PENDING,
        READY,
        FAILED,
    }

    private var bootstrapState = BootstrapState.PENDING

    private var bootstrapFailure: Throwable? = null

    var bootstrapInvocationCount: Int = 0
        private set

    var operationInvocationCount: Int = 0
        private set

    val lastBootstrapFailure: Throwable?
        get() = bootstrapFailure

    fun ensureBootstrap(): Boolean {
        when (bootstrapState) {
            BootstrapState.READY -> return true
            BootstrapState.FAILED -> return false
            BootstrapState.PENDING -> {
                bootstrapInvocationCount++
                return try {
                    bootstrap()
                    bootstrapState = BootstrapState.READY
                    true
                } catch (e: Exception) {
                    bootstrapState = BootstrapState.FAILED
                    bootstrapFailure = e
                    false
                }
            }
        }
    }

    /** Returns null when bootstrap is unavailable; otherwise the probe operation result. */
    fun runOperationAttempt(): TeeProbeResult? {
        if (!ensureBootstrap()) return null
        operationInvocationCount++
        return operate()
    }
}

