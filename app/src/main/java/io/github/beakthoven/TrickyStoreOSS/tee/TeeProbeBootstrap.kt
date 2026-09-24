/*

 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>

 * SPDX-License-Identifier: GPL-3.0-or-later

 */



package io.github.beakthoven.TrickyStoreOSS.tee



import android.os.Build

import io.github.beakthoven.TrickyStoreOSS.logging.DiagLog



/**

 * One-shot process bootstrap for the Java AndroidKeyStore probe.

 *

 * [android.app.ActivityThread.initializeMainlineModules] is not idempotent on Android 16;

 * repeated calls throw IllegalStateException ("setTelephonyServiceManager called twice!").

 * [android.security.keystore2.AndroidKeyStoreProvider.install] should be invoked once per process.

 */

object TeeProbeBootstrap {

    sealed class Result {

        data object Ready : Result()



        data class Failed(val cause: Throwable) : Result()

    }



    private val lock = Any()



    @Volatile private var ready = false



    @Volatile private var failure: Throwable? = null



    fun ensureInitialized(): Result {

        if (ready) return Result.Ready

        failure?.let { return Result.Failed(it) }

        synchronized(lock) {

            if (ready) return Result.Ready

            failure?.let { return Result.Failed(it) }

            DiagLog.probeBootstrapStart()

            TeePhase3Trace.probeBootstrap("start")

            return try {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                    android.app.ActivityThread.initializeMainlineModules()

                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                    android.security.keystore2.AndroidKeyStoreProvider.install()

                } else {

                    android.security.keystore.AndroidKeyStoreProvider.install()

                }

                ready = true

                DiagLog.probeBootstrapSuccess()

                TeePhase3Trace.probeBootstrap("success")

                Result.Ready

            } catch (e: Exception) {

                failure = e

                DiagLog.probeBootstrapFailure(e)

                TeePhase3Trace.probeBootstrap("failure")

                Result.Failed(e)

            }

        }

    }



    internal fun resetForTest() {

        synchronized(lock) {

            ready = false

            failure = null

        }

    }

}



