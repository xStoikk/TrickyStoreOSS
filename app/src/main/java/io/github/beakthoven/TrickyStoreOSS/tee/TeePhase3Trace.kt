/*

 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>

 * SPDX-License-Identifier: GPL-3.0-or-later

 */



package io.github.beakthoven.TrickyStoreOSS.tee



import android.os.SystemClock

import android.os.SystemProperties

import java.io.File



/** Append-only state-machine trace; survives early-boot logcat loss. */

object TeePhase3Trace {

    private const val CONFIG_DIR = "/data/adb/tricky_store"

    private const val TRACE_FILE = "tee_phase3_trace.log"

    private val traceLock = Any()



    fun initialize() {

        runCatching {

            File(CONFIG_DIR).mkdirs()

            append("BUILD_ID ${TeeBuildInfo.BUILD_ID}")

        }

    }



    fun teeInit(state: TeeState) {

        append("TEE_INIT state=$state ${context()}")

    }



    fun probeStart(attempt: String) {

        append("PROBE_START attempt=$attempt ${context()}")

    }



    fun probeBootstrap(event: String) {

        append("PROBE_BOOTSTRAP event=$event ${context()}")

    }



    fun probeOperationStage(attempt: String, stage: String) {

        append("PROBE_OPERATION attempt=$attempt stage=$stage ${context()}")

    }



    fun probeOperationResult(attempt: String, outcome: String) {

        append("PROBE_OPERATION_RESULT attempt=$attempt outcome=$outcome ${context()}")

    }



    fun probeResult(attempt: String, classification: TeeProbeResult, code: Int?) {

        val codeLabel = code?.toString() ?: "none"

        append("PROBE_RESULT attempt=$attempt classification=$classification code=$codeLabel ${context()}")

    }



    fun probeSuccess(attempt: String) {

        append("PROBE_RESULT attempt=$attempt classification=SUCCESS ${context()}")

    }



    fun stateTransition(oldState: TeeState, newState: TeeState) {

        append("STATE old=$oldState new=$newState ${context()}")

    }



    fun retryScheduled(attempt: Int, delayMs: Long) {

        append("RETRY_SCHEDULED attempt=$attempt delay_ms=$delayMs ${context()}")

    }



    fun retryExhausted(finalState: TeeState) {

        append("RETRY_EXHAUSTED state=$finalState ${context()}")

    }



    fun teeStatusWrite(teeBroken: Boolean) {

        append("TEE_STATUS_WRITE teeBroken=$teeBroken ${context()}")

    }



    private fun context(): String {

        val uptime = SystemClock.elapsedRealtime()

        val bootCompleted = SystemProperties.get("sys.boot_completed", "unknown")

        return "uptime_ms=$uptime boot_completed=$bootCompleted"

    }



    private fun append(line: String) {

        synchronized(traceLock) {

            runCatching {

                File(CONFIG_DIR, TRACE_FILE).appendText("$line\n")

            }

        }

    }

}

