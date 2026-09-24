/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.tee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeeProbeClassifierTest {
    @Test
    fun healthyProbeResolvesToWorking() {
        assertEquals(TeeState.WORKING, TeeProbeClassifier.resolveState(TeeState.UNKNOWN, TeeProbeResult.WORKING))
    }

    @Test
    fun genericFailureAfterBootStaysTransient() {
        val result =
            TeeProbeClassifier.classifySignals(
                messages = listOf("Failed to generate key pair"),
                errorCodes = emptyList(),
                bootCompleted = true,
            )
        assertEquals(TeeProbeResult.TRANSIENT, result)
        assertEquals(TeeState.UNKNOWN, TeeProbeClassifier.resolveState(TeeState.UNKNOWN, result))
    }

    @Test
    fun genericFailureBeforeBootStaysTransient() {
        val result =
            TeeProbeClassifier.classifySignals(
                messages = listOf("Failed to generate key pair"),
                errorCodes = emptyList(),
                bootCompleted = false,
            )
        assertEquals(TeeProbeResult.TRANSIENT, result)
        assertEquals(TeeState.UNKNOWN, TeeProbeClassifier.resolveState(TeeState.UNKNOWN, result))
    }

    @Test
    fun bootCompletedDoesNotAffectGenericFailureClassification() {
        val beforeBoot =
            TeeProbeClassifier.classifySignals(
                messages = listOf("Failed to generate key pair"),
                errorCodes = emptyList(),
                bootCompleted = false,
            )
        val afterBoot =
            TeeProbeClassifier.classifySignals(
                messages = listOf("Failed to generate key pair"),
                errorCodes = emptyList(),
                bootCompleted = true,
            )
        assertEquals(TeeProbeResult.TRANSIENT, beforeBoot)
        assertEquals(TeeProbeResult.TRANSIENT, afterBoot)
    }

    @Test
    fun outOfKeysTransientIsTransient() {
        val result =
            TeeProbeClassifier.classifySignals(
                messages = listOf("Failed to generate key pair"),
                errorCodes = listOf(OUT_OF_KEYS_TRANSIENT_ERROR),
                bootCompleted = false,
            )
        assertEquals(TeeProbeResult.TRANSIENT, result)
        assertEquals(TeeState.UNKNOWN, TeeProbeClassifier.resolveState(TeeState.UNKNOWN, result))
    }

    @Test
    fun outOfKeysTransientAfterBootIsTransient() {
        val result =
            TeeProbeClassifier.classifySignals(
                messages = emptyList(),
                errorCodes = listOf(OUT_OF_KEYS_TRANSIENT_ERROR),
                bootCompleted = true,
            )
        assertEquals(TeeProbeResult.TRANSIENT, result)
    }

    @Test
    fun rkpUnavailableDuringBootIsTransient() {
        val result =
            TeeProbeClassifier.classifySignals(
                messages =
                    listOf(
                        "No system services were found hosting com.android.rkpdapp.IRemoteProvisioning",
                        "Failed to get registration",
                    ),
                errorCodes = emptyList(),
                bootCompleted = false,
            )
        assertEquals(TeeProbeResult.TRANSIENT, result)
    }

    @Test
    fun rkpUnavailableAfterBootIsTransient() {
        val result =
            TeeProbeClassifier.classifySignals(
                messages = listOf("Failed to get registration"),
                errorCodes = emptyList(),
                bootCompleted = true,
            )
        assertEquals(TeeProbeResult.TRANSIENT, result)
    }

    @Test
    fun transientThenSuccessBecomesWorking() {
        val transient =
            TeeProbeClassifier.classifySignals(
                messages = listOf("Failed to get registration"),
                errorCodes = listOf(OUT_OF_KEYS_TRANSIENT_ERROR),
                bootCompleted = false,
            )
        assertEquals(TeeState.UNKNOWN, TeeProbeClassifier.resolveState(TeeState.UNKNOWN, transient))
        assertEquals(TeeState.WORKING, TeeProbeClassifier.resolveState(TeeState.UNKNOWN, TeeProbeResult.WORKING))
    }

    @Test
    fun explicitPermanentErrorBecomesBroken() {
        val result =
            TeeProbeClassifier.classifySignals(
                messages = listOf("attestation keys not provisioned"),
                errorCodes = listOf(KM_ERROR_ATTESTATION_KEYS_NOT_PROVISIONED),
                bootCompleted = true,
            )
        assertEquals(TeeProbeResult.BROKEN, result)
        assertEquals(TeeState.BROKEN, TeeProbeClassifier.resolveState(TeeState.UNKNOWN, result))
    }

    @Test
    fun outOfKeysPermanentErrorBecomesBrokenEvenBeforeBoot() {
        val result =
            TeeProbeClassifier.classifySignals(
                messages = emptyList(),
                errorCodes = listOf(OUT_OF_KEYS_PERMANENT_ERROR),
                bootCompleted = false,
            )
        assertEquals(TeeProbeResult.BROKEN, result)
        assertEquals(TeeState.BROKEN, TeeProbeClassifier.resolveState(TeeState.UNKNOWN, result))
    }

    @Test
    fun provisioningFailureBeforeBootStaysUnknown() {
        val result =
            TeeProbeClassifier.classifySignals(
                messages = listOf("attestation keys not provisioned"),
                errorCodes = listOf(KM_ERROR_ATTESTATION_KEYS_NOT_PROVISIONED),
                bootCompleted = false,
            )
        assertEquals(TeeProbeResult.TRANSIENT, result)
        assertEquals(TeeState.UNKNOWN, TeeProbeClassifier.resolveState(TeeState.UNKNOWN, result))
    }

    @Test
    fun exhaustedTransientRetriesRemainUnknown() {
        var state = TeeState.UNKNOWN
        repeat(TeeRetryPolicy.MAX_ATTEMPTS) {
            val transient =
                TeeProbeClassifier.classifySignals(
                    messages = listOf("Failed to generate key pair"),
                    errorCodes = emptyList(),
                    bootCompleted = true,
                )
            state = TeeProbeClassifier.resolveState(state, transient)
        }
        assertEquals(TeeState.UNKNOWN, state)
    }

    @Test
    fun unknownDoesNotEnableGenerateMode() {
        assertFalse(TeeProbeClassifier.autoGenerateAllowed(null))
        assertTrue(TeeProbeClassifier.autoLeafHackAllowed(null))
    }

    @Test
    fun brokenAutoDoesNotEnableSyntheticGenerateOrDisableRealKeystoreIntercept() {
        assertFalse(TeeProbeClassifier.autoGenerateAllowed(true))
        assertTrue(TeeProbeClassifier.autoLeafHackAllowed(true))
    }

    @Test
    fun workingPreservesLeafMode() {
        assertFalse(TeeProbeClassifier.autoGenerateAllowed(false))
        assertTrue(TeeProbeClassifier.autoLeafHackAllowed(false))
    }

    private companion object {
        private const val OUT_OF_KEYS_TRANSIENT_ERROR = 25
        private const val OUT_OF_KEYS_PERMANENT_ERROR = 26
        private const val KM_ERROR_ATTESTATION_KEYS_NOT_PROVISIONED = -74
    }
}
