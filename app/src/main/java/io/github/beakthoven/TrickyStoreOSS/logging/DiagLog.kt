/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.logging

import android.os.Build
import android.os.Process
import android.os.ServiceSpecificException
import android.os.SystemClock
import android.os.SystemProperties
import android.security.keymaster.KeymasterDefs
import android.system.keystore2.ResponseCode
import io.github.beakthoven.TrickyStoreOSS.tee.TeeBuildInfo
import io.github.beakthoven.TrickyStoreOSS.tee.TeeState
import java.security.KeyStoreException
import java.security.ProviderException

object DiagLog {
    private const val TAG = "TrickyStoreOSS"
    private const val PREFIX = "TS_DIAG"

    private val teeProbeTrigger = ThreadLocal<String?>()

    fun setTeeProbeTrigger(source: String) {
        teeProbeTrigger.set(source)
    }

    fun clearTeeProbeTrigger() {
        teeProbeTrigger.remove()
    }

    private fun emit(line: String) {
        val formatted = "$PREFIX $line"
        runCatching {
            android.util.Log.i(TAG, formatted)
            if (SystemProperties.get("init.svc.logd", "") != "running") {
                System.err.println(formatted)
            }
        }.onFailure {
            System.err.println(formatted)
        }
    }

    fun bootContext(): String =
        runCatching {
            val uptime = SystemClock.elapsedRealtime()
            val bootCompleted = SystemProperties.get("sys.boot_completed", "unknown")
            "uptime_ms=$uptime boot_completed=$bootCompleted"
        }.getOrDefault("uptime_ms=unknown boot_completed=unknown")

    fun buildId() {
        emit("BUILD_ID ${TeeBuildInfo.BUILD_ID}")
    }

    fun daemonStart() {
        emit(
            "DAEMON_START sdk=${Build.VERSION.SDK_INT} pid=${Process.myPid()} wall_ms=${System.currentTimeMillis()} ${bootContext()} logd=${SystemProperties.get("init.svc.logd", "")}",
        )
    }

    fun probeBootstrapStart() {
        emit("PROBE_BOOTSTRAP_START uid=${Process.myUid()} pid=${Process.myPid()} ${bootContext()}")
    }

    fun probeBootstrapSuccess() {
        emit("PROBE_BOOTSTRAP_SUCCESS uid=${Process.myUid()} pid=${Process.myPid()} ${bootContext()}")
    }

    fun probeBootstrapFailure(e: Throwable) {
        emit("PROBE_BOOTSTRAP_FAILURE uid=${Process.myUid()} pid=${Process.myPid()} ${bootContext()}")
        formatExceptionChain(e, prefix = "PROBE_BOOTSTRAP_EXCEPTION").forEach { emit(it) }
    }

    fun teeProbeOperationStart(attempt: String, provider: String, strongBoxBacked: Boolean) {
        emit(
            "TEE_PROBE_OPERATION_START attempt=$attempt trigger=${teeProbeTrigger.get() ?: "direct"} provider=$provider strongbox=$strongBoxBacked uid=${Process.myUid()} pid=${Process.myPid()} ${bootContext()}",
        )
    }

    fun teeProbeOperationStage(attempt: String, stage: String) {
        emit("TEE_PROBE_OPERATION_STAGE attempt=$attempt stage=$stage ${bootContext()}")
    }

    fun teeProbeOperationResult(attempt: String, outcome: String, durationMs: Long) {
        emit(
            "TEE_PROBE_OPERATION_RESULT attempt=$attempt outcome=$outcome duration_ms=$durationMs ${bootContext()}",
        )
    }

    fun teeProbeStart(attempt: String, provider: String, strongBoxBacked: Boolean) {
        teeProbeOperationStart(attempt, provider, strongBoxBacked)
    }

    fun teeProbeSuccess(durationMs: Long) {
        emit("TEE_PROBE_SUCCESS duration_ms=$durationMs ${bootContext()}")
    }

    fun teeProbeFailure(durationMs: Long, e: Throwable) {
        emit("TEE_PROBE_FAILURE duration_ms=$durationMs ${bootContext()}")
        formatExceptionChain(e).forEach { emit(it) }
    }

    fun teeStatus(result: Boolean) {
        emit("TEE_STATUS result=$result ${bootContext()}")
    }

    fun teeStateTransition(oldState: TeeState, newState: TeeState) {
        emit("TEE_STATE old=$oldState new=$newState ${bootContext()}")
    }

    fun teeProbeRetry(attempt: Int, maxAttempts: Int) {
        emit("TEE_PROBE_RETRY attempt=$attempt max=$maxAttempts ${bootContext()}")
    }

    fun teeTransient(bootCompleted: Boolean) {
        emit("TEE_TRANSIENT boot_completed=$bootCompleted ${bootContext()}")
    }

    fun teePermanent(bootCompleted: Boolean) {
        emit("TEE_PERMANENT boot_completed=$bootCompleted ${bootContext()}")
    }

    fun teeDecisionDeferred(state: String) {
        emit("TEE_DECISION deferred state=$state teeBroken_not_written=true ${bootContext()}")
    }

    fun teeDecision(teeBroken: Boolean, path: String) {
        emit("TEE_DECISION teeBroken=$teeBroken source=TEEStatus path=$path ${bootContext()}")
    }

    fun teeStatusWritten(path: String, teeBroken: Boolean) {
        emit("TEE_STATUS_WRITE path=$path teeBroken=$teeBroken")
    }

    fun interceptorAttempt(name: String, attempt: Int, injected: Boolean) {
        emit("INTERCEPTOR_ATTEMPT name=$name attempt=$attempt injected=$injected ${bootContext()}")
    }

    fun interceptorInjectResult(name: String, success: Boolean, attempt: Int) {
        emit("INTERCEPTOR_INJECT name=$name success=$success attempt=$attempt ${bootContext()}")
    }

    fun interceptorRegistered(name: String, tee: Boolean, strongBox: Boolean) {
        emit("INTERCEPTOR_REGISTERED name=$name tee=$tee strongbox=$strongBox ${bootContext()}")
    }

    fun interceptorReady(name: String) {
        emit("INTERCEPTOR_READY name=$name ${bootContext()}")
    }

    fun modeRouting(
        callingUid: Int,
        packages: Array<String>?,
        alias: String?,
        needHack: Boolean,
        needGenerate: Boolean,
        hasDeviceIdAttestation: Boolean,
        hasAttestKeyPurpose: Boolean,
        attestationKeyDescriptorSet: Boolean,
        hasAttestationChallenge: Boolean,
        forceForge: Boolean,
        selected: String,
    ) {
        val pkgLabel = packages?.joinToString(",") ?: "uncached"
        emit(
            "MODE_ROUTE uid=$callingUid pkgs=$pkgLabel alias=${alias ?: "null"} needHack=$needHack needGenerate=$needGenerate hasDeviceIdAttestation=$hasDeviceIdAttestation hasAttestKeyPurpose=$hasAttestKeyPurpose attestationKeyDescriptor=${if (attestationKeyDescriptorSet) "set" else "null"} hasAttestationChallenge=$hasAttestationChallenge forceForge=$forceForge selected=$selected",
        )
    }

    fun deviceIdRoute(callingUid: Int, packages: Array<String>?, selected: String) {
        val pkgLabel = packages?.joinToString(",") ?: "uncached"
        emit("DEVICE_ID_ROUTE uid=$callingUid pkgs=$pkgLabel selected=$selected ${bootContext()}")
    }

    fun deviceIdRealTeeResult(callingUid: Int, success: Boolean, errorCode: Int?, exceptionClass: String?) {
        if (success) {
            emit("DEVICE_ID_REAL_TEE_RESULT uid=$callingUid result=success ${bootContext()}")
            return
        }
        val name = errorCode?.let { keymasterErrorLabel(it) } ?: "unknown"
        emit(
            "DEVICE_ID_REAL_TEE_RESULT uid=$callingUid result=failure code=${errorCode ?: "none"} name=$name exception=${exceptionClass ?: "unknown"} ${bootContext()}",
        )
    }

    fun certPath(action: String, reason: String) {
        emit("CERT_PATH action=$action reason=$reason ${bootContext()}")
    }

    fun passthroughResult(callingUid: Int, success: Boolean, replyUnchanged: Boolean) {
        emit(
            "PASSTHROUGH_RESULT uid=$callingUid result=${if (success) "success" else "failure"} reply_unchanged=$replyUnchanged ${bootContext()}",
        )
    }

    fun passthroughTrack(event: String, uid: Int, aliasHash: String) {
        emit("PASSTHROUGH_TRACK $event uid=$uid alias_hash=$aliasHash ${bootContext()}")
    }

    fun certStateClear(uid: Int, aliasHash: String, reason: String) {
        emit("CERT_STATE_CLEAR uid=$uid alias_hash=$aliasHash reason=$reason")
    }

    private fun formatExceptionChain(e: Throwable, prefix: String = "TEE_PROBE_EXCEPTION"): List<String> {
        val lines = mutableListOf<String>()
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 12) {
            val message = current.message?.replace('\n', ' ')?.trim() ?: ""
            lines.add("$prefix depth=$depth class=${current.javaClass.name} message=$message")
            appendTypedExceptionDetails(lines, depth, current, prefix)
            appendMessageHints(lines, depth, message, prefix)
            current = current.cause
            depth++
        }
        return lines
    }

    private fun appendTypedExceptionDetails(
        lines: MutableList<String>,
        depth: Int,
        current: Throwable,
        prefix: String,
    ) {
        when (current) {
            is ServiceSpecificException -> {
                lines.add(
                    "$prefix depth=$depth ServiceSpecificException errorCode=${current.errorCode} label=${responseCodeLabel(current.errorCode)}",
                )
            }
            is KeyStoreException -> {
                lines.add("$prefix depth=$depth KeyStoreException")
            }
            is ProviderException -> {
                lines.add("$prefix depth=$depth ProviderException")
            }
        }
        val errorCode = extractNumericErrorCode(current)
        if (errorCode != null) {
            lines.add(
                "$prefix depth=$depth numeric_error_code=$errorCode label=${responseCodeLabel(errorCode)}",
            )
        }
    }

    private fun extractNumericErrorCode(current: Throwable): Int? {
        val fields =
            listOf("errorCode", "error", "status", "code", "kmError", "keymasterError", "responseCode")
        for (fieldName in fields) {
            val code =
                runCatching {
                    val field = current.javaClass.getField(fieldName)
                    field.getInt(current)
                }.getOrNull()
            if (code != null) return code
        }
        for (methodName in fields) {
            val code =
                runCatching {
                    val method = current.javaClass.getMethod(methodName)
                    method.invoke(current) as? Int
                }.getOrNull()
            if (code != null) return code
        }
        return null
    }

    private fun appendMessageHints(
        lines: MutableList<String>,
        depth: Int,
        message: String,
        prefix: String,
    ) {
        val lower = message.lowercase()
        val hints =
            linkedMapOf(
                "rkpd" to "RKP_UNAVAILABLE",
                "remoteprovisioning" to "RKP_UNAVAILABLE",
                "remote provisioning" to "RKP_UNAVAILABLE",
                "registration" to "REGISTRATION_UNAVAILABLE",
                "out_of_keys_transient" to "OUT_OF_KEYS_TRANSIENT_ERROR",
                "failed to get registration" to "REGISTRATION_UNAVAILABLE",
                "failed to generate key pair" to "FAILED_GENERATE_KEY_PAIR",
                "attestation keys not provisioned" to "ATTESTATION_KEYS_NOT_PROVISIONED",
                "no system services were found hosting" to "SERVICE_UNAVAILABLE",
                "service unavailable" to "SERVICE_UNAVAILABLE",
                "keystore not ready" to "KEYSTORE_NOT_READY",
            )
        for ((pattern, hint) in hints) {
            if (lower.contains(pattern)) {
                lines.add("${prefix}_HINT depth=$depth hint=$hint pattern=$pattern")
            }
        }
        when {
            lower.contains("out_of_keys") && lower.contains("transient") ->
                lines.add("${prefix}_HINT depth=$depth hint=OUT_OF_KEYS_TRANSIENT_ERROR")
            message.contains("${KeymasterDefs.KM_ERROR_ATTESTATION_KEYS_NOT_PROVISIONED}") ->
                lines.add("${prefix}_HINT depth=$depth hint=ATTESTATION_KEYS_NOT_PROVISIONED")
            lower.contains("settelephonymanager called twice") ->
                lines.add("${prefix}_HINT depth=$depth hint=PROCESS_BOOTSTRAP_REINIT")
        }
        val codeMatch = Regex("(?:error|code)[=: ]*(-?\\d+)", RegexOption.IGNORE_CASE).find(message)
        if (codeMatch != null) {
            val code = codeMatch.groupValues[1].toIntOrNull()
            if (code != null) {
                lines.add("${prefix}_HINT depth=$depth parsed_error_code=$code label=${responseCodeLabel(code)}")
            }
        }
    }

    private fun keymasterErrorLabel(code: Int): String =
        when (code) {
            KeymasterDefs.KM_ERROR_CANNOT_ATTEST_IDS -> "KM_ERROR_CANNOT_ATTEST_IDS"
            KeymasterDefs.KM_ERROR_ATTESTATION_KEYS_NOT_PROVISIONED -> "KM_ERROR_ATTESTATION_KEYS_NOT_PROVISIONED"
            KeymasterDefs.KM_ERROR_ATTESTATION_IDS_NOT_PROVISIONED -> "KM_ERROR_ATTESTATION_IDS_NOT_PROVISIONED"
            KeymasterDefs.KM_ERROR_KEYMINT_NOT_CONFIGURED -> "KM_ERROR_KEYMINT_NOT_CONFIGURED"
            else -> responseCodeLabel(code)
        }

    private fun responseCodeLabel(code: Int): String =
        when (code) {
            ResponseCode.OUT_OF_KEYS_TRANSIENT_ERROR -> "OUT_OF_KEYS_TRANSIENT_ERROR"
            ResponseCode.OUT_OF_KEYS_PERMANENT_ERROR -> "OUT_OF_KEYS_PERMANENT_ERROR"
            ResponseCode.OUT_OF_KEYS -> "OUT_OF_KEYS"
            ResponseCode.OUT_OF_KEYS_PENDING_INTERNET_CONNECTIVITY -> "OUT_OF_KEYS_PENDING_INTERNET_CONNECTIVITY"
            ResponseCode.OUT_OF_KEYS_REQUIRES_SYSTEM_UPGRADE -> "OUT_OF_KEYS_REQUIRES_SYSTEM_UPGRADE"
            KeymasterDefs.KM_ERROR_ATTESTATION_KEYS_NOT_PROVISIONED -> "KM_ERROR_ATTESTATION_KEYS_NOT_PROVISIONED"
            KeymasterDefs.KM_ERROR_ATTESTATION_IDS_NOT_PROVISIONED -> "KM_ERROR_ATTESTATION_IDS_NOT_PROVISIONED"
            KeymasterDefs.KM_ERROR_KEYMINT_NOT_CONFIGURED -> "KM_ERROR_KEYMINT_NOT_CONFIGURED"
            else -> "code_$code"
        }
}
