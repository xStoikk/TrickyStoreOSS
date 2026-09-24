/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS

import android.os.Build
import io.github.beakthoven.TrickyStoreOSS.config.PkgConfig
import io.github.beakthoven.TrickyStoreOSS.interceptors.Keystore2Interceptor
import io.github.beakthoven.TrickyStoreOSS.interceptors.KeystoreInterceptor
import io.github.beakthoven.TrickyStoreOSS.logging.DiagLog
import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import io.github.beakthoven.TrickyStoreOSS.tee.TeePhase3Trace
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

private const val RETRY_DELAY_MS = 1000L

fun main(args: Array<String>) {
    TeePhase3Trace.initialize()
    DiagLog.buildId()
    DiagLog.daemonStart()
    Logger.i("Welcome to TrickyStoreOSS!")
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.addProvider(BouncyCastleProvider())

    try {
        PkgConfig.installTeeStateListener()
        AndroidUtils.setupBootHash()
        AttestUtils.startTeeStateInitialization()
        initializeInterceptors()
        maintainService()
    } catch (e: Exception) {
        Logger.e("Fatal error in main", e)
        throw e
    }
}

private fun initializeInterceptors() {
    val interceptor = selectKeystoreInterceptor()
    var initAttempts = 0

    while (!interceptor.tryRunKeystoreInterceptor()) {
        initAttempts++
        Logger.d("Retrying interceptor initialization...")
        Thread.sleep(RETRY_DELAY_MS)
    }

    PkgConfig.initialize()
    DiagLog.interceptorReady(interceptor::class.simpleName ?: "unknown")
    Logger.i("Interceptors initialized successfully after ${initAttempts + 1} attempt(s)")
}

private fun selectKeystoreInterceptor() =
    when {
        Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R -> {
            Logger.i("Using KeystoreInterceptor for Android Q/R (SDK ${Build.VERSION.SDK_INT})")
            KeystoreInterceptor
        }
        else -> {
            Logger.i("Using Keystore2Interceptor for Android S+ (SDK ${Build.VERSION.SDK_INT})")
            Keystore2Interceptor
        }
    }

private fun maintainService() {
    Logger.i("Service started, entering maintenance mode")
    Thread.currentThread().join()
}
