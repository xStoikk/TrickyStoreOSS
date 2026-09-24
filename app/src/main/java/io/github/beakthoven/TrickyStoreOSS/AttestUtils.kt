/*

 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>

 * SPDX-License-Identifier: GPL-3.0-or-later

 */



package io.github.beakthoven.TrickyStoreOSS



import android.os.Build

import android.os.SystemProperties

import android.security.keystore.KeyGenParameterSpec

import android.security.keystore.KeyProperties

import io.github.beakthoven.TrickyStoreOSS.logging.DiagLog

import io.github.beakthoven.TrickyStoreOSS.logging.Logger

import io.github.beakthoven.TrickyStoreOSS.tee.TeePhase3Trace

import io.github.beakthoven.TrickyStoreOSS.tee.TeeProbeBootstrap

import io.github.beakthoven.TrickyStoreOSS.tee.TeeProbeClassifier

import io.github.beakthoven.TrickyStoreOSS.tee.TeeProbeResult

import io.github.beakthoven.TrickyStoreOSS.tee.TeeRetryPolicy

import io.github.beakthoven.TrickyStoreOSS.tee.TeeState

import java.security.KeyPairGenerator

import java.security.KeyStore

import java.security.SecureRandom

import java.security.cert.X509Certificate

import java.security.spec.ECGenParameterSpec

import org.bouncycastle.asn1.ASN1Integer

import org.bouncycastle.asn1.ASN1ObjectIdentifier

import org.bouncycastle.asn1.ASN1OctetString

import org.bouncycastle.asn1.ASN1Sequence

import org.bouncycastle.asn1.ASN1TaggedObject

import org.bouncycastle.cert.X509CertificateHolder



val ATTESTATION_OID = ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")



object AttestUtils {

    data class AttestationData(

        val verifiedBootKey: ByteArray?,

        val verifiedBootHash: ByteArray?,

        val attestVersion: Int?,

        val keymasterVersion: Int?,

        val osVersion: Int?,

        val moduleHash: ByteArray?,

    )



    @Volatile private var state: TeeState = TeeState.UNKNOWN

    @Volatile private var cachedAttestData: AttestationData? = null

    private val stateLock = Any()



    var stateListener: ((TeeState, TeeState) -> Unit)? = null



    val TEEStatus: Boolean

        get() = state == TeeState.WORKING



    fun getState(): TeeState = state



    val CachedAttestData: AttestationData?

        get() {

            if (state != TeeState.WORKING) return null

            synchronized(stateLock) {

                if (cachedAttestData == null) {

                    cachedAttestData = loadAttestData()

                }

                return cachedAttestData

            }

        }



    private val keygen_alias = "TrickyStoreOSS_attest"



    fun startTeeStateInitialization() {

        TeePhase3Trace.teeInit(state)

        Thread(

            {

                runCatching {

                    runInitialProbe()

                    runRetryLoop()

                    if (state == TeeState.UNKNOWN) {

                        TeePhase3Trace.retryExhausted(state)

                    }

                }.onFailure { Logger.e("TEE state initialization thread failed", it) }

            },

            "TrickyStoreOSS-TeeProbe",

        )

            .apply { isDaemon = true }

            .start()

    }



    private fun runInitialProbe() {

        runProbeAttempt("initial")

    }



    private fun runRetryLoop() {

        var attempt = 1

        while (TeeRetryPolicy.shouldContinueRetrying(state, attempt)) {

            waitForBootCompleted()

            val delay = TeeRetryPolicy.delayMs(attempt)

            TeePhase3Trace.retryScheduled(attempt, delay)

            Thread.sleep(delay)

            if (state != TeeState.UNKNOWN) break

            DiagLog.teeProbeRetry(attempt, TeeRetryPolicy.MAX_ATTEMPTS)

            runProbeAttempt("$attempt/${TeeRetryPolicy.MAX_ATTEMPTS}")

            attempt++

        }

    }



    private fun waitForBootCompleted() {

        if (isBootCompleted()) return

        val deadline = System.currentTimeMillis() + TeeRetryPolicy.bootCompletedTimeoutMs()

        while (System.currentTimeMillis() < deadline) {

            if (isBootCompleted()) return

            Thread.sleep(TeeRetryPolicy.pollBootCompletedIntervalMs())

        }

    }



    private fun isBootCompleted(): Boolean = SystemProperties.get("sys.boot_completed", "0") == "1"



    private fun applyProbeResult(result: TeeProbeResult, waitForBootCompleted: Boolean) {

        val bootCompleted = if (waitForBootCompleted) {

            waitForBootCompleted()

            isBootCompleted()

        } else {

            isBootCompleted()

        }

        val newState = TeeProbeClassifier.resolveState(state, result)

        when (result) {

            TeeProbeResult.TRANSIENT -> DiagLog.teeTransient(bootCompleted)

            TeeProbeResult.BROKEN -> DiagLog.teePermanent(bootCompleted)

            TeeProbeResult.WORKING -> {}

        }

        transitionTo(newState)

        DiagLog.teeStatus(state == TeeState.WORKING)

    }



    private fun transitionTo(newState: TeeState) {

        val oldState =

            synchronized(stateLock) {

                val previous = state

                if (previous == newState) return

                state = newState

                if (newState != TeeState.WORKING) {

                    cachedAttestData = null

                }

                previous

            }

        DiagLog.teeStateTransition(oldState, newState)

        TeePhase3Trace.stateTransition(oldState, newState)

        stateListener?.invoke(oldState, newState)

    }



    private fun runProbeAttempt(attemptLabel: String) {

        when (val bootstrap = TeeProbeBootstrap.ensureInitialized()) {

            is TeeProbeBootstrap.Result.Failed -> {

                Logger.w("TEE probe bootstrap unavailable: ${bootstrap.cause.message}")

                return

            }

            is TeeProbeBootstrap.Result.Ready -> applyProbeResult(

                probeHardwareAttestationOnce(attemptLabel),

                waitForBootCompleted = false,

            )

        }

    }



    private fun probeHardwareAttestationOnce(attemptLabel: String): TeeProbeResult {

        val startNanos = System.nanoTime()

        val provider =

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                "AndroidKeyStore/keystore2"

            } else {

                "AndroidKeyStore/legacy"

            }

        DiagLog.teeProbeOperationStart(attempt = attemptLabel, provider = provider, strongBoxBacked = false)

        TeePhase3Trace.probeStart(attemptLabel)

        TeePhase3Trace.probeOperationStage(attemptLabel, "start")

        return try {

            DiagLog.teeProbeOperationStage(attemptLabel, "keystore_load")

            TeePhase3Trace.probeOperationStage(attemptLabel, "keystore_load")

            val keyStore = KeyStore.getInstance("AndroidKeyStore")

            keyStore.load(null)



            DiagLog.teeProbeOperationStage(attemptLabel, "kpg_init")

            TeePhase3Trace.probeOperationStage(attemptLabel, "kpg_init")

            val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")



            val challenge = ByteArray(16).apply { SecureRandom().nextBytes(this) }



            val parameterSpec =

                KeyGenParameterSpec.Builder(keygen_alias, KeyProperties.PURPOSE_SIGN)

                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))

                    .setDigests(KeyProperties.DIGEST_SHA256)

                    .setAttestationChallenge(challenge)

                    .setIsStrongBoxBacked(false)

                    .build()



            keyPairGenerator.initialize(parameterSpec)



            DiagLog.teeProbeOperationStage(attemptLabel, "generate_keypair")

            TeePhase3Trace.probeOperationStage(attemptLabel, "generate_keypair")

            keyPairGenerator.generateKeyPair()



            val durationMs = (System.nanoTime() - startNanos) / 1_000_000

            DiagLog.teeProbeOperationResult(attemptLabel, "success", durationMs)

            TeePhase3Trace.probeOperationResult(attemptLabel, "success")

            DiagLog.teeProbeSuccess(durationMs)

            TeePhase3Trace.probeSuccess(attemptLabel)

            Logger.d("TEE check: successful")

            TeeProbeResult.WORKING

        } catch (e: Exception) {

            val durationMs = (System.nanoTime() - startNanos) / 1_000_000

            DiagLog.teeProbeOperationResult(attemptLabel, "failure", durationMs)

            TeePhase3Trace.probeOperationResult(attemptLabel, "failure")

            DiagLog.teeProbeFailure(durationMs, e)

            Logger.w("TEE check failure: ${e.message}")

            val classification = TeeProbeClassifier.classifyException(e, isBootCompleted())

            TeePhase3Trace.probeResult(

                attemptLabel,

                classification,

                TeeProbeClassifier.extractPrimaryErrorCode(e),

            )

            classification

        }

    }



    private fun loadAttestData(): AttestationData? {

        val leaf = getAttestCert() ?: return null

        return parseAttestData(leaf)

    }



    private fun getAttestCert(): X509Certificate? {

        if (state != TeeState.WORKING) return null

        return try {

            val keyStore = KeyStore.getInstance("AndroidKeyStore")

            keyStore.load(null)



            val certChain = keyStore.getCertificateChain(keygen_alias)

            if (certChain == null || certChain.isEmpty()) {

                null

            } else {

                keyStore.deleteEntry(keygen_alias)

                certChain[0] as X509Certificate

            }

        } catch (e: Exception) {

            Logger.e("Failed to read attestation certificate: ${e.message}")

            null

        }

    }



    private fun parseAttestData(leaf: X509Certificate): AttestationData? {

        return try {

            val leafHolder = X509CertificateHolder(leaf.encoded)

            val ext = leafHolder.getExtension(ATTESTATION_OID)

            if (ext == null) {

                Logger.i("No attestation extension found on certificate")

                return null

            }



            val keyDescriptionSeq = ASN1Sequence.getInstance(ext.extnValue.octets)

            val encodables = keyDescriptionSeq.toArray()



            val attestVersion = ASN1Integer.getInstance(encodables[0]).value.toInt()

            val keymasterVersion = ASN1Integer.getInstance(encodables[2]).value.toInt()

            var attestVerifiedBootKey: ByteArray? = null

            var attestVerifiedBootHash: ByteArray? = null

            var attestOSVersion: Int? = null

            var attestModuleHash: ByteArray? = null



            val teeEnforced = ASN1Sequence.getInstance(encodables[7])



            teeEnforced.forEach { element ->

                val tagged = element as ASN1TaggedObject

                when (tagged.tagNo) {

                    704 -> {

                        val rootOfTrustSeq = ASN1Sequence.getInstance(tagged.baseObject.toASN1Primitive())

                        if (rootOfTrustSeq.size() >= 4) {

                            attestVerifiedBootKey = ASN1OctetString.getInstance(rootOfTrustSeq.getObjectAt(0)).octets

                            attestVerifiedBootHash = ASN1OctetString.getInstance(rootOfTrustSeq.getObjectAt(3)).octets

                        }

                    }

                    705 -> {

                        attestOSVersion = ASN1Integer.getInstance(tagged.baseObject.toASN1Primitive()).value.toInt()

                    }

                }

            }



            val softwareEnforced = encodables.getOrNull(6) as? ASN1Sequence

            softwareEnforced?.forEach { element ->

                val tagged = element as? ASN1TaggedObject ?: return@forEach

                if (tagged.tagNo == 724) {

                    attestModuleHash = ASN1OctetString.getInstance(tagged.baseObject.toASN1Primitive()).octets

                }

            }



            Logger.i("Extracted attestationVersion: $attestVersion")

            Logger.i("Extracted keymasterVersion: $keymasterVersion")

            Logger.i("Extracted verifiedBootKey: ${attestVerifiedBootKey?.toHex() ?: 0}")

            Logger.i("Extracted verifiedBootHash: ${attestVerifiedBootHash?.toHex() ?: 0}")

            Logger.i("Extracted osVersion: $attestOSVersion")



            AttestationData(

                verifiedBootKey = attestVerifiedBootKey,

                verifiedBootHash = attestVerifiedBootHash,

                attestVersion = attestVersion,

                keymasterVersion = keymasterVersion,

                osVersion = attestOSVersion,

                moduleHash = attestModuleHash,

            )

        } catch (e: Exception) {

            Logger.e("Failed to parse attestation data", e)

            null

        }

    }

}

