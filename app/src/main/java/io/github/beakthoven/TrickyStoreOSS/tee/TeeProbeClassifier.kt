/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.tee

import android.os.ServiceSpecificException
import android.security.keymaster.KeymasterDefs
import android.system.keystore2.ResponseCode

/** Pure classification of TEE probe failures; testable without running attestation. */
object TeeProbeClassifier {
    fun classifyException(exception: Throwable, bootCompleted: Boolean): TeeProbeResult {
        val signals = extractSignals(exception)
        return classifySignals(signals.messages, signals.errorCodes, bootCompleted)
    }

    fun extractPrimaryErrorCode(exception: Throwable): Int? = extractSignals(exception).errorCodes.firstOrNull()

    fun classifySignals(messages: List<String>, errorCodes: List<Int>, bootCompleted: Boolean): TeeProbeResult {
        for (code in errorCodes) {
            when (code) {
                ResponseCode.OUT_OF_KEYS_TRANSIENT_ERROR,
                ResponseCode.OUT_OF_KEYS_PENDING_INTERNET_CONNECTIVITY,
                ResponseCode.BACKEND_BUSY,
                -> return TeeProbeResult.TRANSIENT
                ResponseCode.OUT_OF_KEYS_PERMANENT_ERROR,
                ResponseCode.OUT_OF_KEYS_REQUIRES_SYSTEM_UPGRADE,
                -> return TeeProbeResult.BROKEN
                KeymasterDefs.KM_ERROR_ATTESTATION_KEYS_NOT_PROVISIONED,
                KeymasterDefs.KM_ERROR_ATTESTATION_IDS_NOT_PROVISIONED,
                KeymasterDefs.KM_ERROR_KEYMINT_NOT_CONFIGURED,
                -> return if (bootCompleted) TeeProbeResult.BROKEN else TeeProbeResult.TRANSIENT
            }
        }

        for (message in messages) {
            val lower = message.lowercase()
            when {
                lower.contains("out_of_keys") && lower.contains("transient") -> return TeeProbeResult.TRANSIENT
                lower.contains("remoteprovisioning") ||
                    lower.contains("rkpd") ||
                    lower.contains("no system services were found hosting") ||
                    lower.contains("failed to get registration") ||
                    lower.contains("failed to get rkpd") ||
                    lower.contains("trying to get attestation key from rkpd") ->
                    return TeeProbeResult.TRANSIENT
                lower.contains("attestation keys not provisioned") ||
                    lower.contains("attestation ids not provisioned") ||
                    lower.contains("keymint not configured") ->
                    return if (bootCompleted) TeeProbeResult.BROKEN else TeeProbeResult.TRANSIENT
            }
        }

        // Ambiguous failures (e.g. "Failed to generate key pair") stay transient even after boot_completed.
        return TeeProbeResult.TRANSIENT
    }

    fun resolveState(current: TeeState, probeResult: TeeProbeResult): TeeState {
        return when (probeResult) {
            TeeProbeResult.WORKING -> TeeState.WORKING
            TeeProbeResult.TRANSIENT -> TeeState.UNKNOWN
            TeeProbeResult.BROKEN -> TeeState.BROKEN
        }
    }

    fun autoGenerateAllowed(teeBroken: Boolean?): Boolean = teeBroken == true

    fun autoLeafHackAllowed(teeBroken: Boolean?): Boolean = teeBroken != true

    private data class ProbeSignals(val messages: List<String>, val errorCodes: List<Int>)

    private fun extractSignals(exception: Throwable): ProbeSignals {
        val messages = mutableListOf<String>()
        val codes = mutableListOf<Int>()
        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth < 12) {
            current.message?.let { messages.add(it) }
            extractErrorCode(current)?.let { codes.add(it) }
            current = current.cause
            depth++
        }
        return ProbeSignals(messages, codes)
    }

    private fun extractErrorCode(current: Throwable): Int? {
        if (current is ServiceSpecificException) return current.errorCode
        val fields = listOf("errorCode", "error", "status", "code", "kmError", "keymasterError", "responseCode")
        for (fieldName in fields) {
            val code =
                runCatching {
                    val field = current.javaClass.getField(fieldName)
                    field.getInt(current)
                }.getOrNull()
            if (code != null) return code
        }
        return null
    }
}
